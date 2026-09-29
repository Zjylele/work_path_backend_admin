package com.workpath.backend.service;

import com.workpath.backend.common.BusinessException;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.TrackPoint;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 轨迹分析服务单元测试。
 *
 * <p>只测分析编排本身：点数下限、幂等清理顺序、事件的"待确认"初值。
 * 实体字段与库表的对账见 {@link EntitySchemaConsistencyTest}——
 * 那类问题（字段名与列名不一致）在 mock 环境里永远测不出来。</p>
 */
class TrackAnalysisServiceTest {

    private TrackPointMapper trackPointMapper;
    private WorkEventMapper workEventMapper;
    private TrackAnalysisService service;

    @BeforeEach
    void setUp() {
        trackPointMapper = Mockito.mock(TrackPointMapper.class);
        workEventMapper = Mockito.mock(WorkEventMapper.class);
        service = new TrackAnalysisService(trackPointMapper, workEventMapper);
    }

    @Test
    @DisplayName("点数不足 2 时抛出业务异常，不产出空事件")
    void tooFewPointsThrows() {
        Mockito.when(trackPointMapper.selectByTrackId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.analyze(track(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("点数不足");

        Mockito.verifyNoInteractions(workEventMapper);
    }

    @Test
    @DisplayName("分析前先清旧事件，保证同一条轨迹重跑不累积重复结论")
    void clearsOldEventsBeforeInsert() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 1, 9, 0);
        Mockito.when(trackPointMapper.selectByTrackId(1L))
                .thenReturn(List.of(point(t0), point(t0.plusMinutes(30))));

        service.analyze(track(1L));

        // delete 必须在 insert 之前，否则并发重跑会先插入再清空，把自己刚写的删掉
        InOrder order = Mockito.inOrder(workEventMapper);
        order.verify(workEventMapper).delete(Mockito.any());
        order.verify(workEventMapper).insert(Mockito.any(WorkEvent.class));
    }

    @Test
    @DisplayName("产出的事件 confirm_status 恒为 0（待确认）——AI 结论不直接变管理依据")
    void eventStaysPendingConfirmation() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 1, 9, 0);
        LocalDateTime t1 = t0.plusMinutes(30);
        Mockito.when(trackPointMapper.selectByTrackId(1L))
                .thenReturn(List.of(point(t0), point(t1)));

        service.analyze(track(1L));

        ArgumentCaptor<WorkEvent> cap = ArgumentCaptor.forClass(WorkEvent.class);
        Mockito.verify(workEventMapper).insert(cap.capture());
        WorkEvent e = cap.getValue();
        assertThat(e.getConfirmStatus()).isZero();
        assertThat(e.getTrackId()).isEqualTo(1L);
        assertThat(e.getUserId()).isEqualTo(42L);
        // 首尾点时间落入事件区间
        assertThat(e.getStartTime()).isEqualTo(t0);
        assertThat(e.getEndTime()).isEqualTo(t1);
    }

    // ---- 测试替身 ----

    private Track track(Long id) {
        Track t = new Track();
        t.setId(id);
        t.setUserId(42L);
        return t;
    }

    private TrackPoint point(LocalDateTime time) {
        TrackPoint p = new TrackPoint();
        p.setTrackId(1L);
        p.setLatitude(31.2);
        p.setLongitude(121.4);
        p.setRecordTime(time);
        return p;
    }
}
