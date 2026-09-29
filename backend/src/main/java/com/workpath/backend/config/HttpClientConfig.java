package com.workpath.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * 通用 HTTP 客户端配置。
 *
 * <p>EmbeddingClient 走构造注入 HttpClient，方便测试里替换；生产要一个复用连接池的实例。
 * 这里集中提供一个单例：Java 的 HttpClient 内部已管理连接池与线程，
 * 每次 new 都会重建连接池，模型调用并发时反而更慢。</p>
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder().build();
    }
}
