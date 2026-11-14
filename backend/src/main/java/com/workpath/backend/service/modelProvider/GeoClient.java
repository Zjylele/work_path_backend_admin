package com.workpath.backend.service.modelProvider;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高德逆地理编码客户端（W4 POI 解析）。
 *
 * <p>三条硬约束，对应实施指南 W4 的「POI 的三个坑」：</p>
 * <ol>
 *   <li><b>失败绝不抛异常</b>。POI 缺失不影响事件本身，poi_name 留 NULL
 *       即可，语义标注照常进行。若这里抛出去，整条轨迹分析就失败了——
 *       为一个小功能把主链路拖挂，不划算。</li>
 *   <li><b>有本地缓存</b>。高德 Web 服务有 QPS 限制，而「同一坐标一天内
 *       重复命中」是常见形态（员工每天在同一处打卡）。缓存按四舍五入到
 *       5 位小数的坐标做 key，约 1 米精度，足以把同一次停留内的若干点
 *       收敛成一次请求。</li>
 *   <li><b>失败不进缓存</b>。key 填错或配额用尽时若把失败也缓存住，
 *       修好配置后必须重启服务才生效，现场排查极其费时。</li>
 * </ol>
 */
public class GeoClient {

    private static final Logger log = LoggerFactory.getLogger(GeoClient.class);

    /**
     * 坐标缓存精度（小数位数）。5 位约 1 米。
     *
     * <p>取 5 位而不是 6 位：GPS 静止时的正常抖动量级在 1-5 米，
     * 5 位已足以让同一次停留内的相邻点命中同一条缓存，
     * 又不至于把隔着几米的两个点误合并。</p>
     */
    private static final int COORD_SCALE = 5;

    /**
     * 连接与读取超时。外网抖一下很正常，但不能让它拖垮整批分析——
     * 批次最多 10 条串行执行，每条都要等 POI，超时一长整批就卡住了。
     */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 坐标 key → 地名。
     *
     * <p>value 直接存 String 而不是 Optional&lt;String&gt;：这张表只存放<b>成功</b>
     * 的结果，所以"没查过"表现为 key 不存在，"查过但失败"根本不会写进来。
     * 若把失败也以 empty 的 Optional 存进来，就分不出这两者了。</p>
     */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private final String baseUrl;

    private final String apiKey;

    private final HttpClient httpClient;

    public GeoClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, HTTP_TIMEOUT);
    }

    public GeoClient(String baseUrl, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        Duration effective = timeout == null ? HTTP_TIMEOUT : timeout;
        // 每实例一个连接池。POI 解析有本地缓存兜着，请求量小，
        // 不必像 EmbeddingClient 那样共享容器里的单例
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(effective)
                .build();
    }

    /**
     * 逆地理编码。
     *
     * @param lat 纬度，-90 ~ 90
     * @param lng 经度，-180 ~ 180
     * @return 地点名；未配置、坐标非法、请求失败、高德报错、
     *         响应里没有 formatted_address 时一律返回 empty
     */
    public Optional<String> reverseGeocode(Double lat, Double lng) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        if (!isValidLatLng(lat, lng)) {
            return Optional.empty();
        }

        String key = coordKey(lat, lng);
        String cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<String> resolved = query(lat, lng);
        // 只缓存成功结果。失败多半是临时的（限流、网络抖动、key 填错），
        // 缓存住会让「修好配置必须重启才生效」
        resolved.ifPresent(v -> cache.put(key, v));
        return resolved;
    }

    /** 是否已配置可用。 */
    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty();
    }

    /** 已缓存的坐标条目数，测试与诊断用。 */
    public int cacheSize() {
        return cache.size();
    }

    /** 真正发请求。任何异常都就地转成 empty。 */
    private Optional<String> query(Double lat, Double lng) {
        // 高德 v3 regeo 要求 location = "经度,纬度"。
        // 顺序反了不会报错（两边都在合法范围内），只会解析到另一个地方，
        // 这种静默错位的排查成本极高，所以这里显式注释钉住。
        //
        // URL 手工拼 + URLEncoder：占位符展开会把 location 当普通文本塞 URI，
        // 逗号不被编码成 %2C，某些代理层会把它当参数分隔符，高德那头只收到
        // 一段坐标，解析到错位置且不报错。
        String location = fullPrecision(lng) + "," + fullPrecision(lat);

        try {
            String url = baseUrl + "/v3/geocode/regeo?key=" + encode(apiKey)
                    + "&location=" + encode(location);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = send(req);

            if (resp.statusCode() / 100 != 2) {
                log.warn("逆地理编码 HTTP {} key=…{}", resp.statusCode(), tail(apiKey));
                return Optional.empty();
            }

            // 用原生 ObjectMapper 而非 RestTemplate 的 Map 反序列化。
            // 实测踩过的坑：RestTemplate 走 Spring 的
            // MappingJackson2HttpMessageConverter，本项目里它把高德响应中的
            // 每个字符串字段都解析成了空 List（formatted_address=[]、
            // province=[] ...），而同一响应用 curl 与原生 ObjectMapper
            // 都拿到正常字符串。EmbeddingClient/ChatClient 一直走这条原生
            // 链路且 M6 已实测可用，这里与它们保持一致。
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(resp.body());
            if (!"1".equals(root.path("status").asText())) {
                log.debug("逆地理编码未返回成功 key=…{} info={}",
                        tail(apiKey), root.path("info").asText());
                return Optional.empty();
            }
            JsonNode address = root.path("regeocode").path("formatted_address");
            if (!address.isTextual() || address.asText().isBlank()) {
                // isTextual 只对字符串为真：高德对个别坐标会把这个字段返成
                // 空数组，此时 asText() 得到 ""，若照落就成了空地点名
                log.debug("逆地理编码未解析到地名 key=…{} nodeType={}",
                        tail(apiKey), address.getNodeType());
                return Optional.empty();
            }
            return Optional.of(address.asText().trim());
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            // IOException 覆盖超时、连接失败、DNS 失败；
            // IllegalArgumentException 覆盖 URI 里出现非法字符。
            // 两者都不该让轨迹分析失败
            log.warn("逆地理编码查询异常 key=…{}: {}", tail(apiKey), e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    /** 发 GET。单独抽一层是为了测试能替换成不发真请求的实现。 */
    HttpResponse<String> send(HttpRequest req) throws IOException, InterruptedException {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static boolean isValidLatLng(Double lat, Double lng) {
        return lat != null && lng != null
                && lat >= -90.0 && lat <= 90.0
                && lng >= -180.0 && lng <= 180.0;
    }

    /**
     * 请求里用的坐标：6 位小数（约 0.1 米）后去掉多余的 0。
     *
     * <p>比缓存精度高一位——缓存只要求"同一个停留内的点收敛成一次请求"，
     * 而请求本身要尽量保留原始精度，否则 POI 会是几十米外的地名。</p>
     */
    private static String fullPrecision(double v) {
        return BigDecimal.valueOf(v)
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * URL 查询参数编码。
     *
     * <p>手工拼 URL 就必须自己编码：逗号若不转成 %2C，某些代理层会把它
     * 当成参数分隔符，高德那头拿到的 location 就只有一段，解析到错位置
     * 而且不报错。</p>
     */
    private static String encode(String raw) throws UnsupportedEncodingException {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String coordKey(double lat, double lng) {
        return round(lat) + ',' + round(lng);
    }

    private static String round(double v) {
        return BigDecimal.valueOf(v)
                .setScale(COORD_SCALE, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String tail(String key) {
        return key.length() <= 4 ? key : key.substring(key.length() - 4);
    }
}
