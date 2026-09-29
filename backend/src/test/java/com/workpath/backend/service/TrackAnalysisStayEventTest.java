package com.workpath.backend.service;

import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.TrackPoint;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import com.workpath.backend.service.analyze.StayPoint;
import com.workpath.backend.service.analyze.StayPointDetector;
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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4 接入层测试：停留点 → work_event 的落库编排。
 *
 * <p>算法本身在 {@link StayPointDetectorTest} 已逐条验过，
 * 这里只测「算法结果怎么变成事件」这件事：</p>
 * <ul>
 *   <li>POI 失败不能阻塞事件落库（实施指南明确的第三坑）</li>
 *   <li>语义标注失败同样不能阻塞——模型不可用时事件仍要落</li>
 *   <li>确认状态恒为 0（M3→M5 的边界：AI 结论不是管理依据）</li>
 *   <li>无停留时不要写空事件</li>
 * </ul>
 */
class TrackAnalysisStayEventTest {

    private TrackPointMapper trackPointMapper;
    private WorkEventMapper workEventMapper;
    private ChatClient chatClient;
    private com.workpath.backend.service.modelProvider.GeoClient geoClient;
    private TrackAnalysisService service;

    private static final LocalDateTime T0 =
            LocalDateTime.of(2026, 6, 14, 9, 0, 0);

    @BeforeEach
    void setUp() {
        trackPointMapper = Mockito.mock(TrackPointMapper.class);
        workEventMapper = Mockito.mock(WorkEventMapper.class);
        chatClient = Mockito.mock(ChatClient.class);
        geoClient = Mockito.mock(com.workpath.backend.service.modelProvider.GeoClient.class);
        service = new TrackAnalysisService(
                trackPointMapper, workEventMapper, new StayPointDetector(300),
                geoClient, chatClient);
    }

    // ============================================================
    // POI / 语义标注失败都必须降级而非阻塞
    // ============================================================

    @Test
    @DisplayName("POI 解析失败时事件照常落库，poiName 留空")
    void poiFailureDoesNotBlockEvent() {
        oneStay60s();
        when(geoClient.reverseGeocode(anyDouble(), anyDouble())).thenReturn(Optional.empty());

        service.analyze(track(1L));

        ArgumentCaptor<WorkEvent> cap = ArgumentCaptor.forClass(WorkEvent.class);
        verify(workEventMapper).insert(cap.capture());
        WorkEvent e = cap.getValue();
        assertThat(e.getPoiName()).isNull();
        // 事件本身完整落地
        assertThat(e.getEventType()).isEqualTo("STAY");
        assertThat(e.getDwellSeconds()).isEqualTo(600);
        assertThat(e.getConfirmStatus()).isZero();
    }

    @Test
    @DisplayName("POI 解析成功时地名写入 poiName")
    void poiSuccessPopulatesName() {
        oneStay60s();
        when(geoClient.reverseGeocode(anyDouble(), anyDouble()))
                .thenReturn(Optional.of("成都市青羊区天府广场"));

        service.analyze(track(1L));

        ArgumentCaptor<WorkEvent> cap = ArgumentCaptor.forClass(WorkEvent.class);
        verify(workEventMapper).insert(cap.capture());
        assertThat(cap.getValue().getPoiName()).isEqualTo("成都市青羊区天府广场");
    }

    @Test
    @DisplayName("语义标注失败时事件照常落库，semanticLabel 留空")
    void labelFailureDoesNotBlockEvent() {
        oneStay60s();
        when(geoClient.reverseGeocode(anyDouble(), anyDouble()))
                .thenReturn(Optional.of("成都市青羊区天府广场"));
        when(chatClient.chat(any(), any())).thenReturn(Optional.empty());

        service.analyze(track(1L));

        ArgumentCaptor<WorkEvent> cap = ArgumentCaptor.forClass(WorkEvent.class);
        verify(workEventMapper).insert(cap.capture());
        WorkEvent e = cap.getValue();
        assertThat(e.getSemanticLabel()).isNull();
        assertThat(e.getConfidence()).isNull();
        assertThat(e.getPoiName())
                .as("POI 与语义标注是两个独立降级点，标注失败不影响已拿到的地名")
                .isEqualTo("成都市青羊区天府广场");
    }

    @Test
    @DisplayName("语义标注成功时标签与置信度写入")
    void labelSuccessPopulatesFields() {
        oneStay60s();
        when(geoClient.reverseGeocode(anyDouble(), anyDouble()))
                .thenReturn(Optional.of("成都市青羊区天府广场"));
        // 真实模型返回的形如「客户拜访|0.85」
        when(chatClient.chat(any(), any())).thenReturn(Optional.of("客户拜访|0.85"));

        service.analyze(track(1L));

        ArgumentCaptor<WorkEvent> cap = ArgumentCaptor.forClass(WorkEvent.class);
        verify(workEventMapper).insert(cap.capture());
        WorkEvent e = cap.getValue();
        assertThat(e.getSemanticLabel()).isEqualTo("客户拜访");
        assertThat(e.getConfidence()).isEqualTo(0.85);
    }

