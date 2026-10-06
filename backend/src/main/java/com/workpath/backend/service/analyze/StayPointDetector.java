package com.workpath.backend.service.analyze;

import com.workpath.backend.entity.TrackPoint;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * W4 的停留点识别：漂移剔除 → 种子点锚定聚类 → 空间紧凑度 → 时间维度过滤。
 *
 * <p>四步的顺序与取舍都有理由，尤其是第二步与经典 DBSCAN 的<b>有意偏离</b>：</p>
 *
 * <p><b>1. 先剔漂移。</b>漂移是短时间大位移的孤立点，若先聚类，它会把两个本应
 * 分开的停留点连成一个，于是「两个客户点」被误判成「一次长时间停留」。
 * 比较基准取<b>最后一个被接受的点</b>而非前一个原始点——否则漂移点跳出去又跳回来，
 * 返回那次位移同样超过速度阈值，会把紧跟的合法点一起误剔。</p>
 *
 * <p><b>2. 聚类用种子点锚定，不做密度可达的传递扩展。</b>这是本文最关键的设计。
 * 第1章 1.3.5 选 DBSCAN 的两个理由（无需预设簇数、能剔噪声）确实成立，
 * 但标准的传递式 DBSCAN 有一个对轨迹致命的问题：<b>连续慢走会被串成一个巨簇</b>。
 * 以步行 10 米/5 秒为例，每个点的邻域内都有前后若干点、都是核心点，
 * 密度可达一路传递，10 分钟走出 1210 米，最后被判成<b>一次持续 10 分钟的停留</b>，
 * 质心落在人根本不在的地方。</p>
 *
 * <p>根因在于：<b>慢走与原地站在局部密度上完全相同</b>——都是每 5 秒一个点、
 * 点与点相距几米到几十米。凡是只看局部密度的算法都分不出这两者，
 * 唯一能区分的是<b>整组的空间跨度</b>：停留的跨度是米级，穿行是百米到千米级。
 * 所以这里每个种子只收自己 eps 邻域内的点（组内跨度天然不超过 2×eps），
 * 再用显式的紧凑度检查兜住边界。</p>
 *
 * <p><b>3. 空间紧凑度。</b>组内点到质心的最大距离不超过 eps。
 * 防止「很慢地走了很远」被当成停留。</p>
 *
 * <p><b>4. 时间维度过滤。</b>这是「等红灯/路口等待 vs 真停留」的<b>唯一防线</b>，
 * 实施指南 W4 明确要求：只有持续时长 ≥ 阈值（建议 300 秒起）的簇才算停留。
 * 单纯空间聚类分不出两者——等红灯时车辆密集停在路口，空间上也是一团。</p>
 */
public class StayPointDetector {

    /** 空间邻域半径，米。室外 GPS 精度 + 原地小活动的合理上界。 */
    private static final double DEFAULT_EPS_METERS = 50.0;

    /** 成为有效停留的最少点数。低于此的当噪声丢弃（保留"能剔噪声"这个特性）。 */
    private static final int DEFAULT_MIN_PTS = 4;

    /**
     * 漂移判定：相邻点速度超过此值即视为漂移，m/s。
     *
     * <p>取值依据：骑车约 5-6 m/s，冲刺瞬时峰值也就 10 m/s 上下，
     * 而 GPS 跳变往往是几十甚至上百 m/s。25 m/s 能放过一切真实运动。</p>
     */
    private static final double DRIFT_SPEED_MS = 25.0;

    /** 1 度纬度对应的米数，用于说明 eps 的量级。 */
    private static final double METERS_PER_DEGREE = 111_320.0;

    private final int minDwellSeconds;

    private final double epsMeters;

    private final int minPts;

    /** 用默认 eps/minPts，停留阈值必填。 */
    public StayPointDetector(int minDwellSeconds) {
        this(minDwellSeconds, DEFAULT_EPS_METERS, DEFAULT_MIN_PTS);
    }

