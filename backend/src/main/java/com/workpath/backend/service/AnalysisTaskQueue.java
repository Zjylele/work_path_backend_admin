package com.workpath.backend.service;

import com.workpath.backend.entity.Track;
import com.workpath.backend.mapper.TrackMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 轨迹分析定时任务。
 *
 * <p>为什么用「定时捞 + 串行执行」而不是上传时直接 {@code @Async}：
 * 后者在方法调用点就触发，上传高峰期会堆线程；而先落库再定时捞，
 * 上传接口永远快速返回，分析积压也能从 {@code analysis_status} 直接看出来。
 * 第5章演示时能清晰展示「未分析→分析中→已分析」的流转。</p>
 *
 * <p><b>串行是有意的，不是遗漏。</b>每轮按序处理，保证同一条轨迹上
 * 「置 RUNNING → 分析 → 置 DONE/FAILED」不会交错。若改成并发投递到线程池，
 * 两个轮次之间可能对同一条轨迹重复分析，而 analyze 的幂等清理
 * （先删旧事件再重建）会把另一轮刚写入的成果删掉。单批 10 条配 fixedDelay
 * 不会积压，线程池的吞吐收益抵不上这个风险。</p>
 *
 * <p>状态推进顺序是这个类最关键的设计：</p>
 * <ol>
 *   <li>捞到轨迹后<b>先</b>把状态置 RUNNING(1)，<b>再</b>执行分析。</li>
 *   <li>顺序反了会出事：若先分析后置 1，两个轮次之间重叠执行时同一轨迹
 *      会被重复分析，而幂等清理又可能把另一轮的成果删掉。</li>
 *   <li>分析失败置 FAILED(3) 而不是留在 RUNNING——留在 1 会让
 *      {@code WHERE analysis_status = 0} 再也捞不到它，该轨迹彻底失联。</li>
 * </ol>
 */
@Slf4j
@Component
public class AnalysisTaskQueue {

    /** 单轮最多分析的轨迹数。线程池只有 2-4 个，捞太多只会在队列里排队 */
    private static final int BATCH_SIZE = 10;

    private final TrackMapper trackMapper;
    private final TrackAnalysisService analysisService;

    public AnalysisTaskQueue(TrackMapper trackMapper, TrackAnalysisService analysisService) {
        this.trackMapper = trackMapper;
        this.analysisService = analysisService;
    }

    /**
     * 每 30 秒捞一批未分析轨迹。
     *
     * <p>固定用 fixedDelay 而不是 fixedRate：analysis 是耗时操作，
     * fixedRate 会在上一轮没跑完时再触发一次，形成积压叠加。
     * fixedDelay 保证两轮之间至少间隔一个分析周期。</p>
     */
    @Scheduled(fixedDelay = 30000)
    public void dispatch() {
        List<Track> pending = trackMapper.selectPendingAnalysis(BATCH_SIZE);
        if (pending.isEmpty()) {
            return;
        }
        log.debug("本轮捞到 {} 条待分析轨迹", pending.size());

        for (Track track : pending) {
            // 1. 先把状态推到 RUNNING，让后续轮次不再捞它
            markStatus(track.getId(), TrackAnalysisService.STATUS_RUNNING);
            try {
                // 2. 真正分析。@Transactional 在 analyze 内部，
                //    这里再包一层事务没有意义反而放大锁范围
                analysisService.analyze(track);
                // 3. 成功
                markStatus(track.getId(), TrackAnalysisService.STATUS_DONE);
            } catch (Exception e) {
                // 4. 失败置 FAILED，保证这条不会被永久遗忘。
                //    重新分析走管理端手动触发或删了重传
                log.warn("轨迹分析失败 trackId={} : {}", track.getId(), e.getMessage());
                markStatus(track.getId(), TrackAnalysisService.STATUS_FAILED);
            }
        }
    }

    /**
     * 只更新 analysis_status 一列。
     *
     * <p>用一个只填了 id 与 analysisStatus 的空 Track 去做 updateById，
     * 而不是把整个实体回写——整个实体会把 status、distance 等字段一并更新，
     * 覆盖掉分析期间别人对这些字段的修改。</p>
     */
    private void markStatus(Long trackId, int status) {
        Track patch = new Track();
        patch.setId(trackId);
        patch.setAnalysisStatus(status);
        int rows = trackMapper.updateById(patch);
        if (rows == 0) {
            // 轨迹被删了。此时不该继续分析它，也不该让外层 catch 把它标成 FAILED
            log.warn("更新分析状态未命中 trackId={} status={}，轨迹可能已被删除", trackId, status);
        }
    }
}
