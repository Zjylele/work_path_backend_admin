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
}
