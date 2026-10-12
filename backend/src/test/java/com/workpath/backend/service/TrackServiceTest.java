package com.workpath.backend.service;

import com.workpath.backend.dto.TrackPointDTO;
import com.workpath.backend.dto.TrackUploadDTO;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.TrackPoint;
import com.workpath.backend.entity.User;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.TrackMapper;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import com.workpath.backend.vo.TrackDetailVO;
import com.workpath.backend.vo.TrackEventVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 轨迹服务单元测试（Mockito 顶替两个 Mapper）。
 *
 * <p>焦点是轨迹名称链路：移动端开始记录时录入的名称随上传落库、
 * 空白名称归为 null（展示层统一走「未命名」）、旧客户端不传名称不受影响、
 * 详情把名称带出去。其余（幂等、权限）已有集成路径覆盖，不在此重复。</p>
 */
class TrackServiceTest {

    private TrackMapper trackMapper;
    private TrackPointMapper trackPointMapper;
    private WorkEventMapper workEventMapper;
    private TrackService service;

    @BeforeEach
    void setUp() {
        trackMapper = Mockito.mock(TrackMapper.class);
        trackPointMapper = Mockito.mock(TrackPointMapper.class);
        workEventMapper = Mockito.mock(WorkEventMapper.class);
        service = new TrackService(trackMapper, trackPointMapper, workEventMapper);

        // insert 时回填自增 id，模拟 MyBatis-Plus 的行为
        Mockito.lenient().when(trackMapper.insert(Mockito.any(Track.class))).thenAnswer(inv -> {
            Track t = inv.getArgument(0);
            t.setId(99L);
            return 1;
        });

        User u = new User();
        u.setId(7L);
        u.setUsername("zhangsan");
        u.setRole("USER");
        UserContext.set(u);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private TrackUploadDTO uploadDto(String name) {
        TrackUploadDTO dto = new TrackUploadDTO();
        dto.setClientTrackId(null);
        dto.setName(name);
        dto.setStartTime(LocalDateTime.of(2026, 10, 4, 8, 30));
        dto.setEndTime(LocalDateTime.of(2026, 10, 4, 9, 2));
        dto.setStatus(2);
        dto.setDistance(1350.0);
        TrackPointDTO p = new TrackPointDTO();
        p.setLatitude(30.5745);
        p.setLongitude(104.0609);
        p.setRecordTime(LocalDateTime.of(2026, 10, 4, 8, 30));
        dto.setPoints(List.of(p));
        return dto;
    }

    @Test
    @DisplayName("上传带名称时，名称随轨迹落库")
    void uploadPersistsName() {
        service.upload(uploadDto("客户拜访行程"));

        ArgumentCaptor<Track> cap = ArgumentCaptor.forClass(Track.class);
        Mockito.verify(trackMapper).insert(cap.capture());
        assertThat(cap.getValue().getName()).isEqualTo("客户拜访行程");
        assertThat(cap.getValue().getUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("名称首尾空白被裁剪后落库")
    void nameTrimmed() {
        service.upload(uploadDto("  客户拜访行程  "));

        ArgumentCaptor<Track> cap = ArgumentCaptor.forClass(Track.class);
        Mockito.verify(trackMapper).insert(cap.capture());
        assertThat(cap.getValue().getName()).isEqualTo("客户拜访行程");
    }

    @Test
    @DisplayName("空白名称归为 null 入库（展示层统一显示「未命名」，不落 '未命名' 文案）")
    void blankNameBecomesNull() {
        service.upload(uploadDto("   "));

        ArgumentCaptor<Track> cap = ArgumentCaptor.forClass(Track.class);
        Mockito.verify(trackMapper).insert(cap.capture());
        assertThat(cap.getValue().getName()).isNull();
    }

    @Test
    @DisplayName("旧版客户端不传名称时按 null 入库，上传不受影响")
    void missingNameStillUploads() {
        Long id = service.upload(uploadDto(null));

        assertThat(id).isEqualTo(99L);
        ArgumentCaptor<Track> cap = ArgumentCaptor.forClass(Track.class);
        Mockito.verify(trackMapper).insert(cap.capture());
        assertThat(cap.getValue().getName()).isNull();
    }

    @Test
    @DisplayName("详情把名称带出（移动端详情页展示用）")
    void detailIncludesName() {
        Track track = new Track();
        track.setId(5L);
        track.setUserId(7L);
        track.setName("晨间配送路线");
        track.setStartTime(LocalDateTime.of(2026, 10, 4, 8, 30));
        Mockito.when(trackMapper.selectById(5L)).thenReturn(track);
        Mockito.when(trackPointMapper.selectByTrackId(5L)).thenReturn(List.of());

        TrackDetailVO vo = service.detail(5L);

        assertThat(vo.getName()).isEqualTo("晨间配送路线");
        assertThat(vo.getId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("员工能取到自有轨迹的语义标注结果")
    void listEventsReturnsOwnTrackEvents() {
        Track track = new Track();
        track.setId(5L);
        track.setUserId(7L);
        Mockito.when(trackMapper.selectById(5L)).thenReturn(track);

        WorkEvent event = new WorkEvent();
        event.setEventId(11L);
        event.setTrackId(5L);
        event.setEventType("STAY");
        event.setStartTime(LocalDateTime.of(2026, 10, 4, 8, 30));
        event.setEndTime(LocalDateTime.of(2026, 10, 4, 8, 36, 45));
        event.setDwellSeconds(405);
        event.setPoiName("武侯区恒大珺临阁");
        event.setSemanticLabel("驻点办公");
        event.setConfidence(0.5);
        event.setAiReason("停留时长超过阈值的 6.8 分钟");
        event.setConfirmStatus(0);
        Mockito.when(workEventMapper.selectList(Mockito.any())).thenReturn(List.of(event));

        List<TrackEventVO> events = service.listEvents(5L);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventId()).isEqualTo(11L);
        assertThat(events.get(0).getSemanticLabel()).isEqualTo("驻点办公");
        assertThat(events.get(0).getDwellSeconds()).isEqualTo(405);
        assertThat(events.get(0).getPoiName()).isEqualTo("武侯区恒大珺临阁");
        assertThat(events.get(0).getConfirmStatus()).isZero();
    }

    @Test
    @DisplayName("取他人轨迹的语义标注被拒，且不查事件表")
    void listEventsRejectsOtherUsersTrack() {
        Track track = new Track();
        track.setId(5L);
        track.setUserId(999L);
        Mockito.when(trackMapper.selectById(5L)).thenReturn(track);

        assertThatThrownBy(() -> service.listEvents(5L))
                .isInstanceOf(com.workpath.backend.common.BusinessException.class);
        Mockito.verify(workEventMapper, Mockito.never()).selectList(Mockito.any());
    }
}
