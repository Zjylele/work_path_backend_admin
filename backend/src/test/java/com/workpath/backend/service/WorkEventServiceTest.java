package com.workpath.backend.service;

import com.workpath.backend.common.BusinessException;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.mapper.TrackMapper;
import com.workpath.backend.mapper.UserMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作事件复核（M4 人工确认入口）测试。
 *
 * <p>三条不变量在这里钉死：</p>
 * <ol>
 *   <li><b>状态不可回归</b>：已驳回不能被确认、已确认不能被驳回。确认动作会
 *       触发合规核查并生成工单，一旦允许 1→2，核查结论与工单就成了无主数据；
 *       反过来 2→1 会让「AI 判断有误」的人工结论被无声覆盖。</li>
 *   <li><b>核查失败不吞掉确认的可见性</b>：核查异常不能反过来让确认失败，
 *       但也不能像以前那样只写日志——调用方要能知道核查没成功。</li>
 *   <li><b>重新分析不得吃掉已确认结论</b>：analyze 会先删除该轨迹的全部事件，
 *       已确认事件被删后，其触发的 compliance_check.work_event_id 会指向不存在的行。</li>
 * </ol>
 */
class WorkEventServiceTest {

    private WorkEventMapper workEventMapper;
    private TrackMapper trackMapper;
    private UserMapper userMapper;
    private ComplianceService complianceService;
    private WorkEventService service;

    @BeforeEach
    void setUp() {
        workEventMapper = Mockito.mock(WorkEventMapper.class);
        trackMapper = Mockito.mock(TrackMapper.class);
        userMapper = Mockito.mock(UserMapper.class);
        complianceService = Mockito.mock(ComplianceService.class);
        service = new WorkEventService(workEventMapper, trackMapper, userMapper, complianceService);
        // patch 依赖 updateById 的返回行数；mock 默认 0 会被当成"事件已消失"
        when(workEventMapper.updateById(any(WorkEvent.class))).thenReturn(1);
        when(trackMapper.updateById(any(Track.class))).thenReturn(1);
    }

    // ============================================================
    // 确认
    // ============================================================

    @Test
    @DisplayName("待确认 --确认--> 已确认，并记录复核人与时间")
    void confirmPendingApproves() {
        when(workEventMapper.selectById(1L)).thenReturn(event(0));
        when(complianceService.onEventConfirmed(any(WorkEvent.class))).thenReturn(2);

        service.confirm(1L);

        ArgumentCaptor<WorkEvent> cap = ArgumentCaptor.forClass(WorkEvent.class);
        verify(workEventMapper).updateById(cap.capture());
        assertThat(cap.getValue().getConfirmStatus()).isEqualTo(1);
        assertThat(cap.getValue().getConfirmTime()).isNotNull();
    }

    @Test
    @DisplayName("确认返回核查结果：新增结论条数透传给调用方")
    void confirmReportsNewCheckCount() {
        when(workEventMapper.selectById(1L)).thenReturn(event(0));
        when(complianceService.onEventConfirmed(any(WorkEvent.class))).thenReturn(3);

        WorkEventService.ConfirmResult result = service.confirm(1L);

        assertThat(result.checkFailed()).isFalse();
        assertThat(result.newChecks()).isEqualTo(3);
    }

