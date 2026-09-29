package com.workpath.backend.service.modelProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 逆地理编码客户端测试。
 *
 * <p>覆盖实施指南 W4 POI 的三个坑：需要独立的 Web 服务 Key、有 QPS 限制、
 * <b>API 失败不能阻塞分析流程</b>（poi_name 留 NULL，语义标注照常）。
 * 所以「失败返回 empty 而不抛」是这里最重要的一条。</p>
 */
class GeoClientTest {

    // ============================================================
    // 失败路径：必须返回 empty，绝不能抛
    // ============================================================

    @Test
    @DisplayName("请求失败时返回 empty 而不是抛异常——POI 缺失不能阻塞轨迹分析")
    void failureReturnsEmptyNotThrows() {
        // 指向一个不存在的地址：DNS 解析就会失败
        GeoClient client = new GeoClient(
                "http://127.0.0.1:1", "dummy-key", java.time.Duration.ofMillis(3_000));

        assertThat(client.reverseGeocode(30.657261, 104.066678))
                .as("网络异常必须被吞掉并转成 empty，否则整个 analyze() 会失败")
                .isEmpty();
    }

    @Test
    @DisplayName("坐标越界时直接返回 empty，不发请求")
    void invalidCoordinatesShortCircuit() {
        GeoClient client = new GeoClient(
                "http://127.0.0.1:1", "dummy-key", java.time.Duration.ofMillis(3_000));

        // 纬度上限 90、经度上限 180
        assertThat(client.reverseGeocode(91.0, 104.0)).isEmpty();
        assertThat(client.reverseGeocode(30.0, 181.0)).isEmpty();
        assertThat(client.reverseGeocode((Double) null, (Double) null)).isEmpty();
    }

    @Test
    @DisplayName("超时被限制在秒级——外网抖动不能拖垮定时任务的整轮处理")
    void timeoutIsBounded() {
        // 1 毫秒超时对任何真实请求都必然超时，这里验证的是
        // 客户端真的会因超时返回 empty 而不是永久挂起
        GeoClient client = new GeoClient(
                "http://10.255.255.1", "k", java.time.Duration.ofMillis(1));

        long t0 = System.currentTimeMillis();
        Optional<String> result = client.reverseGeocode(30.657261, 104.066678);
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(result).isEmpty();
        assertThat(elapsed)
                .as("超时应生效，不能让调用方无限等待")
                .isLessThan(60_000);
    }

    // ============================================================
    // 正常路径：用本地假高德服务验证解析逻辑
    // ============================================================

