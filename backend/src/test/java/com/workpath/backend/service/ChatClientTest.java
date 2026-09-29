package com.workpath.backend.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话模型客户端单元测试。
 *
 * <p>用 JDK 自带 HttpServer 起本地假 DeepSeek，走真实网络路径。钉住四件事：
 * system prompt 与 user prompt 分两条消息发送（不是拼成一条）、
 * body 按 UTF-8 显式编码（中文制度条款）、
 * 响应里 choices[0].message.content 的解析、
 * 服务商报错/网关错误时返回 empty 而不抛（调用方决定怎么降级）。</p>
 */
class ChatClientTest {

    private HttpServer server;
    private String baseUrl;

    /** 假服务商收到的完整请求体，用于校验消息结构与编码 */
    private volatile String receivedBody;
    /** 假服务商收到的 Content-Type */
    private volatile String receivedContentType;
    /** 假服务商收到的 Authorization 头 */
    private volatile String receivedAuth;
    /** 假服务商返回的答案文本；空串表示响应缺 content */
    private volatile String reply = "依据条款 1.1，需要提前报备。";
    /** 请求体含此串时返回 500，用于构造失败场景 */
    private static final String POISON = "触发服务商错误";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.setExecutor(null);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
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
            receivedContentType = ex.getRequestHeaders().getFirst("Content-Type");
            receivedAuth = ex.getRequestHeaders().getFirst("Authorization");

            if (receivedContentType == null || !receivedContentType.contains("charset=utf-8")) {
                send(ex, 400, "{\"error\":{\"message\":\"missing charset in Content-Type\"}}");
                return;
            }
            if (receivedBody.contains(POISON)) {
                send(ex, 500, "{\"error\":{\"message\":\"upstream timeout\"}}");
                return;
            }
            if (reply.isEmpty()) {
                send(ex, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"index\":0}]}");
                return;
            }
            String body = "{\"id\":\"chatcmpl-test\",\"model\":\"deepseek-flash\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                    + reply + "\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"total_tokens\":42}}";
            send(ex, 200, body);
        } catch (Exception e) {
            try {
                send(ex, 500, "{\"error\":{\"message\":\"handler crashed\"}}");
            } catch (Exception ignore) {
                // 连接已断
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

    private ChatClient client() {
        return new ChatClient(null, HttpClient.newBuilder().build());
    }

    private ModelProviderConfigStub config() {
        return new ModelProviderConfigStub(baseUrl, "sk-test-key", "deepseek-flash");
    }

    /** 用 builder 风格构造一个最小配置，避免引入 entity 依赖的歧义 */
    private record ModelProviderConfigStub(String baseUrl, String apiKey, String modelName) {
        com.workpath.backend.entity.ModelProviderConfig toEntity() {
            com.workpath.backend.entity.ModelProviderConfig c =
                    new com.workpath.backend.entity.ModelProviderConfig();
            c.setBaseUrl(baseUrl);
            c.setApiKey(apiKey);
            c.setModelName(modelName);
            return c;
        }
    }

    @Test
    @DisplayName("system 与 user 分成两条消息，且顺序正确")
    void twoMessages() {
        ChatClient cc = client();
        Optional<String> out = cc.chat(config().toEntity(),
                "你是制度问答助手", "外出要报备吗");

        assertThat(out).isPresent();
        // user 在前 system 在后会显著降低遵循度，必须保证顺序
        assertThat(receivedBody).contains("\"role\":\"system\"");
        assertThat(receivedBody).contains("\"role\":\"user\"");
        assertThat(receivedBody).contains("你是制度问答助手");
        assertThat(receivedBody).contains("外出要报备吗");
        assertThat(receivedBody.indexOf("\"system\""))
                .isLessThan(receivedBody.indexOf("\"user\""));
    }

    @Test
    @DisplayName("中文按 UTF-8 发送，假服务商收到的不是乱码")
    void utf8Body() {
        ChatClient cc = client();
        cc.chat(config().toEntity(), "系统提示", "外勤人员外出需要提前报备吗");

        assertThat(receivedBody).contains("外勤人员外出需要提前报备吗");
        assertThat(receivedContentType).contains("charset=utf-8");
    }

    @Test
    @DisplayName("Authorization 用 Bearer + apiKey")
    void authHeader() {
        ChatClient cc = client();
        cc.chat(config().toEntity(), "s", "u");
        assertThat(receivedAuth).isEqualTo("Bearer sk-test-key");
    }

    @Test
    @DisplayName("解析 choices[0].message.content 为答案")
    void parsesContent() {
        ChatClient cc = client();
        Optional<String> out = cc.chat(config().toEntity(), "s", "u");
        assertThat(out).contains("依据条款 1.1，需要提前报备。");
    }

    @Test
    @DisplayName("服务商返回 5xx：返回 empty 不抛异常")
    void serverErrorReturnsEmpty() {
        ChatClient cc = client();
        Optional<String> out = cc.chat(config().toEntity(), "s", POISON);
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("响应缺 choices / 连不上服务商：返回 empty 不抛")
    void missingChoicesReturnsEmpty() {
        ChatClient cc = client();
        // 端口 1 不监听：走连接失败路径
        assertThat(cc.chat(configWithPort(1).toEntity(), "s", "u")).isEmpty();
    }

    private ModelProviderConfigStub configWithPort(int port) {
        return new ModelProviderConfigStub(
                "http://127.0.0.1:" + port + "/v1", "sk-test", "deepseek-flash");
    }

    @Test
    @DisplayName("应答为空串：返回 empty，调用方据此判定为失败")
    void blankReplyReturnsEmpty() {
        reply = "";
        ChatClient cc = client();
        assertThat(cc.chat(config().toEntity(), "s", "u")).isEmpty();
    }
}
