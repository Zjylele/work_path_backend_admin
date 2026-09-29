package com.workpath.backend.service;

import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 轨迹分析服务。M4 的核心：把轨迹点序列变成「工作事件」。
 *
 * <p>W3 只搭骨架与状态语义，DBSCAN 停留点识别与 POI 解析在 W4 补。
 * 之所以先立骨架，是因为第2章 2.3.4 要求的「未分析→分析中→已分析」
 * 状态流转本身就是可验收项，且它能验证「上传接口不被分析拖慢」
 * 这个架构目标——后者的价值不依赖分析算法是否完善。</p>
 *
 * <p>幂等约定：同一条轨迹重复分析会先删除旧事件再写新的，
 * 因此 {@link #analyze} 可安全重入（W3 失败重试依赖这一点）。</p>
 */
@Slf4j
@Service
public class TrackAnalysisService {

    /** 未分析。上传接口写入的初值 */
    public static final int STATUS_PENDING = 0;

    /** 分析中。进入分析前置，防止定时任务重复捞同一条 */
    public static final int STATUS_RUNNING = 1;

    /** 已完成 */
    public static final int STATUS_DONE = 2;

    /** 分析失败。与 RUNNING 分开是关键：停在 1 会让该轨迹永远不再被捞起 */
    public static final int STATUS_FAILED = 3;

    private final TrackPointMapper trackPointMapper;
    private final WorkEventMapper workEventMapper;

    public TrackAnalysisService(TrackPointMapper trackPointMapper,
                                WorkEventMapper workEventMapper) {
        this.trackPointMapper = trackPointMapper;
        this.workEventMapper = workEventMapper;
    }

    /**
     * 分析一条轨迹，产出工作事件。
     *
     * <p>W3 的行为：校验点数下限 → 清掉旧事件（保证重入幂等）→ 写一条概览事件。
     * 真正的停留点识别在 W4 替换本方法体，届时此处的位置、参数与异常语义不变。</p>
     *
     * @throws BusinessException 点数不足等确定性失败，由调用方置 FAILED 并记录
     */
    @Transactional
    public void analyze(Track track) {
        if (track == null || track.getId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "轨迹不存在");
        }
        List<com.workpath.backend.entity.TrackPoint> points =
                trackPointMapper.selectByTrackId(track.getId());
        // 少于两点连一条线段都构不成：此时不该产出任何事件，明确失败
        if (points.size() < 2) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "轨迹点数不足（仅 " + points.size() + " 个），无法分析");
        }

        // 幂等：重跑同一条轨迹时先清旧结论，否则会累积出重复事件
        workEventMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkEvent>()
                        .eq(WorkEvent::getTrackId, track.getId()));

        // W3 骨架：只落一条概览事件，证明链路通了。
        // W4 起这里会替换为 DBSCAN + POI + 语义标注的结果
        WorkEvent event = new WorkEvent();
        event.setTrackId(track.getId());
        event.setUserId(track.getUserId());
        event.setEventType("SUMMARY");
        event.setAiReason("轨迹共 " + points.size() + " 个轨迹点（W3 骨架）");
        event.setStartTime(points.get(0).getRecordTime());
        event.setEndTime(points.get(points.size() - 1).getRecordTime());
        // AI 结论一律是「待复核」，不由前端指定——否则调用方可把未复核结果标成已确认
        event.setConfirmStatus(0);
        workEventMapper.insert(event);

        log.info("轨迹分析完成 trackId={} 点数={} 事件数=1（W3 骨架）",
                track.getId(), points.size());
    }
}