    @Test
    @DisplayName("parse amap v3 response and take formatted address")
    void parsesFormattedAddress() throws Exception {
        String body = "{\"status\":\"1\",\"info\":\"OK\","
                + "\"regeocode\":{\"formatted_address\":\"四川省成都市青羊区西御河街道天府广场\"}}";
        FakeGeoServer server = new FakeGeoServer(body);
        server.start();
        try {
            GeoClient client = new GeoClient(server.baseUrl(), "k", java.time.Duration.ofMillis(5_000));
            Optional<String> poi = client.reverseGeocode(30.657261, 104.066678);

            assertThat(poi).contains("四川省成都市青羊区西御河街道天府广场");
            // 经纬度必须按「经度,纬度」传给高德，且逗号必须编码成 %2C。
            // 顺序反了不会报错（两边都在合法范围内），只会解析到别的位置，
            // 而高德照常返回一个看似正常的结果，极难发现
            assertThat(server.lastQuery)
                    .as("location 参数必须是「经度,纬度」且逗号已编码，实际收到：%s", server.lastQuery)
                    .isNotNull()
                    .contains("location=104.066678%2C30.657261");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("status != 1 时返回 empty（key 错、配额超限、坐标非法）")
    void errorStatusReturnsEmpty() throws Exception {
        String body = "{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\",\"infocode\":\"10001\"}";
        FakeGeoServer server = new FakeGeoServer(body);
        server.start();
        try {
            GeoClient client = new GeoClient(server.baseUrl(), "wrong-key", java.time.Duration.ofMillis(5_000));
            assertThat(client.reverseGeocode(30.657261, 104.066678)).isEmpty();
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("响应是合法 JSON 但没有 formatted_address 时返回 empty")
    void missingAddressReturnsEmpty() throws Exception {
        String body = "{\"status\":\"1\",\"info\":\"OK\",\"regeocode\":{}}";
        FakeGeoServer server = new FakeGeoServer(body);
        server.start();
        try {
            GeoClient client = new GeoClient(server.baseUrl(), "k", java.time.Duration.ofMillis(5_000));
            assertThat(client.reverseGeocode(30.657261, 104.066678)).isEmpty();
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("响应不是 JSON 时返回 empty，不把解析异常漏出去")
    void malformedBodyReturnsEmpty() throws Exception {
        FakeGeoServer server = new FakeGeoServer("not json at all");
        server.start();
        try {
            GeoClient client = new GeoClient(server.baseUrl(), "k", java.time.Duration.ofMillis(5_000));
            assertThat(client.reverseGeocode(30.657261, 104.066678)).isEmpty();
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("空字符串地名视为解析失败")
    void blankAddressReturnsEmpty() throws Exception {
        String body = "{\"status\":\"1\",\"info\":\"OK\","
                + "\"regeocode\":{\"formatted_address\":\"   \"}}";
        FakeGeoServer server = new FakeGeoServer(body);
        server.start();
        try {
            GeoClient client = new GeoClient(server.baseUrl(), "k", java.time.Duration.ofMillis(5_000));
            assertThat(client.reverseGeocode(30.657261, 104.066678)).isEmpty();
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("同一坐标重复请求命中缓存，不再打服务商（QPS 限制的应对）")
    void repeatedQueryIsCached() throws Exception {
        String body = "{\"status\":\"1\",\"info\":\"OK\","
                + "\"regeocode\":{\"formatted_address\":\"成都市青羊区天府广场\"}}";
        FakeGeoServer server = new FakeGeoServer(body);
        server.start();
        try {
            GeoClient client = new GeoClient(server.baseUrl(), "k", java.time.Duration.ofMillis(5_000));
            Optional<String> first = client.reverseGeocode(30.657261, 104.066678);
            Optional<String> second = client.reverseGeocode(30.657261, 104.066678);

            assertThat(first).contains("成都市青羊区天府广场");
            assertThat(second).isPresent();
            assertThat(server.hitCount())
                    .as("第二次请求应命中本地缓存，不再打高德——同坐标一天内会重复命中")
                    .isEqualTo(1);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("失败结果不进缓存（key 修好后重试应该真的再发请求）")
    void failureIsNotCached() throws Exception {
        String body = "{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}";
        FakeGeoServer server = new FakeGeoServer(body);
        server.start();
        try {
            GeoClient client = new GeoClient(server.baseUrl(), "k", java.time.Duration.ofMillis(5_000));
            assertThat(client.reverseGeocode(30.657261, 104.066678)).isEmpty();
            assertThat(client.reverseGeocode(30.657261, 104.066678)).isEmpty();
            assertThat(server.hitCount())
                    .as("失败不该进缓存，否则修好 key 也必须重启才生效")
                    .isEqualTo(2);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("formatted_address 是数组或对象时返回 empty，不能落成 [] 字样")
    void nonStringAddressReturnsEmpty() throws Exception {
        // 实测踩过：高德对个别坐标把这个字段返成 []，而 String.valueOf([])
        // 会得到字面量 "[]"，直接落进 poi_name，前端显示出「地点 []」
        String body = "{\"status\":\"1\",\"info\":\"OK\","
                + "\"regeocode\":{\"formatted_address\":[]}}";
        FakeGeoServer server = new FakeGeoServer(body);
        server.start();
        try {
            GeoClient client = new GeoClient(server.baseUrl(), "k",
                    java.time.Duration.ofMillis(5_000));
            assertThat(client.reverseGeocode(30.657261, 104.066678)).isEmpty();
        } finally {
            server.stop();
        }

        String objBody = "{\"status\":\"1\",\"info\":\"OK\","
                + "\"regeocode\":{\"formatted_address\":{\"x\":1}}}";
        FakeGeoServer server2 = new FakeGeoServer(objBody);
        server2.start();
        try {
            GeoClient client = new GeoClient(server2.baseUrl(), "k",
                    java.time.Duration.ofMillis(5_000));
            assertThat(client.reverseGeocode(31.0, 121.0)).isEmpty();
        } finally {
            server2.stop();
        }
    }

    // ============================================================
    // 本地假高德服务
    // ============================================================

    /** 返回固定响应体的极简 HTTP 服务，同时记录最后的查询串与命中次数。 */
    static class FakeGeoServer {
        private final com.sun.net.httpserver.HttpServer server;
        private final String body;
        private volatile String lastQuery;
        private final java.util.concurrent.atomic.AtomicInteger hits =
                new java.util.concurrent.atomic.AtomicInteger();

        FakeGeoServer(String body) throws IOException {
            this.body = body;
            this.server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/v3/geocode/regeo", exchange -> {
                hits.incrementAndGet();
                // 必须用 getRawQuery()：getQuery() 返回的是已解码串，
                // %2C 会被还原成逗号，那样就无法验证「逗号确实被编码过」。
                // 而逗号编码恰恰是上一版踩过的坑（代理层把它当参数分隔符）。
                lastQuery = exchange.getRequestURI().getRawQuery();
                byte[] out = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            this.server.setExecutor(null);
        }

        void start() {
            server.start();
        }

        void stop() {
            server.stop(0);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int hitCount() {
            return hits.get();
        }
    }
}
