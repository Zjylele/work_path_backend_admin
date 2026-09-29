package com.workpath.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.ModelProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 向量化客户端。调 model_provider_config 里 EMBEDDING 用途的服务商（硅基流动 BAAI/bge-m3）。
 *
 * <p>三个设计约束，全部来自实测踩过的坑（详见 {@code 08-P0环境与API实测记录.md}）：</p>
 * <ol>
 *   <li><b>input 必须是纯字符串</b>。硅基流动的 /v1/embeddings 不收数组形式，
 *       也不收 dimensions 参数——两者都返回 {@code 400 code 20015}，
 *       尽管它对外声称 OpenAI 兼容。传数组会让整个文档上传失败。</li>
 *   <li><b>body 显式 UTF-8 编码</b>。制度条款全是中文，少了 charset 声明
 *       服务商可能按别的编码猜，猜错就是乱码。Git Bash 的 curl -d 会把中文
 *       按 GBK 发送，正是这个问题的另一条路径。</li>
 *   <li><b>失败不抛，返回 Optional.empty</b>。调用方据此只让该条款
 *       vector_data 留空，不让单条失败阻塞整个文档入库
 *       （实施指南 W2 的健壮性要求）。</li>
 * </ol>
 *
 * <p>还有一个不在上述列表但同样重要的点：维度校验。接口返回的维度若与
 * extra_config 里声明的不一致，直接判失败。此时若仍把向量写进库，
 * 不同维度的向量混在一张表里，后续余弦相似度算出来的排序错得毫无线索。</p>
 */
@Slf4j
@Component
public class EmbeddingClient {

    /** 服务商标注，用于日志与审计，不参与请求 */
    private static final String PROVIDER_SILICONFLOW = "siliconflow";

    /** 单次 HTTP 超时。条款文本最长约 2000 字，1024 维输出，10 秒足够 */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * 并发数。刻意保守：条款文本长（一条制度条款常有几百字），
     * 并发过高既容易触发服务商限流，也没必要——文档量级在几十条。
     */
    private static final int PARALLELISM = 4;

