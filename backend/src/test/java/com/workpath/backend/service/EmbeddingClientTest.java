package com.workpath.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.workpath.backend.entity.ModelProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量化客户端单元测试。
 *
 * <p>用 JDK 自带的 HttpServer 起一个本地假服务商，走的是真实的
 * java.net.http.HttpClient 网络路径——不是 mock，所以能真正验证三件事：</p>
 * <ol>
 *   <li>请求体按 UTF-8 编码发送（Git Bash 的 curl 会把中文按 GBK 发出去，
 *       服务商解析成乱码报 400，这里必须堵住）；</li>
 *   <li>维度不符时直接失败，不静默截断；</li>
 *   <li>单条失败不影响同批次的其它条款。</li>
 * </ol>
 */
class EmbeddingClientTest {

    private HttpServer server;
    private String baseUrl;

    /** 假服务商实际收到的请求体，用于校验编码 */
    private volatile String receivedBody;

    /** 假服务商返回的向量维度；0 表示响应里不放 embedding 数组 */
    private volatile int dim = 1024;

    /** 请求体含此串时返回 500，用于构造失败场景 */
    private static final String POISON = "触发服务商错误";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", this::handle);
        server.setExecutor(null);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            receivedBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            String contentType = ex.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.contains("charset=utf-8")) {
                send(ex, 400, "{\"error\":{\"message\":\"missing charset in Content-Type\"}}");
                return;
            }
            if (receivedBody.contains(POISON)) {
                send(ex, 500, "{\"error\":{\"message\":\"quota exceeded\"}}");
                return;
            }
            if (dim <= 0) {
                send(ex, 200, "{\"data\":[{\"index\":0}]}");
                return;
            }
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < dim; i++) {
                if (i > 0) {
                    arr.append(',');
                }
                arr.append(i == 0 ? "0.5" : "0.1");
            }
            arr.append(']');
            String body = "{\"data\":[{\"embedding\":" + arr + ",\"index\":0}],\"model\":\"BAAI/bge-m3\"}";
            send(ex, 200, body);
        } catch (Exception e) {
            // 处理器里抛异常会让客户端挂住，必须兜住并返回
            try {
                send(ex, 500, "{\"error\":{\"message\":\"handler crashed\"}}");
            } catch (Exception ignore) {
                // 连接已断，无处可报
            }
        }
    }

    private void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private EmbeddingClient client(int declaredDimension) {
        // modelProviderService 为 null 时 embed() 会抛 BusinessException（missingConfigThrows 覆盖），
        // embedWith(config, text) 走的是注入的配置路径，不碰它
        return new EmbeddingClient(null, HttpClient.newBuilder().build());
    }

    private ModelProviderConfig config(int declaredDimension) {
        ModelProviderConfig c = new ModelProviderConfig();
        c.setBaseUrl(baseUrl + "/v1");
        c.setApiKey("sk-test");
        c.setModelName("BAAI/bge-m3");
        c.setExtraConfig(declaredDimension > 0 ? "{\"dimension\":" + declaredDimension + "}" : null);
        return c;
    }

    @Test
    @DisplayName("中文条款正常向量化，返回维度与声明一致")
    void embedChineseText() {
        EmbeddingClient ec = client(1024);
        Optional<double[]> vec = ec.embedWith(config(1024), "员工因公外出应提前报备，经部门负责人批准后方可出行。");
        assertThat(vec).isPresent();
        assertThat(vec.get()).hasSize(1024);
    }

    @Test
    @DisplayName("请求体以 UTF-8 发送，服务商收到的中文没有乱码")
    void sendsUtf8Body() {
        EmbeddingClient ec = client(1024);
        String clause = "外勤人员严禁工作时间离岗外出处理私人事务。";
        assertThat(ec.embedWith(config(1024), clause)).isPresent();
        assertThat(receivedBody)
                .isNotNull()
                .contains("外勤人员严禁工作时间离岗外出处理私人事务");
    }

    @Test
    @DisplayName("请求只发送 input 字符串，不带数组形式也不带 dimensions 参数")
    void inputIsPlainString() {
        EmbeddingClient ec = client(1024);
        ec.embedWith(config(1024), "测试");
        assertThat(receivedBody).contains("\"input\":\"测试\"");
        assertThat(receivedBody).doesNotContain("dimensions");
        assertThat(receivedBody).doesNotContain("\"input\":[");
    }

    @Test
    @DisplayName("接口返回维度与配置声明不一致时失败，不静默截断")
    void dimensionMismatchFails() {
        EmbeddingClient ec = client(512);
        assertThat(ec.embedWith(config(512), "测试")).isEmpty();
    }

    @Test
    @DisplayName("配置没声明维度时以接口返回为准")
    void noDeclaredDimensionUsesActual() {
        EmbeddingClient ec = client(0);
        Optional<double[]> vec = ec.embedWith(config(0), "测试");
        assertThat(vec).isPresent();
        assertThat(vec.get()).hasSize(1024);
    }

    @Test
    @DisplayName("响应缺少 embedding 数组时失败")
    void missingEmbeddingFails() {
        dim = 0;
        EmbeddingClient ec = client(0);
        assertThat(ec.embedWith(config(0), "测试")).isEmpty();
    }

    @Test
    @DisplayName("服务商报错时本条失败，同批次其它条仍成功")
    void batchIsolatesFailures() {
        EmbeddingClient ec = client(1024);
        List<Optional<double[]>> out = ec.embedAll(config(1024), List.of(
                "正常条款一",
                "正常条款二" + POISON,
                "正常条款三"));
        assertThat(out).hasSize(3);
        assertThat(out.get(0)).isPresent();
        assertThat(out.get(1)).isEmpty();
        assertThat(out.get(2)).isPresent();
        // 顺序不能因为并发而错乱
        assertThat(out.get(2).get()[1]).isEqualTo(0.1d);
    }

    @Test
    @DisplayName("未配置 EMBEDDING 时 embed() 明确失败，不把没配模型伪装成空结果")
    void missingConfigThrows() {
        // service 未注入时的兜底：必须抛 BusinessException 而不是 NPE。
        // 空返回值会被上层当成"只是没检索到"，把"没配模型"这个真问题吞掉
        EmbeddingClient ec = new EmbeddingClient(null, HttpClient.newBuilder().build());
        assertThatThrownBy(() -> ec.embed("测试"))
                .isInstanceOf(com.workpath.backend.common.BusinessException.class)
                .hasMessageContaining("EMBEDDING");
    }

    @Test
    @DisplayName("空文本直接跳过，不发请求")
    void blankTextSkipped() {
        dim = 1024;
        EmbeddingClient ec = client(1024);
        String before = receivedBody;
        assertThat(ec.embedWith(config(1024), "   ")).isEmpty();
        assertThat(receivedBody).isSameAs(before);
    }
}
