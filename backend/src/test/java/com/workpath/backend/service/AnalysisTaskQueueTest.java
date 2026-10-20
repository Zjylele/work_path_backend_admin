package com.workpath.backend.service;

import com.workpath.backend.common.BusinessException;
import com.workpath.backend.entity.Track;
import com.workpath.backend.mapper.TrackMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 手动轨迹分析批次单元测试。
 *
 * <p>钉住管理员手动发起的这条入口：</p>
 * <ul>
 *   <li><b>先占用再执行</b>。占用是带数据库条件的更新
 *       （{@code analysis_status IN (0,3)}），影响行数不是 1 就说明被别的操作
 *       抢先，必须拒绝而不是继续分析；</li>
 *   <li><b>事务提交后才启动批次</b>。占用和启动之间隔着提交，若事务回滚，
 *       批次不该跑起来对一条"其实没占用成功"的轨迹做分析；</li>
 *   <li><b>单条失败不拖垮其余</b>。失败置 3 而不是停在 1——停在 1 的轨迹
 *       再也进不了待处理列表，等于失联；置 3 至少还能被重新发起。</li>
 * </ul>
 *
 * <p>这里 mock Mapper 与分析服务，被测对象只是编排本身；停留点识别与
 * 语义标注的正确性由 StayPointDetectorTest / TrackAnalysisServiceTest 覆盖。</p>
 *
 * <p><b>关于历史</b>：本类早期测的是定时任务版本的 {@code dispatch()}，
 * 那个入口在手动分析工作流上线时被移除，测试没跟着改，于是编译就过不去、
 * 挡住了全量 {@code mvn test}。本次按 {@code startManualBatch} 重写，
 * 保留了原来"0→1→2 流转""失败置 3 可重试"两条断言的精神。</p>
 */
class AnalysisTaskQueueTest {

    private TrackMapper trackMapper;
    private TrackAnalysisService analysisService;
    private AnalysisTaskQueue queue;

    /** 捕获真正投递到线程池的任务，测试里手动执行它 */
    private final List<Runnable> submitted = new ArrayList<>();

    /** 模拟库中的轨迹 */
    private final List<Track> storedTracks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        trackMapper = Mockito.mock(TrackMapper.class);
        analysisService = Mockito.mock(TrackAnalysisService.class);
        // 不直接跑，只收下任务——批次何时执行由测试控制
        TaskExecutor executor = submitted::add;

        // 占用：按数据库条件更新的语义，只有 0/3 能成功
        Mockito.lenient().when(trackMapper.claimForManualAnalysis(Mockito.anyLong()))
                .thenAnswer(inv -> {
                    Track t = find(inv.getArgument(0));
                    if (t == null || t.getAnalysisStatus() == null
                            || (t.getAnalysisStatus() != 0 && t.getAnalysisStatus() != 3)) {
                        return 0;
                    }
                    t.setAnalysisStatus(1);
                    return 1;
                });
        Mockito.lenient().when(trackMapper.selectById(Mockito.any(Long.class)))
                .thenAnswer(inv -> find(inv.getArgument(0)));
        // updateById 按主键更新非空字段，与真实 MyBatis-Plus 行为一致
        Mockito.lenient().when(trackMapper.updateById(Mockito.any(Track.class)))
                .thenAnswer(inv -> {
                    Track patch = inv.getArgument(0);
                    Track target = find(patch.getId());
                    if (target == null) {
                        return 0;
                    }
                    if (patch.getAnalysisStatus() != null) {
                        target.setAnalysisStatus(patch.getAnalysisStatus());
                    }
                    return 1;
                });

        queue = new AnalysisTaskQueue(trackMapper, analysisService, executor);

        // 僵死复位：按 SQL 语义只改 analysis_status=1 且早于阈值的行
        Mockito.lenient().when(trackMapper.resetStaleRunningTracks(Mockito.anyInt(), Mockito.anyInt()))
                .thenAnswer(inv -> {
                    int target = inv.getArgument(0);
                    int minutes = inv.getArgument(1);
                    LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
                    int[] n = {0};
                    storedTracks.stream()
                            .filter(t -> t.getAnalysisStatus() != null && t.getAnalysisStatus() == 1)
                            .filter(t -> t.getStartTime() != null && t.getStartTime().isBefore(cutoff))
                            .forEach(t -> {
                                t.setAnalysisStatus(target);
                                n[0]++;
                            });
                    return n[0];
                });

