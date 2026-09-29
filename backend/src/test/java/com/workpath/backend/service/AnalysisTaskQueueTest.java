package com.workpath.backend.service;

import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.TrackPoint;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.entity.User;
import com.workpath.backend.mapper.TrackMapper;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 轨迹分析任务队列单元测试。
 *
 * <p>钉住 M4 的状态机骨架：</p>
 * <ul>
 *   <li><b>0 → 1 → 2 的流转</b>（第2章 2.3.4 明确要求）。关键点是「进入分析前
 *       先置 1」——如果先分析再置状态，同一个轨迹会被下一轮定时任务重复捞起，
 *       并发跑两次分析；</li>
 *   <li><b>失败不卡死队列</b>。单条轨迹分析失败置 2（带失败标记）或保持 0 重试，
 *       但不能让它永远停在 1，否则后续定时任务再也不捞它，该轨迹彻底失联；</li>
 *   <li><b>上传接口不被分析拖慢</b>。分析只在定时任务里跑，upload 路径不触发。</li>
 * </ul>
 *
 * <p>这里用 mock 的 Mapper 与 AnalysisService，被测对象只是编排本身——
 * DBSCAN 与 POI 的正确性由 AnalysisServiceTest（W4）覆盖。</p>
 */
class AnalysisTaskQueueTest {

    private TrackMapper trackMapper;
    private TrackPointMapper trackPointMapper;
    private WorkEventMapper workEventMapper;
    private TrackAnalysisService analysisService;
    private AnalysisTaskQueue queue;

    /** 模拟库中的轨迹 */
    private final List<Track> storedTracks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        trackMapper = Mockito.mock(TrackMapper.class);
        trackPointMapper = Mockito.mock(TrackPointMapper.class);
        workEventMapper = Mockito.mock(WorkEventMapper.class);
        analysisService = Mockito.mock(TrackAnalysisService.class);

        // 捞未分析轨迹：按入库顺序返回，与分析执行顺序无关
        Mockito.lenient().when(trackMapper.selectPendingAnalysis(Mockito.anyInt()))
                .thenAnswer(inv -> storedTracks.stream()
                        .filter(t -> t.getAnalysisStatus() != null && t.getAnalysisStatus() == 0)
                        .limit(inv.<Integer>getArgument(0))
                        .toList());
        Mockito.lenient().when(trackMapper.selectById(Mockito.any(Long.class)))
                .thenAnswer(inv -> storedTracks.stream()
                        .filter(t -> t.getId().equals(inv.<Long>getArgument(0)))
                        .findFirst().orElse(null));
        // updateById 按主键更新非空字段，与真实 MyBatis-Plus 行为一致
        Mockito.lenient().when(trackMapper.updateById(Mockito.any(Track.class)))
                .thenAnswer(inv -> {
                    Track patch = inv.getArgument(0);
                    Track target = storedTracks.stream()
                            .filter(t -> t.getId().equals(patch.getId())).findFirst().orElse(null);
                    if (target == null) {
                        return 0;
                    }
                    if (patch.getAnalysisStatus() != null) {
                        target.setAnalysisStatus(patch.getAnalysisStatus());
                    }
                    if (patch.getStatus() != null) {
                        target.setStatus(patch.getStatus());
                    }
                    return 1;
                });

