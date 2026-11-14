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
 * 分开是因为两者的读写频率与事务边界完全不同——AI 分析由手动批次驱动，
 * 复核是人一条条看的，混在一个 Service 里会让批量的 @Transactional
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
    private final AnalysisTaskQueue analysisTaskQueue;

    public PageVO<Map<String, Object>> listTracks(Integer status, Integer page, Integer pageSize) {
        LambdaQueryWrapper<Track> query = new LambdaQueryWrapper<>();
        query.inSql(Track::getId, "SELECT DISTINCT track_id FROM work_event"
                + (status == null ? "" : " WHERE confirm_status = " + status));
        query.orderByDesc(Track::getStartTime).orderByDesc(Track::getId);
        Page<Track> tracks = trackMapper.selectPage(new Page<>(normalize(page, 1),
                Math.min(normalize(pageSize, 10), 50)), query);
        List<Long> ids = tracks.getRecords().stream().map(Track::getId).toList();
        Map<Long, List<WorkEvent>> grouped = ids.isEmpty() ? Map.of() : workEventMapper.selectList(
                new LambdaQueryWrapper<WorkEvent>().in(WorkEvent::getTrackId, ids)
                        .orderByAsc(WorkEvent::getStartTime)).stream().collect(Collectors.groupingBy(WorkEvent::getTrackId));
        List<Long> users = tracks.getRecords().stream().map(Track::getUserId).distinct().toList();
        Map<Long, String> names = users.isEmpty() ? Map.of() : userMapper.selectBatchIds(users).stream()
                .collect(Collectors.toMap(com.workpath.backend.entity.User::getId,
                        com.workpath.backend.entity.User::getUsername, (a, b) -> a));
        Page<Map<String, Object>> result = new Page<>(tracks.getCurrent(), tracks.getSize(), tracks.getTotal());
        result.setRecords(tracks.getRecords().stream().map(track -> {
            Map<String, Object> row = new HashMap<>();
            List<WorkEvent> events = grouped.getOrDefault(track.getId(), List.of());
            row.put("trackId", track.getId()); row.put("name", track.getName());
            row.put("username", names.get(track.getUserId()));
            row.put("startTime", track.getStartTime()); row.put("endTime", track.getEndTime());
            row.put("eventCount", events.size());
            for (int s = 0; s < 3; s++) {
                final int value = s;
                row.put(new String[]{"pending", "approved", "rejected"}[s],
                        events.stream().filter(e -> Integer.valueOf(value).equals(e.getConfirmStatus())).count());
            }
            row.put("labels", events.stream().collect(Collectors.groupingBy(
                    e -> e.getSemanticLabel() == null ? "未标注" : e.getSemanticLabel(), Collectors.counting())));
            return row;
        }).toList());
        return PageVO.of(result);
    }

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
     * 某条轨迹的全部工作事件，按开始时间升序。
     *
     * <p>供管理端「分析详情」页使用：那一页要把轨迹线、停留点、事件时序放在同一屏里，
     * 分页列表接口（按 confirmStatus 全局筛选）满足不了「只取这一条轨迹」的需求。</p>
     *
     * <p>不分页：一条轨迹通常只产出 1-3 个停留事件，分页只会徒增前端的状态管理。</p>
     */
    public List<WorkEvent> listByTrack(Long trackId) {
        return workEventMapper.selectList(new LambdaQueryWrapper<WorkEvent>()
                .eq(WorkEvent::getTrackId, trackId)
                .orderByAsc(WorkEvent::getStartTime));
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

    /**
     * 撤销确认：已确认 → 待确认。
     *
     * <p>补上「误点确认」的正路。此前把 1→2（驳回）堵死之后，误确认只剩改库
     * 一条路可退——那是把问题从「明确报错」变成「静默绕过系统」。</p>
     *
     * <p><b>前置条件是工单</b>：确认会触发合规核查并可能生成整改工单，而工单的
     * content 来自核查结论、结论挂在事件上。所以只要存在未闭环工单，撤销就必须
     * 拒绝——否则那张工单会指向一条「未确认」的事件。已闭环的工单不影响，
     * 它是走完流程的历史记录。</p>
     *
     * <p>核查结论本身不删除：它记录了「AI 曾经这么判过」，是复核历史的组成部分。
     * 撤销只把事件退回待确认，让管理员重新判断。</p>
     */
    public void revoke(Long eventId) {
        WorkEvent event = workEventMapper.selectById(eventId);
        if (event == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "工作事件不存在");
        }
        if (!isConfirmStatus(event, CONFIRM_APPROVED)) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(),
                    "只有已确认的事件可以撤销（当前状态 " + event.getConfirmStatus() + "）");
        }
        if (complianceService.hasOpenOrderForEvent(eventId)) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(),
                    "该事件已产生未闭环的整改工单，撤销会让工单失去依据；请先处理工单");
        }

        WorkEvent patch = new WorkEvent();
        patch.setEventId(eventId);
        patch.setConfirmStatus(CONFIRM_PENDING);
        // confirm_by / confirm_time 不清空：它们是审计痕迹，展示层在待确认状态下
        // 不渲染即可。（另：MyBatis-Plus 的 updateById 对 null 字段默认跳过更新，
        // 想真清空得换 LambdaUpdateWrapper——这里本就不需要清）
        workEventMapper.updateById(patch);
        log.info("工作事件已撤销确认 eventId={} 操作人={}", eventId, UserContext.getUserId());
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
     * <p>直接投递到手动分析批次，<b>不再置回 0 等定时任务</b>——定时捞取入口
     * 在手动分析工作流上线时就移除了，全仓没有任何代码再查 {@code analysis_status=0}
     * 的轨迹，置回 0 的轨迹会一直挂在「待分析」再也跑不起来。
     * 批次自己会按 0/3 → 1 → 2/3 推进状态，这里不重复写状态。</p>
     *
     * <p>不在 HTTP 请求里同步调 analyze：那会让请求等一个可能几十秒的分析。
     * 批次跑在事务提交后的线程池里，响应仍然立即返回。</p>
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

        // 走手动批次：它内部会校验状态可发起（0/3）并用条件更新原子占用，
        // 重复点击会被"已被其他操作启动"挡住，不会并发跑两次分析。
        analysisTaskQueue.startManualBatch(List.of(trackId));
        log.info("已提交重新分析 trackId={}，交由手动分析批次执行", trackId);
    }

    /** selectCount 的判空包装：MyBatis-Plus 正常返回 0，mock 环境可能给 null。 */
    private static long count(Long value) {
        return value == null ? 0 : value;
    }
}
