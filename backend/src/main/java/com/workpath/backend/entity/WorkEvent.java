package com.workpath.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作事件。AI 分析的最终产出：轨迹经过停留点识别与语义标注后得到的事件。
 *
 * <p>字段名与 init_ai.sql 的既有 work_event 表逐字对齐（event_desc、duration_seconds
 * 这类"看起来更顺眼"的改名会让 INSERT 直接报 Unknown column——实测踩过）。</p>
 *
 * <p>{@code confirmStatus} 是这套设计的要害：第1章 1.1.2 与第2章 2.3.4 都把
 * 系统定位为「管理辅助工具」而非「执法依据」，AI 结论必须以疑似、待人工复核的
 * 形式输出。confirm_status=0 就是这个机制在数据模型上的落点——没有它，
 * AI 结论会直接变成管理依据，与论文里反复强调的定位直接冲突。</p>
 */
@Data
@TableName("work_event")
public class WorkEvent {

    @TableId(type = IdType.AUTO)
    private Long eventId;

    private Long trackId;

    private Long userId;

    /** 事件类型，实际取值见表注释：MOVE=移动，STAY=停留 */
    private String eventType;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 停留点质心纬度 */
    private Double centerLat;

    /** 停留点质心经度 */
    private Double centerLng;

    /** 停留时长，秒 */
    private Integer dwellSeconds;

    /** 地点名称。逆地理编码失败时留 NULL，不影响事件本身 */
    private String poiName;

    /** 语义标注（LLM 输出），如 客户拜访 / 用餐 */
    private String semanticLabel;

    /** 标注置信度 0-1 */
    private Double confidence;

    /** LLM 判定理由 */
    private String aiReason;

    /**
     * 人工复核状态：0=待确认，1=已确认，2=已驳回。
     *
     * <p>写 0 由服务端负责，不由前端传——否则调用方可以把未复核的结果
     * 直接标成已确认。</p>
     */
    private Integer confirmStatus;

    private Long confirmBy;

    private LocalDateTime confirmTime;

    private LocalDateTime createTime;

    /**
     * 非持久化：所属员工用户名，列表展示用。
     */
    @TableField(exist = false)
    private String username;
}
