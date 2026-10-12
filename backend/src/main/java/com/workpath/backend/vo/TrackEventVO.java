package com.workpath.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作事件·员工侧视图。
 *
 * <p>移动端「轨迹详情」要展示这条轨迹的语义标注结果，接口是
 * {@code GET /api/track/{id}/events}。这里刻意不直接返回 WorkEvent 实体：
 * 实体里带着复核人（confirm_by）、判定过程原文（analysis_trace）这类管理端的
 * 复核依据，它们不该随员工接口出去。字段只保留员工看得懂、也理应看到的部分。</p>
 */
@Data
public class TrackEventVO {

    private Long eventId;

    /** 事件类型：MOVE=移动，STAY=停留 */
    private String eventType;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 停留点质心纬度 */
    private Double centerLat;

    /** 停留点质心经度 */
    private Double centerLng;

    /** 停留时长，秒 */
    private Integer dwellSeconds;

    /** 地点名称。逆地理编码失败时为 null */
    private String poiName;

    /** 语义标注（LLM 输出），如 客户拜访 / 用餐 */
    private String semanticLabel;

    /** 标注置信度 0-1 */
    private Double confidence;

    /** 模型给出的判定理由 */
    private String aiReason;

    /** 复核状态：0=待确认 1=已确认 2=已驳回 */
    private Integer confirmStatus;
}
