package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.TrackMapper;
import com.workpath.backend.mapper.UserMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工作事件查询服务。M4 产出的人工复核入口。
 *
 * <p>职责只有「读」：列表、详情、确认/驳回。写入侧在 TrackAnalysisService。
 * 分开是因为两者的读写频率与事务边界完全不同——AI 分析由定时任务批量驱动，
 * 复核是人一条条看的，混在一个 Service 里会让定时任务的 @Transactional
 * 覆盖到人看的查询路径上。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkEventService {

    private final WorkEventMapper workEventMapper;
    private final TrackMapper trackMapper;
    private final UserMapper userMapper;

    /**
     * 工作事件列表。
     *
     * @param confirmStatus 复核状态筛选；null 表示全部
     */
    public List<WorkEvent> list(Integer confirmStatus) {
        LambdaQueryWrapper<WorkEvent> q = new LambdaQueryWrapper<>();
        if (confirmStatus != null) {
            q.eq(WorkEvent::getConfirmStatus, confirmStatus);
        }
        q.orderByDesc(WorkEvent::getStartTime);
        List<WorkEvent> events = workEventMapper.selectList(q);

        // 员工用户名逐个查会 N+1。事件量在百级，一次批量查完再组装
        if (!events.isEmpty()) {
            List<Long> userIds = events.stream().map(WorkEvent::getUserId).distinct().toList();
            Map<Long, String> names = userMapper.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(com.workpath.backend.entity.User::getId,
                            com.workpath.backend.entity.User::getUsername, (a, b) -> a));
            events.forEach(e -> e.setUsername(names.get(e.getUserId())));
        }
        return events;
    }

    /** 事件详情。 */
    public WorkEvent detail(Long eventId) {
        WorkEvent event = workEventMapper.selectById(eventId);
        if (event == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "工作事件不存在");
        }
        return event;
    }

    /**
     * 人工确认某条事件。
     *
     * <p>这是「AI 结论 → 管理依据」那一步的开关。confirm_status 从 0 变 1
     * 完全由人触发，服务端记录复核人与时间——后续 M5 的违规判定只认
     * confirm_status=1 的事件。</p>
     */
    public void confirm(Long eventId) {
        WorkEvent event = workEventMapper.selectById(eventId);
        if (event == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "工作事件不存在");
        }
        // 幂等：复核过的可重复确认（结果相同），但复核人与时间记录最后一次
        WorkEvent patch = new WorkEvent();
        patch.setEventId(eventId);
        patch.setConfirmStatus(1);
        patch.setConfirmBy(UserContext.getUserId());
        patch.setConfirmTime(java.time.LocalDateTime.now());
        workEventMapper.updateById(patch);

        log.info("工作事件已确认 eventId={} 原状态={} 复核人={}",
                eventId, event.getConfirmStatus(), UserContext.getUserId());
    }

    /**
     * 驳回某条事件。
     *
     * <p>与确认的差别只在状态值：2 表示"AI 判断有误"。驳回的事件同样保留——
     * 它们是调整聚类参数与标注 prompt 的依据。</p>
     */
    public void reject(Long eventId) {
        WorkEvent event = workEventMapper.selectById(eventId);
        if (event == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "工作事件不存在");
        }
        WorkEvent patch = new WorkEvent();
        patch.setEventId(eventId);
        patch.setConfirmStatus(2);
        patch.setConfirmBy(UserContext.getUserId());
        patch.setConfirmTime(java.time.LocalDateTime.now());
        workEventMapper.updateById(patch);
        log.info("工作事件已驳回 eventId={} 原状态={} 复核人={}",
                eventId, event.getConfirmStatus(), UserContext.getUserId());
    }

    /**
     * 触发某条轨迹重新分析。
     *
     * <p>把 analysis_status 置回 0（未分析），下一轮定时任务（30s）自然捞起它。
     * 不在这里同步调 analyze——那会让 HTTP 请求等一个可能几十秒的分析，
     * 违背"定时捞 + 异步执行"的初衷。</p>
     */
    public void reanalyze(Long trackId) {
        Track track = trackMapper.selectById(trackId);
        if (track == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "轨迹不存在");
        }
        Track patch = new Track();
        patch.setId(trackId);
        // 只有这两个字段非空，updateById 只会更新 analysis_status
        patch.setAnalysisStatus(0);
        trackMapper.updateById(patch);
        log.info("已提交重新分析 trackId={}，将在下一轮定时任务中执行", trackId);
    }
}