    // ============================================================
    // 没有停留时不应产出事件
    // ============================================================

    @Test
    @DisplayName("整条轨迹都在移动时不写任何事件")
    void noStayWritesNothing() {
        List<TrackPoint> pts = new java.util.ArrayList<>();
        for (int i = 0; i < 121; i++) {
            TrackPoint p = new TrackPoint();
            p.setLatitude(30.657261 + 0.00009 * i);
            p.setLongitude(104.066678);
            p.setRecordTime(T0.plusSeconds((long) i * 5));
            pts.add(p);
        }
        when(trackPointMapper.selectByTrackId(1L)).thenReturn(pts);

        service.analyze(track(1L));

        verify(workEventMapper, never()).insert(any(WorkEvent.class));
    }

    @Test
    @DisplayName("每个停留点各写一条 STAY 事件，不是只写第一条")
    void everyStayWritesItsOwnEvent() {
        twoStays();
        when(geoClient.reverseGeocode(anyDouble(), anyDouble()))
                .thenAnswer(inv -> Optional.of("成都市某地"));
        when(chatClient.chat(any(), any())).thenReturn(Optional.of("客户拜访|0.9"));

        service.analyze(track(1L));

        ArgumentCaptor<WorkEvent> cap = ArgumentCaptor.forClass(WorkEvent.class);
        verify(workEventMapper, Mockito.times(2)).insert(cap.capture());
        assertThat(cap.getAllValues())
                .extracting(WorkEvent::getEventType)
                .containsExactly("STAY", "STAY");
        assertThat(cap.getAllValues())
                .extracting(WorkEvent::getDwellSeconds)
                .containsExactly(625, 600);
    }

    @Test
    @DisplayName("事件的时间区间与停留点一致，而不是整条轨迹的区间")
    void eventTimeMatchesStayNotTrack() {
        oneStay60s();
        when(geoClient.reverseGeocode(anyDouble(), anyDouble()))
                .thenReturn(Optional.empty());

        service.analyze(track(1L));

        ArgumentCaptor<WorkEvent> cap = ArgumentCaptor.forClass(WorkEvent.class);
        verify(workEventMapper).insert(cap.capture());
        WorkEvent e = cap.getValue();
        assertThat(e.getStartTime()).isEqualTo(T0);
        assertThat(e.getEndTime()).isEqualTo(T0.plusSeconds(600));
    }

    // ============================================================
    // 构造辅助
    // ============================================================

    private Track track(Long id) {
        Track t = new Track();
        t.setId(id);
        t.setUserId(42L);
        return t;
    }

    /** 一段持续 600 秒的静止轨迹。 */
    private void oneStay60s() {
        List<TrackPoint> pts = new java.util.ArrayList<>();
        for (int i = 0; i < 121; i++) {
            double j = (i % 2 == 0 ? 1 : -1) * 0.000010 * ((i % 3) + 1);
            TrackPoint p = new TrackPoint();
            p.setLatitude(30.657261 + j);
            p.setLongitude(104.066678 + j);
            p.setRecordTime(T0.plusSeconds((long) i * 5));
            pts.add(p);
        }
        when(trackPointMapper.selectByTrackId(1L)).thenReturn(pts);
    }

    /**
     * 两个停留点，中间夹 20 步移动。
     *
     * <p>时长取实测值（625/600）：种子锚定下 A 停留的邻域会吃进
     * 移动段的前 5 个点，多出 25 秒。</p>
     */
    private void twoStays() {
        List<TrackPoint> pts = new java.util.ArrayList<>();
        for (int i = 0; i < 121; i++) {
            double j = (i % 2 == 0 ? 1 : -1) * 0.000010 * ((i % 3) + 1);
            pts.add(at(30.657261 + j, 104.066678 + j, i));
        }
        for (int i = 0; i < 20; i++) {
            pts.add(at(30.657261 + 0.000108 * i, 104.066678, 121 + i));
        }
        for (int i = 0; i < 121; i++) {
            double j = (i % 2 == 0 ? 1 : -1) * 0.000010 * ((i % 3) + 1);
            pts.add(at(30.659961 + j, 104.066678 + j, 141 + i));
        }
        when(trackPointMapper.selectByTrackId(1L)).thenReturn(pts);
    }

    private TrackPoint at(double lat, double lng, int step) {
        TrackPoint p = new TrackPoint();
        p.setLatitude(lat);
        p.setLongitude(lng);
        p.setRecordTime(T0.plusSeconds((long) step * 5));
        return p;
    }
}