    public StayPointDetector(int minDwellSeconds, double epsMeters, int minPts) {
        if (minDwellSeconds <= 0) {
            throw new IllegalArgumentException("停留时长阈值必须为正，收到 " + minDwellSeconds);
        }
        if (epsMeters <= 0 || minPts <= 0) {
            throw new IllegalArgumentException("epsMeters 与 minPts 必须为正");
        }
        this.minDwellSeconds = minDwellSeconds;
        this.epsMeters = epsMeters;
        this.minPts = minPts;
    }

    /**
     * 识别轨迹中的停留点。
     *
     * @param points 轨迹点，可为空、可含 null 字段；内部会按时间升序重排
     * @return 停留点列表，按开始时间升序；没有任何停留时返回空列表
     */
    public List<StayPoint> detect(List<TrackPoint> points) {
        List<TrackPoint> valid = sanitize(points);
        if (valid.size() < minPts) {
            // 连一个有效组都凑不出，直接判定为无停留
            return List.of();
        }

        List<TrackPoint> cleaned = removeDrift(valid);
        if (cleaned.size() < minPts) {
            return List.of();
        }

        boolean[] grouped = new boolean[cleaned.size()];
        List<StayPoint> stays = new ArrayList<>();

        for (int seed = 0; seed < cleaned.size(); seed++) {
            if (grouped[seed]) {
                continue;
            }
            // 收种子 eps 邻域内的全部点（含种子自身）。不传递扩展。
            List<TrackPoint> group = new ArrayList<>();
            for (int j = seed; j < cleaned.size(); j++) {
                if (!grouped[j] && distanceMeters(cleaned.get(seed), cleaned.get(j)) <= epsMeters) {
                    group.add(cleaned.get(j));
                    grouped[j] = true;
                }
            }

            if (group.size() < minPts) {
                // 噪声：孤立的跳变残点，既不算停留也不参与质心
                continue;
            }
            if (!spatiallyCompact(group)) {
                // 跨度超过 eps：这是穿行而过，不是停留
                continue;
            }
            StayPoint stay = toStayPoint(group);
            if (stay.getDwellSeconds() >= minDwellSeconds) {
                stays.add(stay);
            }
        }

        stays.sort(Comparator.comparing(StayPoint::getStartTime));
        return stays;
    }

    /** 丢掉坐标或时间为 null、以及经纬度非法的点，并按时间升序排好。 */
    private List<TrackPoint> sanitize(List<TrackPoint> points) {
        if (points == null) {
            return List.of();
        }
        List<TrackPoint> out = new ArrayList<>(points.size());
        for (TrackPoint p : points) {
            if (p == null || p.getLatitude() == null || p.getLongitude() == null
                    || p.getRecordTime() == null) {
                continue;
            }
            if (!isValidCoordinate(p.getLatitude(), p.getLongitude())) {
                continue;
            }
            out.add(p);
        }
        out.sort(Comparator.comparing(TrackPoint::getRecordTime));
        return out;
    }