    private final ModelProviderService modelProviderService;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    /**
     * 可用 HttpClient 注入，测试里换成本地假服务商的配置即可。
     */
    public EmbeddingClient(ModelProviderService modelProviderService, HttpClient httpClient) {
        this.modelProviderService = modelProviderService;
        this.httpClient = httpClient;
        this.executor = Executors.newFixedThreadPool(PARALLELISM, r -> {
            Thread t = new Thread(r, "embedding-client");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 用默认的 EMBEDDING 配置向量化单条文本。
     *
     * @return 有向量时返回；失败（含未配置、超时、维度不符）时返回 empty
     * @throws BusinessException 若模型接入配置缺失——"没有配置"与"调用失败"是两件事，
     *                           前者必须让调用方看到明确原因，而不是被当成空结果咽下去
     */
    public Optional<double[]> embed(String text) {
        if (modelProviderService == null) {
            throw new BusinessException(ResultCode.ERROR.getCode(),
                    "EMBEDDING 模型接入配置缺失：ModelProviderService 未注入");
        }
        ModelProviderConfig config = modelProviderService.requireConfig(
                ModelProviderService.PURPOSE_EMBEDDING);
        return embedWith(config, text);
    }

    /**
     * 用指定配置向量化单条文本。失败只返回 empty，不抛。
     */
    public Optional<double[]> embedWith(ModelProviderConfig config, String text) {
        if (config == null) {
            log.warn("向量化失败：未提供模型接入配置");
            return Optional.empty();
        }
        if (text == null || text.isBlank()) {
            // 空白文本不产生有意义的向量，也不值得为它发一次请求
            return Optional.empty();
        }
        try {
            return Optional.of(doEmbed(config, text));
        } catch (Exception e) {
            log.warn("向量化失败 text={} : {}", abbreviate(text), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 批量向量化。返回值与入参按位置一一对应，调用方靠位置对齐条款。
     *
     * <p>失败位置放 Optional.empty，同批次其它条正常出向量——
     * 这是"单条失败不阻塞文档入库"的实现位置。</p>
     */
    public List<Optional<double[]>> embedAll(ModelProviderConfig config, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<Optional<double[]>>> futures = new ArrayList<>(texts.size());
        for (String t : texts) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> embedWith(config, t), executor));
        }
        List<Optional<double[]>> out = new ArrayList<>(texts.size());
        for (CompletableFuture<Optional<double[]>> f : futures) {
            out.add(f.join());
        }
        return Collections.unmodifiableList(out);
    }

    /** 真正发请求。返回向量；任何异常由调用方统一转成 Optional.empty。 */
    private double[] doEmbed(ModelProviderConfig config, String text)
            throws IOException, InterruptedException {
        String url = trimSlash(config.getBaseUrl()) + "/embeddings";
        // 用户可能填了完整端点，此时会拼成 /embeddings/embeddings。
        // 实测硅基流动对这种地址能容错，但不该依赖服务商的容错行为。
        if (url.endsWith("/embeddings/embeddings")) {
            url = url.replace("/embeddings/embeddings", "/embeddings");
        }

        // input 只传纯字符串：不收数组形式，也不传 dimensions 参数。
        // 文本用 escapeJson 转义，防止条款里的引号、换行把 JSON 结构破坏。
        String body = "{\"model\":\"" + escapeJson(config.getModelName()) + "\","
                + "\"input\":\"" + escapeJson(text) + "\"}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                // 显式 charset：少了这个头，某些服务端会按自己的默认编码猜，
                // 猜错就是乱码向量——而且乱码向量的相似度结果看起来一切正常。
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + config.getApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode()
                    + "：" + abbreviate(resp.body()));
        }

        JsonNode root = new ObjectMapper().readTree(resp.body());
        JsonNode vec = root.path("data").path(0).path("embedding");
        if (!vec.isArray() || vec.isEmpty()) {
            throw new IllegalStateException("响应缺少 embedding 数组");
        }

        double[] out = new double[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
            JsonNode e = vec.get(i);
            if (!e.isNumber()) {
                throw new IllegalStateException("embedding 第 " + i + " 个元素不是数字");
            }
            out[i] = e.doubleValue();
        }

        int declared = readDeclaredDimension(config.getExtraConfig());
        if (declared > 0 && declared != out.length) {
            // 维度不符必须拦住：混用维度的向量会让相似度排序错得没有线索
            throw new IllegalStateException("向量维度不符：接口返回 " + out.length
                    + " 维，配置声明 " + declared + " 维");
        }
        return out;
    }

    /**
     * 从 extra_config JSON 里读 dimension。读不到或非法都返回 0（不校验）。
     *
     * <p>与 ModelProviderService#readExpectedDimension 保持同一口径：
     * 声明了才校验，没声明就按接口实际返回的维度走。</p>
     */
    static int readDeclaredDimension(String extraConfig) {
        if (extraConfig == null || extraConfig.isBlank()) {
            return 0;
        }
        try {
            JsonNode n = new ObjectMapper().readTree(extraConfig);
            int v = n.path("dimension").asInt(0);
            return v > 0 ? v : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 关闭线程池。Spring 容器销毁时会调用，否则守护线程会阻止 JVM 退出。 */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final int LOG_LIMIT = 60;

    private static String abbreviate(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String t = s.strip();
        return t.length() <= LOG_LIMIT ? t : t.substring(0, LOG_LIMIT) + "…";
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }

    /**
     * JSON 字符串转义。
     *
     * <p>不用外部库，只需要处理引号、反斜杠与控制字符——
     * 这三个是会把 JSON 结构破坏的。制度原文里的中文按 UTF-8 直出，无需 unicode 转义。</p>
     */
    static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        // 转义序列必须运行时生成，不能在源码里直接写字面量形式
                        // ——Java 编译期会先预处理这类转义，直接写会编译失败
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
