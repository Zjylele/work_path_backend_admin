package com.workpath.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步执行器配置。
 *
 * <p>专供轨迹分析使用（AnalysisTaskQueue 派发）。参数取保守值的原因：
 * 分析是 CPU 密集（DBSCAN 是 O(n²) 的密度聚类）叠加外部 HTTP（POI 逆地理编码），
 * 并发过高既拖慢数据库也容易触发高德 QPS 限制。</p>
 *
 * <p>拒绝策略用 CallerRunsPolicy：队列满时由调用线程（即定时任务线程）自己执行，
 * 相当于自动降速。若用 AbortPolicy 直接抛异常，任务会丢失且只出现在日志里。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 核心线程数。与机器的可用核数匹配，避免过度订阅 */
    private static final int CORE_POOL_SIZE = 2;

    /** 最大线程数。队列满后才扩容到这个数 */
    private static final int MAX_POOL_SIZE = 4;

    /** 队列容量。超过这个数才触发扩容，再超过才走拒绝策略 */
    private static final int QUEUE_CAPACITY = 100;

    @Bean("analysisExecutor")
    public Executor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("track-analysis-");
        // 优雅停机：容器关闭时等已提交任务跑完，别把分析到一半的事务硬切
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