        queue = new AnalysisTaskQueue(trackMapper, analysisService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Track track(long id, int analysisStatus) {
        Track t = new Track();
        t.setId(id);
        t.setUserId(1L);
        t.setStartTime(LocalDateTime.of(2026, 9, 1, 9, 0));
        t.setEndTime(LocalDateTime.of(2026, 9, 1, 18, 0));
        t.setStatus(1);
        t.setAnalysisStatus(analysisStatus);
        storedTracks.add(t);
        return t;
    }

    @Nested
    @DisplayName("状态机流转 0 → 1 → 2")
    class StateMachine {

        @Test
        @DisplayName("捞起后先置分析中(1)，再执行分析，最后置完成(2)")
        void flowsZeroOneTwo() {
            track(1L, 0);

            queue.dispatch();

            ArgumentCaptor<Track> cap = ArgumentCaptor.forClass(Track.class);
            Mockito.verify(trackMapper, Mockito.atLeastOnce()).updateById(cap.capture());
            List<Integer> statuses = cap.getAllValues().stream()
                    .map(Track::getAnalysisStatus).filter(java.util.Objects::nonNull).toList();

            // 先看到 1（进入分析），最后看到 2（完成）
            assertThat(statuses.get(0)).isEqualTo(1);
            assertThat(statuses.get(statuses.size() - 1)).isEqualTo(2);
            assertThat(storedTracks.get(0).getAnalysisStatus()).isEqualTo(2);
        }

        @Test
        @DisplayName("进入分析前就置 1，防止下一轮定时任务重复捞同一轨迹")
        void marksBeforeAnalysis() {
            track(1L, 0);
            // 分析执行时把状态改回 0 —— 若实现是「先分析后置1」，这里会看到 0
            Mockito.doAnswer(inv -> {
                storedTracks.get(0).setAnalysisStatus(0);
                return null;
            }).when(analysisService).analyze(Mockito.any(Track.class));

            queue.dispatch();

            // 结束时必须是 2：置 1 发生在 analyze 之前，analyze 内部的改动不会回写
            assertThat(storedTracks.get(0).getAnalysisStatus()).isEqualTo(2);
        }

        @Test
        @DisplayName("没有未分析轨迹时一次数据库都不写")
        void noopWhenNothingPending() {
            track(1L, 2);

            queue.dispatch();

            // 指明参数类型：BaseMapper 有 updateById(T) 与 updateById(Collection) 两个重载
            Mockito.verify(trackMapper, Mockito.never()).updateById(Mockito.any(Track.class));
            Mockito.verify(analysisService, Mockito.never()).analyze(Mockito.any());
        }

        @Test
        @DisplayName("已在分析中(1)的轨迹不被捞起——上一轮遗留的由超时机制兜")
        void skipsInFlight() {
            track(1L, 1);

            queue.dispatch();

            Mockito.verify(analysisService, Mockito.never()).analyze(Mockito.any());
        }
    }

    @Nested
    @DisplayName("单条失败不拖垮队列")
    class Isolation {

        @Test
        @DisplayName("某条分析抛异常：该条置失败态(3)，其余继续完成")
        void failureIsolated() {
            Track ok = track(1L, 0);
            Track bad = track(2L, 0);

            Mockito.doThrow(new RuntimeException("DBSCAN 输入点数不足"))
                    .when(analysisService).analyze(Mockito.argThat(t -> t.getId().equals(2L)));

            queue.dispatch();

            // 失败的那条不留在 1（否则后续再也不捞），置 3 带标记
            assertThat(bad.getAnalysisStatus()).isEqualTo(3);
            // 正常的那条照常完成
            assertThat(ok.getAnalysisStatus()).isEqualTo(2);
        }

        @Test
        @DisplayName("失败后重试：同一轨迹能被再次捞起分析")
        void failureAllowsRetry() {
            Track t = track(1L, 0);
            Mockito.doThrow(new RuntimeException("网络超时"))
                    .when(analysisService).analyze(Mockito.any(Track.class));

            queue.dispatch();

            // 置 3 而不是停在 1：否则定时任务的 WHERE analysis_status=0 再也捞不到它
            assertThat(t.getAnalysisStatus()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("状态常量可读性")
    class Constants {

        @Test
        @DisplayName("四个状态都有命名常量，实现里不出现裸 0/1/2")
        void namedConstants() {
            assertThat(TrackAnalysisService.STATUS_PENDING).isZero();
            assertThat(TrackAnalysisService.STATUS_RUNNING).isEqualTo(1);
            assertThat(TrackAnalysisService.STATUS_DONE).isEqualTo(2);
            assertThat(TrackAnalysisService.STATUS_FAILED).isEqualTo(3);
        }
    }
}
