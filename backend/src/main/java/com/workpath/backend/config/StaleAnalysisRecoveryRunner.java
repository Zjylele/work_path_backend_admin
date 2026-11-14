package com.workpath.backend.config;

import com.workpath.backend.service.AnalysisTaskQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动时复位僵死的「分析中(1)」轨迹。
 *
 * <p>手动分析批次跑在事务提交后的单线程池里，进程若在分析途中被杀，
 * 轨迹会永久停在 1——前端只对 0 和 3 开放重试入口，停在 1 的轨迹
 * 管理员没有任何操作路径。这里在启动后修一次，把它们复位为失败(3)。</p>
 *
 * <p>失败不阻塞启动：这只是一次补偿性的清理，数据库连不上时记录日志即可，
 * 系统该起还是得起。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleAnalysisRecoveryRunner implements ApplicationRunner {

    private final AnalysisTaskQueue analysisTaskQueue;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int reset = analysisTaskQueue.recoverStaleRunningTracks();
            if (reset > 0) {
                log.info("启动清理：{} 条僵死的分析中轨迹已复位为失败，可在管理端重新发起", reset);
            }
        } catch (Exception e) {
            log.warn("启动清理僵死分析轨迹失败（不影响启动）：{}", e.getMessage());
        }
    }
}