    /**
     * 经纬度是否落在合法范围内。
     *
     * <p>必须在这一步拦掉，不能指望后面的漂移剔除：漂移判定比的是「与前一个
     * 被接受点的速度」，而首点没有比较对象、会被无条件保留——一个 (999,999)
     * 的首点就此成为后续所有点的基准，每个正常点相对它都是天文速度，会被逐个
     * 判成漂移，整条轨迹一个点都不剩。</p>
     *
     * <p>顺带排除 (0, 0)：几内亚湾海上的这个点在几何上合法，但设备拿不到定位时
     * 普遍上报它，属于「没有坐标」的另一种写法。</p>
     */
    private static boolean isValidCoordinate(double lat, double lng) {
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
            return false;
        }
        return !(lat == 0.0 && lng == 0.0);
    }

    /**
     * 以下四个取值暴露出来，是为了让判定轨迹（analysis_trace）能记录**真实**
     * 参数而不是手抄一份副本——副本会在调参后与实现失配，而 trace 的全部意义
     * 就是「当时是按什么标准判的」。
     */
    public int getMinDwellSeconds() {
        return minDwellSeconds;
    }

    public double getEpsMeters() {
        return epsMeters;
    }

    public int getMinPts() {
        return minPts;
    }

    public static double getDriftSpeedMs() {
        return DRIFT_SPEED_MS;
    }

    /**
     * 剔除漂移点。
     *
     * <p>判定用「相邻点速度」而非「离前一点距离」：固定位移阈值会把停留后的
     * 正常起步也误判——人在原地站了 10 分钟，下一步走开 50 米是正常移动，
     * 但那 50 米远大于 GPS 漂移。速度自带时间分母，能区分这两者。</p>
     */
    private List<TrackPoint> removeDrift(List<TrackPoint> sorted) {
        List<TrackPoint> kept = new ArrayList<>(sorted.size());
        TrackPoint lastAccepted = null;
        for (TrackPoint cur : sorted) {
            if (lastAccepted == null) {
                // 首点无从比较，保留——丢它会让后续所有点失去基准
                kept.add(cur);
                lastAccepted = cur;
                continue;
            }
            double seconds = secondsBetween(lastAccepted.getRecordTime(), cur.getRecordTime());
            if (seconds <= 0) {
                // 同一时刻重复上报：保留首条，其余丢掉，避免距离除以 0
                continue;
            }
            double speed = distanceMeters(lastAccepted, cur) / seconds;
            if (speed > DRIFT_SPEED_MS) {
                // 漂移：不保留，也<b>不更新 lastAccepted</b>。
                // 这一点是关键——漂移点跳出去又跳回来时，返回那次位移同样是
                // 一次大跳变，若拿它当基准就会把紧跟的合法点误剔。
                continue;
            }
            kept.add(cur);
            lastAccepted = cur;
        }
        return kept;
    }

    /**
     * 组内空间紧凑度：点到质心的最大距离不超过 eps。
     *
     * <p>用意是兜住「很慢地走很远」这一种残留情形：种子锚定已把组内跨度
     * 限制在 2×eps 内，若这一组真的横跨了整个邻域而不是聚在一处，
     * 说明人在经过这里而非停在这里。</p>
     */
    private boolean spatiallyCompact(List<TrackPoint> group) {
        double latSum = 0;
        double lngSum = 0;
        for (TrackPoint p : group) {
            latSum += p.getLatitude();
            lngSum += p.getLongitude();
        }
        int n = group.size();
        double centerLat = latSum / n;
        double centerLng = lngSum / n;

        TrackPoint center = new TrackPoint();
        center.setLatitude(centerLat);
        center.setLongitude(centerLng);
        for (TrackPoint p : group) {
            if (distanceMeters(center, p) > epsMeters) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把一组点转成 StayPoint。
     *
     * <p>duration 用簇内最早点与最晚点的<b>真实时间跨度</b>，不拿「点数 × 间隔」
     * 估算——后者在漂移点被剔除后会凭空少几秒，恰好 300 秒的边界用例就会失败。</p>
     */
    private StayPoint toStayPoint(List<TrackPoint> cluster) {
        double latSum = 0;
        double lngSum = 0;
        LocalDateTime start = null;
        LocalDateTime end = null;
        for (TrackPoint p : cluster) {
            latSum += p.getLatitude();
            lngSum += p.getLongitude();
            LocalDateTime t = p.getRecordTime();
            if (start == null || t.isBefore(start)) {
                start = t;
            }
            if (end == null || t.isAfter(end)) {
                end = t;
            }
        }
        int n = cluster.size();
        return new StayPoint(
                latSum / n, lngSum / n, start, end,
                (int) secondsBetween(start, end), n);
    }

    private static double secondsBetween(LocalDateTime a, LocalDateTime b) {
        return Duration.between(a, b).toMillis() / 1000.0;
    }

    /**
     * 两点间距离，米。用 Haversine，够精确也让代码可读；
     * 系统内的比较只是「离 50 米近还是远」，不需要 Vincenty 那级精度。
     */
    private static double distanceMeters(TrackPoint a, TrackPoint b) {
        final double R = 6_371_000.0;
        double dLat = Math.toRadians(b.getLatitude() - a.getLatitude());
        double dLng = Math.toRadians(b.getLongitude() - a.getLongitude());
        double lat1 = Math.toRadians(a.getLatitude());
        double lat2 = Math.toRadians(b.getLatitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.sqrt(h));
    }
}