        // 每个用例一份干净状态。JUnit 5 的 @Nested 会复用外层测试实例，
        // storedTracks 若不在 beforeEach 清空，前一个用例改过的状态会被
        // findFirst() 捞到，表现为"莫名其妙的状态不可发起"。
        storedTracks.clear();
        submitted.clear();
    }

    private Track find(Long id) {
        return storedTracks.stream()
                .filter(t -> t.getId().equals(id))
                .findFirst().orElse(null);
    }

    private Track track(long id, int analysisStatus) {
        Track t = new Track();
        t.setId(id);
        t.setUserId(1L);
        t.setName("轨迹 " + id);
        t.setStartTime(LocalDateTime.of(2026, 9, 1, 9, 0));
        t.setEndTime(LocalDateTime.of(2026, 9, 1, 18, 0));
        t.setStatus(1);
        t.setAnalysisStatus(analysisStatus);
        storedTracks.add(t);
        return t;
    }

    /** 跑完提交后注册的收尾动作，模拟事务真正提交。 */
    private void commitPending() {
        List<Runnable> snapshot = List.copyOf(submitted);
        submitted.clear();
        snapshot.forEach(Runnable::run);
    }

    @Nested
    @DisplayName("提交前的占用与校验")
    class Claiming {

        @Test
        @DisplayName("待分析与失败的轨迹都能发起；发起后即被占用为分析中(1)")
        void claimsPendingAndFailed() {
            track(1L, 0);
            track(2L, 3);

            queue.startManualBatch(List.of(1L, 2L));

            // 占用发生在方法返回前：不依赖事务提交就能看到状态已置 1
            assertThat(find(1L).getAnalysisStatus()).isEqualTo(1);
            assertThat(find(2L).getAnalysisStatus()).isEqualTo(1);
            // 且确实用了带条件的占用更新，而不是无条件置 1
            Mockito.verify(trackMapper).claimForManualAnalysis(1L);
            Mockito.verify(trackMapper).claimForManualAnalysis(2L);
        }

        @Test
        @DisplayName("空列表、超上限、重复 ID、空 ID 一律拒绝")
        void rejectsInvalidBatches() {
            assertThatThrownBy(() -> queue.startManualBatch(List.of()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("1 到 10 条");
            assertThatThrownBy(() -> queue.startManualBatch(
                            LongStream.rangeClosed(1, 11).boxed().toList()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("1 到 10 条");
            assertThatThrownBy(() -> queue.startManualBatch(List.of(1L, 1L)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("重复");
            // List.of 不接受 null，用 ArrayList 构造含空 ID 的入参
            List<Long> withNull = new ArrayList<>(List.of(1L));
            withNull.add(null);
            assertThatThrownBy(() -> queue.startManualBatch(withNull))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("为空");

            // 一条都不该被占用
            Mockito.verify(trackMapper, Mockito.never()).claimForManualAnalysis(Mockito.anyLong());
        }

        @Test
        @DisplayName("轨迹不存在：拒绝并说明是哪一个")
        void rejectsUnknownTrack() {
            assertThatThrownBy(() -> queue.startManualBatch(List.of(1L)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("轨迹不存在：1");
        }

        @Test
        @DisplayName("已分析或分析中的轨迹不可发起——状态不在 (0,3) 内")
        void rejectsNonRestartableStatus() {
            track(1L, 2);
            track(2L, 1);

            assertThatThrownBy(() -> queue.startManualBatch(List.of(1L)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不可发起分析");
            assertThatThrownBy(() -> queue.startManualBatch(List.of(2L)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不可发起分析");
        }

        @Test
        @DisplayName("占用被抢占（条件更新影响 0 行）：拒绝，不进入分析")
        void rejectsWhenClaimLoses() {
            // 校验读的是占用前的状态，占用才是真正写库那一下。
            // 这条用例验的是第二道防线失守：状态看着合法，但条件更新影响 0 行
            // （并发场景下另一个操作刚好把它改走了）。
            //
            // 用 doReturn 而不是 when(...).thenReturn(...)：后者会先真调一次 mock，
            // 命中 setUp 里的默认 stub，而那个 stub 带"把状态改成 1"的副作用——
            // 于是还没进 startManualBatch，状态就已经不是 0，校验先抛"不可发起"，
            // 根本走不到占用那一步。这是 Mockito 的经典陷阱，值得留注释。
            track(1L, 0);
            Mockito.doReturn(0).when(trackMapper).claimForManualAnalysis(1L);

            assertThatThrownBy(() -> queue.startManualBatch(List.of(1L)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("已被其他操作启动");

            // 状态没被占用更新改写，仍停在 0
            assertThat(find(1L).getAnalysisStatus()).isZero();
            Mockito.verify(analysisService, Mockito.never()).analyze(Mockito.any());
            assertThat(submitted).isEmpty();
        }
    }

    @Nested
    @DisplayName("批次执行")
    class Processing {

        @Test
        @DisplayName("事务提交后才启动批次；提交前不执行分析")
        void startsOnlyAfterCommit() {
            track(1L, 0);

            // 光 initSynchronization 不够：没有实际事务时 registerSynchronization 不会生效，
            // 所以这里手动绑定一个事务持有者，模拟 @Transactional 的真实环境。
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                queue.startManualBatch(List.of(1L));
                // 占用已完成，但批次还没跑——它在等 afterCommit
                assertThat(submitted).isEmpty();
                Mockito.verify(analysisService, Mockito.never()).analyze(Mockito.any());

                // 提交：触发 afterCommit 回调
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(s -> s.afterCommit());
                assertThat(submitted).hasSize(1);

                submitted.get(0).run();
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }

            Mockito.verify(analysisService).analyze(Mockito.any(Track.class));
            assertThat(find(1L).getAnalysisStatus()).isEqualTo(2);
        }

        @Test
        @DisplayName("无事务上下文时立即启动（不依赖同步注册）")
        void startsImmediatelyWithoutTransaction() {
            track(1L, 0);

            queue.startManualBatch(List.of(1L));

            assertThat(submitted).hasSize(1);
        }

        @Test
        @DisplayName("0 → 1 → 2：占用先于分析，完成后置已分析")
        void flowsZeroOneTwo() {
            track(1L, 0);

            queue.startManualBatch(List.of(1L));
            commitPending();

            ArgumentCaptor<Track> cap = ArgumentCaptor.forClass(Track.class);
            Mockito.verify(trackMapper, Mockito.atLeastOnce()).updateById(cap.capture());
            List<Integer> statuses = cap.getAllValues().stream()
                    .map(Track::getAnalysisStatus).filter(java.util.Objects::nonNull).toList();

            assertThat(statuses.get(statuses.size() - 1)).isEqualTo(2);
            assertThat(find(1L).getAnalysisStatus()).isEqualTo(2);
        }

        @Test
        @DisplayName("某条分析抛异常：该条置失败态(3)，同批其余继续完成")
        void failureIsolated() {
            Track ok = track(1L, 0);
            Track bad = track(2L, 0);

            Mockito.doThrow(new RuntimeException("停留识别输入点数不足"))
                    .when(analysisService).analyze(Mockito.argThat(t -> t.getId().equals(2L)));

            queue.startManualBatch(List.of(1L, 2L));
            commitPending();

            // 失败的那条不留在 1，置 3 才能被再次发起
            assertThat(bad.getAnalysisStatus()).isEqualTo(3);
            assertThat(ok.getAnalysisStatus()).isEqualTo(2);
        }

        @Test
        @DisplayName("失败后仍可重新发起——3 是允许重新分析的状态")
        void failureAllowsRetry() {
            track(1L, 0);
            Mockito.doThrow(new RuntimeException("模型调用超时"))
                    .when(analysisService).analyze(Mockito.any(Track.class));

            queue.startManualBatch(List.of(1L));
            commitPending();

            assertThat(find(1L).getAnalysisStatus()).isEqualTo(3);

            // 同样的轨迹再来一次：占用条件 (0,3) 成立，这次分析不再抛异常
            Mockito.doNothing().when(analysisService).analyze(Mockito.any(Track.class));
            queue.startManualBatch(List.of(1L));
            commitPending();

            assertThat(find(1L).getAnalysisStatus()).isEqualTo(2);
        }

        @Test
        @DisplayName("批次执行中轨迹被删：跳过该条，其余继续完成")
        void skipsDeletedTrack() {
            Track ok = track(1L, 0);
            track(2L, 0);
            // selectById 对 2L 返回 null，模拟批次执行期间该轨迹已被删除。
            // 注意 stub 要写在 startManualBatch 之后——占用阶段也走 selectById，
            // 提前 stub 会让它连占用都过不去。
            queue.startManualBatch(List.of(1L, 2L));
            Mockito.when(trackMapper.selectById(2L)).thenReturn(null);
            commitPending();

            // 被删的那条不置任何终态（占用时的 1 保持原样由人工发现），其余照常完成
            assertThat(ok.getAnalysisStatus()).isEqualTo(2);
            Mockito.verify(analysisService).analyze(Mockito.argThat(t -> t.getId().equals(1L)));
            Mockito.verify(analysisService, Mockito.never())
                    .analyze(Mockito.argThat(t -> t.getId().equals(2L)));
        }
    }

    @Nested
    @DisplayName("僵死的分析中(1)要能自救")
    class StaleRecovery {

        @Test
        @DisplayName("启动时把早于阈值的分析中(1)复位为失败(3)，让管理员能重试")
        void resetsStaleRunningTracks() {
            Track stale = track(1L, 1);
            stale.setStartTime(LocalDateTime.now().minusHours(2));
            Track fresh = track(2L, 1);
            fresh.setStartTime(LocalDateTime.now().minusMinutes(1));

            int reset = queue.recoverStaleRunningTracks();

            assertThat(reset).isEqualTo(1);
            assertThat(stale.getAnalysisStatus()).isEqualTo(3);
            // 刚提交的批次还在跑，不能动
            assertThat(fresh.getAnalysisStatus()).isEqualTo(1);
        }

        @Test
        @DisplayName("没有僵死轨迹时一次数据库都不写")
        void noopWhenNothingStale() {
            track(1L, 0);
            track(2L, 2);

            assertThat(queue.recoverStaleRunningTracks()).isZero();
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
