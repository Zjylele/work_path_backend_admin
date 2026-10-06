package com.workpath.backend.service;

import com.workpath.backend.entity.ComplianceCheck;
import com.workpath.backend.entity.ComplianceRule;
import com.workpath.backend.entity.PolicyClause;
import com.workpath.backend.entity.RectificationOrder;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.mapper.ComplianceCheckMapper;
import com.workpath.backend.mapper.ComplianceRuleMapper;
import com.workpath.backend.mapper.PolicyClauseMapper;
import com.workpath.backend.mapper.RectificationOrderMapper;
import com.workpath.backend.mapper.TrackMapper;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W6 条款回查机制测试——全系统创新点的行为钉子。
 *
 * <p>实施指南 8.1 的要求：条款存在 → verified=1；条款不存在 → verified=0
 * 且 result=2。这里再加三类必须 fail-closed 的情形：模型没给引用、模型
 * 整个失败、输出不是 JSON——三种都落在 result=2 转人工复核，
 * 无效引用不能进入工单流程。</p>
 */
class ComplianceServiceTest {

    private ComplianceRuleMapper ruleMapper;
    private ComplianceCheckMapper checkMapper;
    private RectificationOrderMapper orderMapper;
    private WorkEventMapper workEventMapper;
    private TrackMapper trackMapper;
    private TrackPointMapper trackPointMapper;
    private PolicyClauseMapper clauseMapper;
    private ChatClient chatClient;
    private ComplianceService service;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 30, 9, 0, 0);

    @BeforeEach
    void setUp() {
        ruleMapper = Mockito.mock(ComplianceRuleMapper.class);
        checkMapper = Mockito.mock(ComplianceCheckMapper.class);
        orderMapper = Mockito.mock(RectificationOrderMapper.class);
        workEventMapper = Mockito.mock(WorkEventMapper.class);
        trackMapper = Mockito.mock(TrackMapper.class);
        trackPointMapper = Mockito.mock(TrackPointMapper.class);
        clauseMapper = Mockito.mock(PolicyClauseMapper.class);
        chatClient = Mockito.mock(ChatClient.class);
        service = new ComplianceService(ruleMapper, checkMapper, orderMapper,
                workEventMapper, trackMapper, trackPointMapper, clauseMapper,
                chatClient, new ObjectMapper());
    }

    @Test
    @DisplayName("条款回查通过：result=1、clauseVerified=1，并自动生成工单")
    void verifiedCitationCreatesOrder() {
        ComplianceRule rule = durationRule(10L);
        PolicyClause anchor = clause(10L, 5L, "3.1");
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(clauseMapper.selectById(10L)).thenReturn(anchor);
        when(clauseMapper.selectCount(any())).thenReturn(1L);
        when(chatClient.chat(anyString(), anyString())).thenReturn(Optional.of(
                "{\"reason\":\"单次停留超时\",\"clause_code\":\"3.2\"}"));

        service.onEventConfirmed(confirmedStay());

        ArgumentCaptor<ComplianceCheck> cap = ArgumentCaptor.forClass(ComplianceCheck.class);
        verify(checkMapper).insert(cap.capture());
        ComplianceCheck check = cap.getValue();
        assertThat(check.getResult()).isEqualTo(1);
        assertThat(check.getClauseVerified()).isEqualTo(1);
        assertThat(check.getCitedClauseCode()).isEqualTo("3.2");
        assertThat(check.getAiReason()).isEqualTo("单次停留超时");
        assertThat(check.getWorkDate()).isEqualTo(T0.toLocalDate());
        // 回查通过才生成工单
        verify(orderMapper).insert(any(RectificationOrder.class));
    }

    @Test
    @DisplayName("引用的条款编号不存在：result=2、clauseVerified=0，不生成工单")
    void invalidCitationGoesToManualReview() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(durationRule(10L)));
        when(clauseMapper.selectById(10L)).thenReturn(clause(10L, 5L, "3.1"));
        when(clauseMapper.selectCount(any())).thenReturn(0L);
        when(chatClient.chat(anyString(), anyString())).thenReturn(Optional.of(
                "{\"reason\":\"超时停留\",\"clause_code\":\"9.9\"}"));

        service.onEventConfirmed(confirmedStay());

        ArgumentCaptor<ComplianceCheck> cap = ArgumentCaptor.forClass(ComplianceCheck.class);
        verify(checkMapper).insert(cap.capture());
        assertThat(cap.getValue().getResult()).isEqualTo(2);
        assertThat(cap.getValue().getClauseVerified()).isZero();
        // 无效引用没有机会进入工单流程
        verify(orderMapper, never()).insert(any(RectificationOrder.class));
    }

    @Test
    @DisplayName("模型没给出条款编号：视为引用无效，转人工复核")
    void missingCitationIsInvalid() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(durationRule(10L)));
        when(chatClient.chat(anyString(), anyString())).thenReturn(Optional.of(
                "{\"reason\":\"超时停留\",\"clause_code\":\"\"}"));

        service.onEventConfirmed(confirmedStay());

        ArgumentCaptor<ComplianceCheck> cap = ArgumentCaptor.forClass(ComplianceCheck.class);
        verify(checkMapper).insert(cap.capture());
        assertThat(cap.getValue().getResult()).isEqualTo(2);
        assertThat(cap.getValue().getCitedClauseCode()).isNull();
        verify(orderMapper, never()).insert(any(RectificationOrder.class));
    }

    @Test
    @DisplayName("LLM 整体失败：结论仍落库（证据在），转人工复核")
    void llmFailureStillRecordsCheck() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(durationRule(10L)));
        when(chatClient.chat(anyString(), anyString())).thenReturn(Optional.empty());

        service.onEventConfirmed(confirmedStay());

        ArgumentCaptor<ComplianceCheck> cap = ArgumentCaptor.forClass(ComplianceCheck.class);
        verify(checkMapper).insert(cap.capture());
        ComplianceCheck check = cap.getValue();
        assertThat(check.getResult()).isEqualTo(2);
        assertThat(check.getAiReason()).isNull();
        assertThat(check.getEvidence()).contains("超过阈值");
    }

    @Test
    @DisplayName("模型输出不是 JSON：解析不出引用，转人工复核")
    void nonJsonOutputFailsClosed() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(durationRule(10L)));
        when(chatClient.chat(anyString(), anyString())).thenReturn(
                Optional.of("该员工停留时间过长，违反了相关制度条款。"));

        service.onEventConfirmed(confirmedStay());

        verify(checkMapper).insert(any(ComplianceCheck.class));
        verify(orderMapper, never()).insert(any(RectificationOrder.class));
    }

    @Test
    @DisplayName("规则未关联条款时引用退化为全库校验")
    void citationWithoutRuleAnchorChecksWholeLibrary() {
        ComplianceRule rule = durationRule(null);
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(clauseMapper.selectCount(any())).thenReturn(1L);
        when(chatClient.chat(anyString(), anyString())).thenReturn(Optional.of(
                "{\"reason\":\"超时\",\"clause_code\":\"3.2\"}"));

        service.onEventConfirmed(confirmedStay());

        verify(clauseMapper, never()).selectById(any());
        verify(orderMapper).insert(any(RectificationOrder.class));
    }

    @Test
    @DisplayName("幂等：同一事件重复确认不产生重复结论")
    void repeatedConfirmDoesNotDuplicate() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(durationRule(10L)));
        when(checkMapper.selectCount(any())).thenReturn(1L);

        service.onEventConfirmed(confirmedStay());

        verify(checkMapper, never()).insert(any(ComplianceCheck.class));
        verify(chatClient, never()).chat(anyString(), anyString());
    }

    @Test
    @DisplayName("无启用规则时核查直接跳过")
    void noRulesSkipsEverything() {
        when(ruleMapper.selectList(any())).thenReturn(List.of());

        service.onEventConfirmed(confirmedStay());

        verify(chatClient, never()).chat(anyString(), anyString());
        verify(checkMapper, never()).insert(any(ComplianceCheck.class));
    }

    @Test
    @DisplayName("返回值＝本次新增结论数：调用方据此区分「未命中」与「核查跑过」")
    void returnsCreatedCheckCount() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(durationRule(10L)));
        when(clauseMapper.selectById(10L)).thenReturn(clause(10L, 5L, "3.1"));
        when(clauseMapper.selectCount(any())).thenReturn(1L);
        when(chatClient.chat(anyString(), anyString())).thenReturn(Optional.of(
                "{\"reason\":\"单次停留超时\",\"clause_code\":\"3.2\"}"));

        assertThat(service.onEventConfirmed(confirmedStay())).isEqualTo(1);
    }

    @Test
    @DisplayName("无启用规则时返回 0——调用方提示「未命中规则」而非「核查失败」")
    void returnsZeroWithoutRules() {
        when(ruleMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.onEventConfirmed(confirmedStay())).isZero();
    }

    @Test
    @DisplayName("ABNORMAL 扫描：无停留的已分析轨迹落 result=2 结论，不自动生成工单")
    void abnormalScanCreatesManualReviewCheck() {
        ComplianceRule rule = new ComplianceRule();
        rule.setRuleId(200L);
        rule.setRuleCode("ABN-01");
        rule.setRuleName("无停留异常");
        rule.setRuleType("ABNORMAL");
        rule.setEnabled(1);
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));

        com.workpath.backend.entity.Track track = new com.workpath.backend.entity.Track();
        track.setId(7L);
        track.setUserId(5L);
        track.setStartTime(T0);
        track.setEndTime(T0.plusMinutes(90));
        when(trackMapper.selectList(any())).thenReturn(List.of(track));
        when(workEventMapper.selectCount(any())).thenReturn(0L);
        when(trackPointMapper.selectCount(any())).thenReturn(50L);

        int created = service.scanAbnormalTracks();

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<ComplianceCheck> cap = ArgumentCaptor.forClass(ComplianceCheck.class);
        verify(checkMapper).insert(cap.capture());
        assertThat(cap.getValue().getResult()).isEqualTo(2);
        assertThat(cap.getValue().getWorkEventId()).isNull();
        assertThat(cap.getValue().getEvidence()).contains("未识别出任何停留");
        verify(orderMapper, never()).insert(any(RectificationOrder.class));
    }

    // ============================================================
    // 造数
    // ============================================================

    private ComplianceRule durationRule(Long clauseId) {
        ComplianceRule rule = new ComplianceRule();
        rule.setRuleId(100L);
        rule.setRuleCode("DUR-30");
        rule.setRuleName("单次停留超时");
        rule.setRuleType("DURATION");
        rule.setThreshold(30.0);
        rule.setPolicyClauseId(clauseId);
        rule.setEnabled(1);
        return rule;
    }

    private PolicyClause clause(Long clauseId, Long docId, String code) {
        PolicyClause c = new PolicyClause();
        c.setClauseId(clauseId);
        c.setDocId(docId);
        c.setClauseCode(code);
        c.setContent("外勤人员单次停留不得超过三十分钟");
        c.setStatus(1);
        return c;
    }

    private WorkEvent confirmedStay() {
        WorkEvent e = new WorkEvent();
        e.setEventId(1L);
        e.setEventType("STAY");
        e.setUserId(5L);
        e.setStartTime(T0);
        e.setEndTime(T0.plusSeconds(3600));
        e.setDwellSeconds(3600);
        e.setCenterLat(30.5484);
        e.setCenterLng(104.0626);
        e.setPoiName("成都市武侯区某大厦");
        e.setSemanticLabel("驻点办公");
        return e;
    }
}
