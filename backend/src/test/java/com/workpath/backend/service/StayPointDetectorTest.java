package com.workpath.backend.service;

import com.workpath.backend.entity.TrackPoint;
import com.workpath.backend.service.analyze.StayPoint;
import com.workpath.backend.service.analyze.StayPointDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W4 停留点识别的测试，验收标准逐条对应实施指南「W4 验收」：
 * 漂移点被剔除、停留点被识别、路口般的短时低速点没有被判成停留。
 *
 * <p>为什么把算法做成纯函数（不吃 Spring、不碰 DB）：这三条验收
 * 都要拿构造好的轨迹去比对输出，如果算法埋在 TrackAnalysisService 里，
 * 就得连库连 mapper 才能验，而 mock 掉的 mapper 会让断言失去意义。
 * 纯函数的测试是决定性的——输入写死，输出必须逐条对上。</p>
 *
 * <p>数据构造方式：采集间隔固定 5 秒（移动端 dart 里写死的），
 * 这里所有用例都按 5 秒一个点生成，与真实数据一致。</p>
 */
class StayPointDetectorTest {

    private static final LocalDateTime T0 =
            LocalDateTime.of(2026, 6, 14, 9, 0, 0);

    /** 采集间隔，秒。移动端 record_track_page.dart 写死的值。 */
    private static final int INTERVAL = 5;

    /** 停留判定阈值：簇持续 ≥ 此秒数才算停留（实施指南建议 300 秒起） */
    private static final int MIN_DWELL = 300;

    // ============================================================
    // 辅助构造
    // ============================================================

    /** 按固定间隔生成轨迹点。 */
    private static List<TrackPoint> points(List<double[]> latLngs, LocalDateTime start) {
        List<TrackPoint> out = new ArrayList<>();
        for (int i = 0; i < latLngs.size(); i++) {
            TrackPoint p = new TrackPoint();
            p.setLatitude(latLngs.get(i)[0]);
            p.setLongitude(latLngs.get(i)[1]);
            p.setRecordTime(start.plusSeconds((long) i * INTERVAL));
            out.add(p);
        }
        return out;
    }

