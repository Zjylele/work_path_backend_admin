package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.ComplianceCheck;
import com.workpath.backend.entity.ComplianceRule;
import com.workpath.backend.entity.PolicyClause;
import com.workpath.backend.entity.RectificationOrder;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.ComplianceCheckMapper;
import com.workpath.backend.mapper.ComplianceRuleMapper;
import com.workpath.backend.mapper.PolicyClauseMapper;
import com.workpath.backend.mapper.RectificationOrderMapper;
import com.workpath.backend.mapper.TrackMapper;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import com.workpath.backend.service.compliance.RuleEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * M5 合规核查编排：规则命中（程序）→ LLM 说明与引用（模型）→ 条款回查（程序）
 * → 结论入库 → 疑似违规自动生成工单。
 *
 * <p>条款回查的位置是全系统最关键的一步（实施指南 W6）：必须发生在
 * <b>模型输出之后、结论入库之前</b>。前置回查让无效引用根本没机会进入
 * 工单流程，失败处理只需「转人工复核」（result=2），不需要状态回滚——
 * 后置校验则要处理「已入库结论的撤回」，状态机复杂度翻倍。</p>
 *
 * <p>触发点只有事件人工确认（confirm_status 0→1）：M5 只认已确认事件，
 * 这是「AI 结论必须经人确认才成为管理依据」定位的延续。ABNORMAL 是轨迹级
 * 异常（无停留事件可确认），走管理端手动扫描。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceService {

    /** ABNORMAL 扫描单轮处理的轨迹上限，防止首次扫描拖太久 */
    private static final int SCAN_LIMIT = 200;

    private final ComplianceRuleMapper ruleMapper;
    private final ComplianceCheckMapper checkMapper;
    private final RectificationOrderMapper orderMapper;
    private final WorkEventMapper workEventMapper;
    private final TrackMapper trackMapper;
    private final TrackPointMapper trackPointMapper;
    private final PolicyClauseMapper clauseMapper;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private final RuleEngine ruleEngine = new RuleEngine();

    /**
     * 事件确认后的合规核查入口。由 WorkEventService.confirm() 调用。
     *
     * <p>幂等：事件级规则按 (rule, work_event) 去重，FREQUENCY 按
     * (rule, user, work_date) 去重——重复确认同一条事件不会产生重复结论。</p>
     */
    public void onEventConfirmed(WorkEvent event) {
        List<ComplianceRule> rules = enabledRules();
        if (rules.isEmpty() || event.getStartTime() == null) {
            return;
        }
        LocalDate workDate = event.getStartTime().toLocalDate();
        List<WorkEvent> dayStays = confirmedStaysOfDay(event.getUserId(), workDate);

        for (ComplianceRule rule : rules) {
            if (alreadyChecked(rule, event, workDate)) {
                continue;
            }
            ruleEngine.evaluate(event, rule, dayStays).ifPresent(evidence -> {
                log.info("规则命中 ruleCode={} eventId={} : {}", rule.getRuleCode(),
                        event.getEventId(), evidence);
                checkAndStore(event, rule, evidence);
            });
        }
    }

    /**
     * ABNORMAL 轨迹扫描：已分析完成但没有任何停留的轨迹。
     *
     * <p>异常类结论不带条款引用（「无停留」没有明确的条款锚点可让模型引用），
     * 一律 result=2 转人工复核、不自动生成工单——fail-closed，由管理员
     * 复核后决定是否下发工单。</p>
     *
     * @return 新增结论条数
     */
    @Transactional
    public int scanAbnormalTracks() {
        List<ComplianceRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<ComplianceRule>()
                .eq(ComplianceRule::getEnabled, 1)
                .eq(ComplianceRule::getRuleType, "ABNORMAL"));
        if (rules.isEmpty()) {
            return 0;
        }

        List<Track> tracks = trackMapper.selectList(new LambdaQueryWrapper<Track>()
                .eq(Track::getAnalysisStatus, 2)
                .orderByDesc(Track::getId)
                .last("LIMIT " + SCAN_LIMIT));

        int created = 0;
        for (Track track : tracks) {
            if (track.getStartTime() == null) {
                continue;
            }
            Long stayCount = workEventMapper.selectCount(new LambdaQueryWrapper<WorkEvent>()
                    .eq(WorkEvent::getTrackId, track.getId())
                    .eq(WorkEvent::getEventType, "STAY"));
            if (stayCount != null && stayCount > 0) {
                continue;
            }
            Long pointCount = trackPointMapper.selectCount(
                    new LambdaQueryWrapper<com.workpath.backend.entity.TrackPoint>()
                            .eq(com.workpath.backend.entity.TrackPoint::getTrackId, track.getId()));
            LocalDate workDate = track.getStartTime().toLocalDate();

            for (ComplianceRule rule : rules) {
                if (existsCheck(rule.getRuleId(), track.getUserId(), workDate, null)) {
                    continue;
                }
                Optional<String> hit = ruleEngine.evaluateAbnormal(
                        track, stayCount == null ? 0 : stayCount, pointCount == null ? 0 : pointCount);
                if (hit.isEmpty()) {
                    continue;
                }
                ComplianceCheck check = new ComplianceCheck();
                check.setUserId(track.getUserId());
                check.setWorkDate(workDate);
                check.setRuleId(rule.getRuleId());
                // 异常类无条款引用，一律转人工复核
                check.setResult(2);
                check.setClauseVerified(0);
                check.setEvidence(hit.get());
                checkMapper.insert(check);
                created++;
                log.info("ABNORMAL 命中 ruleCode={} trackId={} : {}",
                        rule.getRuleCode(), track.getId(), hit.get());
            }
        }
        return created;
    }

    /**
     * 人工复核 result=2 的结论后手动下发工单（ABNORMAL、引用无效待复核
     * 这两类不会自动生成工单，管理员看完证据认为属实才下发）。
     */
    @Transactional
    public Long createOrderFromCheck(Long checkId) {
        ComplianceCheck check = checkMapper.selectById(checkId);
        if (check == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "核查结论不存在");
        }
        if (existsOrder(checkId)) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "该结论已生成过工单");
        }
        return insertOrder(check, UserContext.getUserId());
    }

    private void checkAndStore(WorkEvent event, ComplianceRule rule, String evidence) {
        // 第 2 步：命中后调 LLM 生成说明 + 引用条款编号（强制 JSON）
        AiJudgment judgment = judge(event, rule, evidence);

        // 第 3 步（关键）：回查引用的条款编号是否真实存在于规则关联的文档。
        // 模型给出的编号语法上挑不出毛病，但未必真存在于制度库——这就是回查的存在理由。
        String cited = judgment == null ? null : normalizeCode(judgment.clauseCode());
        boolean verified = cited != null && citationExists(rule, cited);

        // 第 4 步：回查不通过 → result=2 转人工复核（结论仍入库，但无工单）
        ComplianceCheck check = new ComplianceCheck();
        check.setUserId(event.getUserId());
        check.setWorkDate(event.getStartTime().toLocalDate());
        check.setWorkEventId(event.getEventId());
        check.setRuleId(rule.getRuleId());
        check.setResult(verified ? 1 : 2);
        check.setEvidence(evidence);
        check.setAiReason(judgment == null ? null : judgment.reason());
        check.setCitedClauseCode(cited);
        check.setClauseVerified(verified ? 1 : 0);
        checkMapper.insert(check);

        if (verified) {
            insertOrder(check, UserContext.getUserId());
        }
    }

    /**
     * LLM 判定。模型不可用、输出非 JSON 时返回 null——此时结论落库为
     * result=2（无引用即引用无效），宁可多一件人工复核，不可少一条证据。
     */
    private AiJudgment judge(WorkEvent event, ComplianceRule rule, String evidence) {
        String clauseText = linkedClauseText(rule);
        String system = "你是外勤管理系统的合规审核助手。只输出一个 JSON 对象，"
                + "格式：{\"reason\":\"一句话说明违规性质与关键数值\","
                + "\"clause_code\":\"对应的制度条款编号（如 3.2），无法确定则为空字符串\"}。"
                + "不要输出 JSON 以外的任何字符。";
        String user = "规则：" + rule.getRuleName()
                + "（类型 " + rule.getRuleType()
                + "，阈值 " + (rule.getThreshold() == null ? "无" : rule.getThreshold()) + "）\n"
                + "命中证据：" + evidence + "\n"
                + "事件：" + event.getStartTime() + " 在 "
                + (event.getPoiName() == null ? "未知地点" : event.getPoiName())
                + (event.getDwellSeconds() == null ? "" : "，停留 " + event.getDwellSeconds() + " 秒")
                + "，语义标注「" + (event.getSemanticLabel() == null ? "未标注" : event.getSemanticLabel())
                + "」\n"
                + "关联条款原文：" + (clauseText == null ? "无" : clauseText) + "\n"
                + "请输出 JSON。";

        Optional<String> raw = chatClient.chat(system, user);
        if (raw.isEmpty()) {
            log.warn("LLM 说明生成失败，结论将转人工复核 ruleCode={} eventId={}",
                    rule.getRuleCode(), event.getEventId());
            return null;
        }
        return parseJudgment(raw.get());
    }

    /** 从模型输出提取 JSON 对象并解析；解析失败返回 null（fail-closed）。 */
    private AiJudgment parseJudgment(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            String reason = node.path("reason").isTextual() ? node.path("reason").asText() : null;
            String code = node.path("clause_code").isTextual() ? node.path("clause_code").asText() : null;
            return new AiJudgment(reason, code);
        } catch (Exception e) {
            log.warn("LLM 输出 JSON 解析失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 引用有效性判定：规则关联了条款时，引用必须存在于<b>同一文档</b>；
     * 规则没关联条款时退化为全库校验。停用条款不算有效引用。
     */
    private boolean citationExists(ComplianceRule rule, String cited) {
        LambdaQueryWrapper<PolicyClause> q = new LambdaQueryWrapper<PolicyClause>()
                .eq(PolicyClause::getClauseCode, cited)
                .eq(PolicyClause::getStatus, 1);
        if (rule.getPolicyClauseId() != null) {
            PolicyClause anchor = clauseMapper.selectById(rule.getPolicyClauseId());
            if (anchor == null) {
                // 规则挂的条款已不存在：文档范围无从确定，按引用无效处理
                return false;
            }
            q.eq(PolicyClause::getDocId, anchor.getDocId());
        }
        return count(clauseMapper.selectCount(q)) > 0;
    }

    /** 规则关联条款的原文，作为提示词上下文帮模型对准编号。 */
    private String linkedClauseText(ComplianceRule rule) {
        if (rule.getPolicyClauseId() == null) {
            return null;
        }
        PolicyClause clause = clauseMapper.selectById(rule.getPolicyClauseId());
        return clause == null ? null : clause.getClauseCode() + " " + clause.getContent();
    }

    /** 疑似违规自动生成工单：待处理状态，由责任员工后续处理或申诉。 */
    private Long insertOrder(ComplianceCheck check, Long assignBy) {
        if (existsOrder(check.getCheckId())) {
            return null;
        }
        RectificationOrder order = new RectificationOrder();
        order.setCheckId(check.getCheckId());
        order.setUserId(check.getUserId());
        order.setContent(orderContent(check));
        order.setStatus(0);
        order.setAssignBy(assignBy);
        order.setAssignTime(LocalDateTime.now());
        orderMapper.insert(order);
        log.info("已生成整改工单 orderId={} checkId={}", order.getOrderId(), check.getCheckId());
        return order.getOrderId();
    }

    private String orderContent(ComplianceCheck check) {
        ComplianceRule rule = ruleMapper.selectById(check.getRuleId());
        String head = rule == null ? "合规核查结论" : rule.getRuleName();
        String text = head + "：" + (check.getEvidence() == null ? "" : check.getEvidence());
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private List<ComplianceRule> enabledRules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<ComplianceRule>()
                .eq(ComplianceRule::getEnabled, 1));
    }

    /** 当日已确认停留事件（含刚确认的本条），FREQUENCY 的「同类」统计输入。 */
    private List<WorkEvent> confirmedStaysOfDay(Long userId, LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        return workEventMapper.selectList(new LambdaQueryWrapper<WorkEvent>()
                .eq(WorkEvent::getUserId, userId)
                .eq(WorkEvent::getEventType, "STAY")
                .eq(WorkEvent::getConfirmStatus, 1)
                .ge(WorkEvent::getStartTime, dayStart)
                .lt(WorkEvent::getStartTime, dayEnd));
    }

    private boolean alreadyChecked(ComplianceRule rule, WorkEvent event, LocalDate workDate) {
        if ("FREQUENCY".equals(rule.getRuleType())) {
            return existsCheck(rule.getRuleId(), event.getUserId(), workDate, null);
        }
        return existsCheck(rule.getRuleId(), event.getUserId(), workDate, event.getEventId());
    }

    private boolean existsCheck(Long ruleId, Long userId, LocalDate date, Long eventId) {
        LambdaQueryWrapper<ComplianceCheck> q = new LambdaQueryWrapper<ComplianceCheck>()
                .eq(ComplianceCheck::getRuleId, ruleId)
                .eq(ComplianceCheck::getUserId, userId)
                .eq(ComplianceCheck::getWorkDate, date);
        if (eventId != null) {
            q.eq(ComplianceCheck::getWorkEventId, eventId);
        }
        return count(checkMapper.selectCount(q)) > 0;
    }

    private boolean existsOrder(Long checkId) {
        return count(orderMapper.selectCount(new LambdaQueryWrapper<RectificationOrder>()
                .eq(RectificationOrder::getCheckId, checkId))) > 0;
    }

    /** selectCount 的判空包装：MyBatis-Plus 正常返回 0，mock 环境可能给 null。 */
    private static long count(Long value) {
        return value == null ? 0 : value;
    }

    private static String normalizeCode(String code) {
        if (code == null) {
            return null;
        }
        String t = code.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) {
            return null;
        }
        return t.length() > 30 ? t.substring(0, 30) : t;
    }

    /** LLM 判定的结构化结果。 */
    record AiJudgment(String reason, String clauseCode) {
    }
}
