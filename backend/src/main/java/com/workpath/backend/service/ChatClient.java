package com.workpath.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;

/**
 * 对话模型客户端。走 model_provider_config 里 CHAT 用途的服务商（DeepSeek）。
 *
 * <p>契约：</p>
 * <ul>
 *   <li><b>system 与 user 分两条消息</b>。拼成一条会显著降低模型对"必须依据给定
 *       条款回答、不得编造"这类约束的遵循度——测试里用
 *       {@code receivedBody.indexOf("system") < indexOf("user")} 钉住顺序。</li>
 *   <li><b>body 显式 UTF-8</b>。制度条款与问题都是中文，少了 charset 声明
 *       服务端可能按别的编码猜，猜错问句就变乱码，答案自然偏题。</li>
 *   <li><b>失败返回 empty 不抛</b>。问答失败该让用户看到"模型暂时不可用"，
 *       而不是把 HTTP 栈暴露到接口层；调用方拿 empty 再决定怎么降级。</li>
 * </ul>
 *
 * <p>HttpClient 走构造注入，测试换成指向本地假服务商的实例即可，
 * 生产用 Spring 容器里那个复用连接池的单例（见 HttpClientConfig）。</p>
 */
@Slf4j
@Component
public class ChatClient {

    /** 同 EmbeddingClient：条款文本长，模型生成也要时间，30 秒比 10 秒稳妥 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** 从错误体里摘出的原因最大长度，避免把整段 JSON 扔进日志 */
    private static final int LOG_LIMIT = 120;

    private final ModelProviderService modelProviderService;
    private final HttpClient httpClient;

    public ChatClient(ModelProviderService modelProviderService, HttpClient httpClient) {
        this.modelProviderService = modelProviderService;
        this.httpClient = httpClient;
    }

    /**
     * 用默认的 CHAT 配置对话。
     *
     * @param system 系统提示（角色与约束）
     * @param user   用户提示（问题 + 上下文）
     * @return 答案文本；未配置、网络失败、服务商报错、应答为空时返回 empty
     */
    public Optional<String> chat(String system, String user) {
        if (modelProviderService == null) {
            log.warn("对话失败：ModelProviderService 未注入");
            return Optional.empty();
        }
        ModelProviderConfig config = modelProviderService.requireConfig(
                ModelProviderService.PURPOSE_CHAT);
        return chat(config, system, user);
    }

    /** 用指定配置对话。失败只返回 empty，不抛。 */
    public Optional<String> chat(ModelProviderConfig config, String system, String user) {
        if (config == null) {
            log.warn("对话失败：未提供模型接入配置");
            return Optional.empty();
        }
        if (user == null || user.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(doChat(config, system, user));
        } catch (Exception e) {
            log.warn("对话失败 model={} : {}", config.getModelName(), e.getMessage());
            return Optional.empty();
        }
    }

    private String doChat(ModelProviderConfig config, String system, String user)
            throws IOException, InterruptedException {
        String url = trimSlash(config.getBaseUrl()) + "/chat/completions";
        // 用户可能已填完整端点，拼重了要收敛
        if (url.endsWith("/chat/completions/chat/completions")) {
            url = url.replace("/chat/completions/chat/completions", "/chat/completions");
        }

        // 关键：system 与 user 分两条 message，system 在前
        String body = "{"
                + "\"model\":\"" + escapeJson(config.getModelName()) + "\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + escapeJson(system == null ? "" : system) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escapeJson(user) + "\"}"
                + "],"
                // 低温度：问答要的是稳定复现，不是文采
                + "\"temperature\":0.2,"
                + "\"stream\":false"
                + "}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
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
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            // 应答为空与请求失败是两回事：前者多半是模型拒答或内容被过滤
            throw new IllegalStateException("响应缺少可用的 message.content");
        }
        return content.asText();
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }

    private static String abbreviate(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String t = s.strip();
        return t.length() <= LOG_LIMIT ? t : t.substring(0, LOG_LIMIT) + "…";
    }

    // 转义规则与 EmbeddingClient 保持一致：引号、反斜杠、控制字符
    private static String escapeJson(String s) {
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
                        // 转义序列必须运行时生成，源码里不能直接写这类字面量
                        // ——Java 编译期会先做 Unicode 转义预处理
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
