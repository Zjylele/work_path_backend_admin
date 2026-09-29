package com.workpath.backend.service.analyze;

import java.time.LocalDateTime;

/**
 * 一段被识别为「停留」的轨迹点群，附带给 W4 落 work_event 表用的全部字段。
 *
 * <p>纯数据载体，不含算法。之所以单独建类而不是复用 WorkEvent：
 * 识别结果是算法层的中间产物，落哪些列、confirm_status 填什么
 * 是 Service 层的事；两者耦合在一起会让算法没法脱离 Spring 测试。</p>
 */
public class StayPoint {

    /** 停留点质心纬度 */
    private final double centerLat;

    /** 停留点质心经度 */
    private final double centerLng;

    /** 簇内最早点的记录时间 */
    private final LocalDateTime startTime;

    /** 簇内最晚点的记录时间 */
    private final LocalDateTime endTime;

    /** 持续秒数，等于 startTime 到 endTime 的跨度（不是点数 × 间隔） */
    private final int dwellSeconds;

    /** 参与该停留的轨迹点个数（漂移点已在识别前剔除） */
    private final int pointCount;

    public StayPoint(double centerLat, double centerLng,
                     LocalDateTime startTime, LocalDateTime endTime,
                     int dwellSeconds, int pointCount) {
        this.centerLat = centerLat;
        this.centerLng = centerLng;
        this.startTime = startTime;
        this.endTime = endTime;
        this.dwellSeconds = dwellSeconds;
        this.pointCount = pointCount;
    }

    public double getCenterLat() {
        return centerLat;
    }

    public double getCenterLng() {
        return centerLng;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public int getDwellSeconds() {
        return dwellSeconds;
    }

    public int getPointCount() {
        return pointCount;
    }

    @Override
    public String toString() {
        return "StayPoint{center=(" + centerLat + "," + centerLng + "), dwell=" + dwellSeconds
                + "s, points=" + pointCount + "}";
    }
}