    /**
     * 在固定坐标附近生成 n 个点，抖动幅度不超过 3 米
     * （GPS 静止时的正常漂移，必须保留在簇内）。
     */
    private static List<double[]> stationaryCluster(double lat, double lng, int n) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // 3 米的纬度约 0.000027°，刻意让它小于簇内 eps
            double jitter = (i % 2 == 0 ? 1 : -1) * 0.000010 * ((i % 3) + 1);
            out.add(new double[]{lat + jitter, lng + jitter});
        }
        return out;
    }

    /** 沿一条直线缓慢移动 n 步，每步 meters 米。 */
    private static List<double[]> movingLine(double lat, double lng, int n, double meters) {
        List<double[]> out = new ArrayList<>();
        // 1 度纬度约 111000 米
        double step = meters / 111_000.0;
        for (int i = 0; i < n; i++) {
            out.add(new double[]{lat + step * i, lng});
        }
        return out;
    }

    private static StayPointDetector detector() {
        return new StayPointDetector(MIN_DWELL);
    }

    // ============================================================
    // 验收标准第三条：路口般的短时低速点没有被判成停留
    // ============================================================

    @Test
    @DisplayName("时间维度约束：持续仅 60 秒的低速点群不判为停留")
    void shortLowSpeedClusterIsNotStay() {
        // 60 秒 = 12 个点，空间上完全聚成一团，但时长远低于 300 秒
        List<double[]> raw = stationaryCluster(30.657261, 104.066678, 12);
        List<StayPoint> stays = detector().detect(points(raw, T0));

        assertThat(stays)
                .as("60 秒的低速点群（等红灯场景）不应被判成停留——"
                        + "这正是实施指南强调的「DBSCAN 分不出等红灯和真停留」")
                .isEmpty();
    }

    @Test
    @DisplayName("边界：恰好 300 秒的停留能被识别（阈值含等于）")
    void exactlyAtThresholdCounts() {
        // 61 个点、间隔 5 秒 → 首尾跨度 300 秒
        List<double[]> raw = stationaryCluster(30.657261, 104.066678, 61);
        List<StayPoint> stays = detector().detect(points(raw, T0));

        assertThat(stays).hasSize(1);
        assertThat(stays.get(0).getDwellSeconds())
                .as("持续时长按首尾点时间跨度计算")
                .isEqualTo(300);
    }

    // ============================================================
    // 验收标准第二条：停留点被识别
    // ============================================================

    @Test
    @DisplayName("持续 10 分钟的静止段被识别为一个停留点")
    void longStationarySegmentDetected() {
        List<double[]> raw = stationaryCluster(30.657261, 104.066678, 121); // 10 分钟
        List<StayPoint> stays = detector().detect(points(raw, T0));

        assertThat(stays).hasSize(1);
        StayPoint s = stays.get(0);
        assertThat(s.getDwellSeconds()).isEqualTo(600);
        assertThat(s.getCenterLat()).isBetween(30.65720, 30.65732);
        assertThat(s.getCenterLng()).isBetween(104.06658, 104.06678);
        assertThat(s.getStartTime()).isEqualTo(T0);
        assertThat(s.getEndTime()).isEqualTo(T0.plusSeconds(600));
    }

    @Test
    @DisplayName("两个分离的停留点被识别为两条独立停留")
    void twoSeparateStaysDetected() {
        List<double[]> raw = new ArrayList<>();
        raw.addAll(stationaryCluster(30.657261, 104.066678, 121)); // A 点 10 分钟
        // 中间慢速移动段（每步 12 米，5 秒 12 米 = 2.4 m/s，正常步行，共 300 米）。
        // A、B 两点相距 300 米，远大于 eps=50 米，是两个独立的停留。
        // 步长 12 米 < eps，所以这段不会因空间紧凑度检查被误判成停留，
        // 只会因时长不足（20 步仅 100 秒）被时间维度过滤掉
        raw.addAll(movingLine(30.657261, 104.066678, 20, 12));
        raw.addAll(stationaryCluster(30.659961, 104.066678, 121));

        List<StayPoint> stays = detector().detect(points(raw, T0));

        assertThat(stays).hasSize(2);
        // 两个停留被独立识别，各自时长与质心都正确。
        // 偏差只来自种子锚定的固有行为：A 停留的种子锚定在 A 段末端，
        // 其 eps 邻域吃进了移动段的前 5 个点（25 秒），于是 A 的跨度为 600+25。
        // 这是真实时间跨度，不是估算误差—— dwell 一律按首尾点真实时间算。
        assertThat(stays.get(0).getDwellSeconds()).isEqualTo(625);
        assertThat(stays.get(0).getPointCount()).isEqualTo(126);
        assertThat(stays.get(1).getDwellSeconds()).isEqualTo(600);
        assertThat(stays.get(1).getPointCount()).isEqualTo(121);
        assertThat(stays.get(0).getCenterLat()).isBetween(30.65720, 30.65732);
        assertThat(stays.get(1).getCenterLat()).isBetween(30.65990, 30.66002);
        assertThat(stays.get(0).getCenterLat()).isLessThan(stays.get(1).getCenterLat());
    }

    // ============================================================
    // 验收标准第一条：漂移点被剔除
    // ============================================================

    @Test
    @DisplayName("漂移点被剔除：短时间大位移的孤立点不参与聚类")
    void driftPointsAreExcluded() {
        List<double[]> raw = stationaryCluster(30.657261, 104.066678, 121);
        // 在第 50 个点处插入一个瞬移点（跳到 500 米外，下一瞬又跳回）
        raw.add(50, new double[]{30.661800, 104.066678});

        List<TrackPoint> pts = points(raw, T0);
        List<StayPoint> stays = detector().detect(pts);

        assertThat(stays).hasSize(1);
        StayPoint s = stays.get(0);
        // 漂移点被剔除后，停留点的首尾仍是那 121 个静止点。
        // 605 = 121 个点按 5 秒间隔的跨度 600 秒 + 漂移点挤占的那 1 个间隔 5 秒：
        // 漂移点自身被剔，但它前后两点之间真实跨过了 10 秒。
        // 这正是「按真实时间跨度算 dwell」而非「点数 × 间隔」的差别。
        assertThat(s.getDwellSeconds())
                .as("漂移点被剔除，停留点按真实时间跨度计入")
                .isEqualTo(605);
        assertThat(s.getPointCount())
                .as("漂移点没有被算进停留点的质心")
                .isEqualTo(121);
    }

    @Test
    @DisplayName("连续漂移段被整体剔除，不影响真正停留的识别")
    void driftSegmentDoesNotBreakRealStay() {
        List<double[]> raw = stationaryCluster(30.657261, 104.066678, 121);
        // 尾部接一段漂移（每步跳 200 米，速度 40 m/s，远超步行）
        for (int i = 1; i <= 5; i++) {
            raw.add(new double[]{30.657261 + 0.0018 * i, 104.066678});
        }
        List<StayPoint> stays = detector().detect(points(raw, T0));

        assertThat(stays).hasSize(1);
        assertThat(stays.get(0).getDwellSeconds()).isEqualTo(600);
        assertThat(stays.get(0).getPointCount()).isEqualTo(121);
    }

    // ============================================================
    // 边界与退化输入
    // ============================================================

    @Test
    @DisplayName("点数不足或空输入不抛异常，返回空列表")
    void degenerateInputsReturnEmpty() {
        StayPointDetector d = detector();
        assertThat(d.detect(List.of())).isEmpty();
        assertThat(d.detect(List.of(new TrackPoint()))).isEmpty();

        // 坐标或时间为 null 的点应被忽略而不是 NPE
        TrackPoint bad = new TrackPoint();
        bad.setRecordTime(T0);
        assertThat(d.detect(List.of(bad, bad))).isEmpty();
    }

    @Test
    @DisplayName("整条轨迹都在缓慢移动时不产出停留点")
    void allMovingTrackHasNoStay() {
        // 全程慢速移动且单调远去，任意两个相距 10 分钟的点都不在同一个 EPS 邻域内：
        // 121 步 × 10 米 = 1210 米，远大于 eps=50 米，不会出现首尾相近的情况。
        List<double[]> raw = movingLine(30.657261, 104.066678, 121, 10);
        List<TrackPoint> pts = points(raw, T0);

        List<StayPoint> stays = detector().detect(pts);
        assertThat(stays).isEmpty();

        // 对照组：同样 121 个点若静止，就能识别出停留——
        // 证明上面为空是「在移动」导致的，不是点数不够之类的偶然原因
        List<double[]> still = stationaryCluster(30.657261, 104.066678, 121);
        assertThat(detector().detect(points(still, T0))).hasSize(1);
    }
}
