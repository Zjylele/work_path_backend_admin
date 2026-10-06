package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.TrackMapper;
import com.workpath.backend.mapper.UserMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import com.workpath.backend.vo.PageVO;
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

    /** 复核状态：待确认。由分析侧写入，不由前端指定 */
    public static final int CONFIRM_PENDING = 0;

    /** 复核状态：已确认。确认后 AI 结论才成为管理依据，并触发合规核查 */
    public static final int CONFIRM_APPROVED = 1;

    /** 复核状态：已驳回。留档用于优化分析参数与标注 prompt，不参与核查 */
    public static final int CONFIRM_REJECTED = 2;

    private final WorkEventMapper workEventMapper;
    private final TrackMapper trackMapper;
    private final UserMapper userMapper;
    private final ComplianceService complianceService;

    /**
     * 确认动作的结果。
     *
     * @param checkFailed 合规核查是否执行失败。确认动作本身已成功落库、不因核查失败回滚；
     *                    这个标记让调用方能把「已确认，但核查没跑成」如实告诉管理员——
     *                    此前核查异常只写日志，前端一律提示"已确认"，核查静默失败无从察觉
     * @param newChecks   本次新增的核查结论条数；0 表示未命中任何规则
     */
    public record ConfirmResult(boolean checkFailed, int newChecks) {

        static ConfirmResult of(int newChecks) {
            return new ConfirmResult(false, newChecks);
        }

        static ConfirmResult failed() {
            return new ConfirmResult(true, 0);
        }
    }

    /**
     * 工作事件分页列表。
     *
     * <p>事件量随轨迹持续增长（每条轨迹产出 1-3 个事件），一次性全量返回
     * 在演示库尚可、在真实数据规模下不可持续，分页是表格展示的常规配置。</p>
     *
     * @param confirmStatus 复核状态筛选；null 表示全部
     */
    public PageVO<WorkEvent> list(Integer confirmStatus, Integer page, Integer pageSize) {
        LambdaQueryWrapper<WorkEvent> q = new LambdaQueryWrapper<>();
        if (confirmStatus != null) {
            q.eq(WorkEvent::getConfirmStatus, confirmStatus);
        }
        q.orderByDesc(WorkEvent::getStartTime);
        Page<WorkEvent> result = workEventMapper.selectPage(
                new Page<>(normalize(page, 1), normalize(pageSize, 10)), q);

        // 员工用户名逐个查会 N+1。每页至多几十条，一次批量查完再组装
        List<WorkEvent> events = result.getRecords();
        if (!events.isEmpty()) {
            List<Long> userIds = events.stream().map(WorkEvent::getUserId).distinct().toList();
            Map<Long, String> names = userMapper.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(com.workpath.backend.entity.User::getId,
                            com.workpath.backend.entity.User::getUsername, (a, b) -> a));
            events.forEach(e -> e.setUsername(names.get(e.getUserId())));
        }
        return PageVO.of(result);
    }

    /** 分页参数兜底：null 或非正数回落默认值，防止 Page 构造抛异常。 */
    private static long normalize(Integer value, int fallback) {
        return (value == null || value <= 0) ? fallback : value;
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
     * confirm_status=1 的事件。确认成功即触发合规核查。</p>
     *
     * <p><b>状态不可回归</b>：已驳回（2）的事件不能翻回已确认——驳回是人工
     * 给出「AI 判断有误」的结论，覆盖它等于把那条判断无声抹掉。需要重新
     * 判定时应走重新分析，让 AI 重跑一遍再复核。</p>
     *
     * @return 核查执行情况，供调用方给管理员可见反馈
     */
    public ConfirmResult confirm(Long eventId) {
        WorkEvent event = workEventMapper.selectById(eventId);
        if (event == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "工作事件不存在");
        }
        if (isConfirmStatus(event, CONFIRM_REJECTED)) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(),
                    "该事件已驳回，不能再次确认；如需重新判定请重新分析来源轨迹");
        }

        // 幂等：复核过的可重复确认（结果相同），但复核人与时间记录最后一次
        WorkEvent patch = new WorkEvent();
        patch.setEventId(eventId);
        patch.setConfirmStatus(CONFIRM_APPROVED);
        patch.setConfirmBy(UserContext.getUserId());
        patch.setConfirmTime(java.time.LocalDateTime.now());
        workEventMapper.updateById(patch);

        // M5 入口：确认即触发合规核查（联调链「管理员确认 → 规则命中」）。
        // 核查链路（含 LLM）的失败不能让确认请求失败——确认动作本身已成功，
        // 核查问题留给 result=2 的待复核结论。但失败必须如实回传：只写日志
        // 会让前端照旧提示「已确认」，核查静默失效无从察觉。
        ConfirmResult result;
        try {
            result = ConfirmResult.of(complianceService.onEventConfirmed(event));
        } catch (Exception e) {
            log.error("合规核查失败 eventId={} : {}", eventId, e.getMessage(), e);
            result = ConfirmResult.failed();
        }

        log.info("工作事件已确认 eventId={} 原状态={} 复核人={} 新增核查结论={}",
                eventId, event.getConfirmStatus(), UserContext.getUserId(), result.newChecks());
        return result;
    }

    /** 复核状态比对：confirmStatus 为 null（历史脏数据）时一律按不匹配处理。 */
    private static boolean isConfirmStatus(WorkEvent event, int status) {
        return event.getConfirmStatus() != null && event.getConfirmStatus() == status;
    }

    /**
     * 驳回某条事件。
     *
     * <p>2 表示"AI 判断有误"。驳回的事件同样保留——它们是调整聚类参数与
     * 标注 prompt 的依据。</p>
     *
     * <p><b>状态不可回归</b>：已确认（1）的事件不能驳回。确认已经触发过
     * 合规核查、并可能生成工单，把状态改成已驳回会让那些结论失去依据
     * （compliance_check.work_event_id 指向一条「被驳回」的事件）。</p>
     */
    public void reject(Long eventId) {
        WorkEvent event = workEventMapper.selectById(eventId);
        if (event == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "工作事件不存在");
        }
        if (isConfirmStatus(event, CONFIRM_APPROVED)) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(),
                    "该事件已确认并已触发合规核查，不能驳回；如需重新判定请重新分析来源轨迹");
        }
        WorkEvent patch = new WorkEvent();
        patch.setEventId(eventId);
        patch.setConfirmStatus(CONFIRM_REJECTED);
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
     *
     * <p><b>已确认事件是红线</b>：analyze 会先删除该轨迹的全部事件再重建
     * （TrackAnalysisService.deleteExistingEvents），已确认事件被删后，它触发过的
     * compliance_check.work_event_id 会指向不存在的行、工单失去证据来源。
     * 因此这里 fail-closed：存在已确认事件就不放行。</p>
     */
    public void reanalyze(Long trackId) {
        Track track = trackMapper.selectById(trackId);
        if (track == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "轨迹不存在");
        }
        long confirmed = count(workEventMapper.selectCount(new LambdaQueryWrapper<WorkEvent>()
                .eq(WorkEvent::getTrackId, trackId)
                .eq(WorkEvent::getConfirmStatus, CONFIRM_APPROVED)));
        if (confirmed > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(),
                    "该轨迹存在 " + confirmed + " 条已确认事件，重新分析会删除这些确认记录"
                            + "并使其核查结论与工单失去依据，已阻止");
        }

        Track patch = new Track();
        patch.setId(trackId);
        // 只设 id 与 analysisStatus 两个字段，updateById 便只会更新 analysis_status
        patch.setAnalysisStatus(0);
        trackMapper.updateById(patch);
        log.info("已提交重新分析 trackId={}，将在下一轮定时任务中执行", trackId);
    }

    /** selectCount 的判空包装：MyBatis-Plus 正常返回 0，mock 环境可能给 null。 */
    private static long count(Long value) {
        return value == null ? 0 : value;
    }
}
