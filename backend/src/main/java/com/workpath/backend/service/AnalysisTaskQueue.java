package com.workpath.backend.service;

import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.Track;
import com.workpath.backend.mapper.TrackMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 管理员手动发起的轨迹分析任务。 */
@Slf4j
@Component
public class AnalysisTaskQueue {

    private static final int MAX_BATCH_SIZE = 10;

    /** 僵死「分析中」的判定阈值（分钟）。单批 10 条串行实测 1-3 分钟，留十倍余量。 */
    static final int STALE_RUNNING_MINUTES = 30;

    private final TrackMapper trackMapper;
    private final TrackAnalysisService analysisService;
    private final TaskExecutor executor;

    public AnalysisTaskQueue(TrackMapper trackMapper,
                             TrackAnalysisService analysisService,
                             @Qualifier("manualTrackAnalysisExecutor") TaskExecutor executor) {
        this.trackMapper = trackMapper;
        this.analysisService = analysisService;
        this.executor = executor;
    }

    /** 先原子占用所选轨迹，再在事务提交后启动批次。 */
    @Transactional
    public void startManualBatch(List<Long> trackIds) {
        if (trackIds == null || trackIds.isEmpty() || trackIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "每批请选择 1 到 10 条轨迹");
        }
        Set<Long> uniqueIds = new HashSet<>(trackIds);
        if (uniqueIds.size() != trackIds.size() || uniqueIds.contains(null)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "轨迹编号重复或为空");
        }

        List<Long> claimedIds = new ArrayList<>(trackIds.size());
        for (Long id : trackIds) {
            Track track = trackMapper.selectById(id);
            if (track == null) {
                throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "轨迹不存在：" + id);
            }
            if (track.getAnalysisStatus() == null
                    || (track.getAnalysisStatus() != 0 && track.getAnalysisStatus() != 3)) {
                throw new BusinessException(ResultCode.CONFLICT.getCode(),
                        "轨迹 " + id + " 当前状态不可发起分析");
            }
            if (trackMapper.claimForManualAnalysis(id) != 1) {
                throw new BusinessException(ResultCode.CONFLICT.getCode(),
                        "轨迹 " + id + " 已被其他操作启动，请刷新后重试");
            }
            claimedIds.add(id);
        }

        Runnable start = () -> executor.execute(() -> processBatch(claimedIds));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    start.run();
                }
            });
        } else {
            start.run();
        }
    }

    private void processBatch(List<Long> trackIds) {
        for (Long trackId : trackIds) {
            Track track = trackMapper.selectById(trackId);
            if (track == null) {
                log.warn("手动分析时轨迹已不存在 trackId={}", trackId);
                continue;
            }
            try {
                analysisService.analyze(track);
                markStatus(trackId, 2);
            } catch (Exception e) {
                log.warn("手动分析失败 trackId={} : {}", trackId, e.getMessage());
                markStatus(trackId, 3);
            }
        }
    }

    private void markStatus(Long trackId, int status) {
        Track patch = new Track();
        patch.setId(trackId);
        patch.setAnalysisStatus(status);
        trackMapper.updateById(patch);
    }

    /**
     * 僵死的「分析中(1)」自救：把超过阈值还没跑完的轨迹复位为失败(3)。
     *
     * <p><b>为什么需要它</b>：批次在事务提交后由单线程池执行，
     * {@code processBatch} 只对<b>抛异常</b>的情况置 3。如果进程在分析途中
     * 被杀、机器断电、或线程池任务还没跑完服务就重启，轨迹会永久停在 1。
     * 而前端只对 0 和 3 显示复选框、提供重试入口——停在 1 的轨迹
     * 既不能被重新发起，也读不到事件（本来就没产出），管理员没有任何操作路径，
     * 只能看着它永远挂在「进行中」页签里。</p>
     *
     * <p>阈值取 30 分钟：单批最多 10 条、串行执行，每条含 POI 与模型调用，
     * 实测一轮通常 1-3 分钟；30 分钟留了十倍余量，正常批次绝不可能被误伤。</p>
     *
     * <p>由应用启动钩子调用一次（见 {@code StaleAnalysisRecoveryRunner}）。
     * 不做定时轮询：那需要额外引入调度，而这个问题在启动时修一次就够。</p>
     *
     * @return 被复位的轨迹条数
     */
    public int recoverStaleRunningTracks() {
        int reset = trackMapper.resetStaleRunningTracks(
                TrackAnalysisService.STATUS_FAILED, STALE_RUNNING_MINUTES);
        if (reset > 0) {
            log.warn("复位僵死的分析中轨迹 {} 条：超过 {} 分钟未完成，已置为失败(3)可重新发起",
                    reset, STALE_RUNNING_MINUTES);
        }
        return reset;
    }
}