    @Test
    @DisplayName("核查抛异常时确认仍然成功，但结果标记为失败（不再静默）")
    void confirmSurvivesCheckFailure() {
        when(workEventMapper.selectById(1L)).thenReturn(event(0));
        when(complianceService.onEventConfirmed(any(WorkEvent.class)))
                .thenThrow(new RuntimeException("模型超时"));

        WorkEventService.ConfirmResult result = service.confirm(1L);

        assertThat(result.checkFailed()).isTrue();
        assertThat(result.newChecks()).isZero();
        // 确认动作本身已落库，不因核查失败回滚
        ArgumentCaptor<WorkEvent> cap = ArgumentCaptor.forClass(WorkEvent.class);
        verify(workEventMapper).updateById(cap.capture());
        assertThat(cap.getValue().getConfirmStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("已确认重复确认：幂等通过（去重由核查侧保证，不重复产生结论）")
    void confirmAlreadyApprovedIsIdempotent() {
        when(workEventMapper.selectById(1L)).thenReturn(event(1));
        when(complianceService.onEventConfirmed(any(WorkEvent.class))).thenReturn(0);

        service.confirm(1L);

        verify(workEventMapper).updateById(any(WorkEvent.class));
    }

    @Test
    @DisplayName("非法：已驳回的事件不能被确认（2→1 被拒）")
    void confirmRejectedIsConflict() {
        when(workEventMapper.selectById(1L)).thenReturn(event(2));

        assertThatThrownBy(() -> service.confirm(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已驳回");
        verify(workEventMapper, never()).updateById(any(WorkEvent.class));
    }

    @Test
    @DisplayName("非法：事件不存在时确认被拒")
    void confirmMissingEventRejected() {
        when(workEventMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.confirm(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ============================================================
    // 驳回
    // ============================================================

    @Test
    @DisplayName("待确认 --驳回--> 已驳回")
    void rejectPendingRejects() {
        when(workEventMapper.selectById(1L)).thenReturn(event(0));

        service.reject(1L);

        ArgumentCaptor<WorkEvent> cap = ArgumentCaptor.forClass(WorkEvent.class);
        verify(workEventMapper).updateById(cap.capture());
        assertThat(cap.getValue().getConfirmStatus()).isEqualTo(2);
        assertThat(cap.getValue().getConfirmTime()).isNotNull();
    }

    @Test
    @DisplayName("已驳回重复驳回：幂等通过")
    void rejectAlreadyRejectedIsIdempotent() {
        when(workEventMapper.selectById(1L)).thenReturn(event(2));

        service.reject(1L);

        verify(workEventMapper).updateById(any(WorkEvent.class));
    }

    @Test
    @DisplayName("非法：已确认的事件不能被驳回（1→2 被拒，核查结论与工单不会成为无主数据）")
    void rejectApprovedIsConflict() {
        when(workEventMapper.selectById(1L)).thenReturn(event(1));

        assertThatThrownBy(() -> service.reject(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已确认");
        verify(workEventMapper, never()).updateById(any(WorkEvent.class));
    }

    @Test
    @DisplayName("非法：事件不存在时驳回被拒")
    void rejectMissingEventRejected() {
        when(workEventMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.reject(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ============================================================
    // 重新分析
    // ============================================================

    @Test
    @DisplayName("重新分析：轨迹存在已确认事件时拒绝（避免删除确认记录）")
    void reanalyzeBlockedWhenConfirmedEventExists() {
        when(trackMapper.selectById(7L)).thenReturn(track());
        when(workEventMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.reanalyze(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已确认事件");
        verify(trackMapper, never()).updateById(any(Track.class));
    }

    @Test
    @DisplayName("重新分析：无已确认事件时置回未分析，等定时任务捞起")
    void reanalyzeResetsStatusWhenNothingConfirmed() {
        when(trackMapper.selectById(7L)).thenReturn(track());
        when(workEventMapper.selectCount(any())).thenReturn(0L);

        service.reanalyze(7L);

        ArgumentCaptor<Track> cap = ArgumentCaptor.forClass(Track.class);
        verify(trackMapper).updateById(cap.capture());
        assertThat(cap.getValue().getAnalysisStatus()).isZero();
    }

    @Test
    @DisplayName("非法：轨迹不存在时重新分析被拒")
    void reanalyzeMissingTrackRejected() {
        when(trackMapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> service.reanalyze(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ============================================================
    // 按轨迹查询（W8 分析详情页用）
    // ============================================================

    @Test
    @DisplayName("按轨迹查事件：返回该轨迹的全部事件，供详情页做时序展示")
    void listByTrackReturnsEventsOfTrack() {
        WorkEvent first = event(0);
        WorkEvent second = event(0);
        second.setEventId(2L);
        second.setStartTime(first.getStartTime().plusHours(2));
        when(workEventMapper.selectList(any())).thenReturn(List.of(first, second));

        List<WorkEvent> result = service.listByTrack(7L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getEventId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("按轨迹查事件：该轨迹没有事件时返回空列表，不抛异常")
    void listByTrackReturnsEmptyWhenNoEvents() {
        when(workEventMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listByTrack(99L)).isEmpty();
    }

    // ============================================================
    // 造数
    // ============================================================

    private WorkEvent event(int confirmStatus) {
        WorkEvent e = new WorkEvent();
        e.setEventId(1L);
        e.setTrackId(7L);
        e.setUserId(5L);
        e.setEventType("STAY");
        e.setStartTime(LocalDateTime.of(2026, 9, 30, 10, 0));
        e.setEndTime(LocalDateTime.of(2026, 9, 30, 10, 12));
        e.setDwellSeconds(720);
        e.setPoiName("天府软件园");
        e.setSemanticLabel("客户拜访");
        e.setConfidence(0.6);
        e.setConfirmStatus(confirmStatus);
        return e;
    }

    private Track track() {
        Track t = new Track();
        t.setId(7L);
        t.setUserId(5L);
        t.setAnalysisStatus(2);
        return t;
    }
}
