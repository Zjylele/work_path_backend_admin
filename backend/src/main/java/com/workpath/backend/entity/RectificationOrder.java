package com.workpath.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 整改工单（M5-W7）。状态机：0=待处理, 1=处理中, 2=已闭环, 3=申诉中。
 *
 * <p>状态机的两条设计约束（实施指南 W7，第2章 2.2 节）：</p>
 * <ul>
 *   <li>员工不能自行关闭工单——只能提交处理说明与申诉，闭环一律由管理员触发；</li>
 *   <li>申诉中的工单原结论暂不生效——这就是单独设 3 状态而不是打标的原因。</li>
 * </ul>
 *
 * <p>{@code uk_order_check} 保证一条核查结论至多一张工单。</p>
 */
@Data
@TableName("rectification_order")
public class RectificationOrder {

    @TableId(type = IdType.AUTO)
    private Long orderId;

    /** 来源核查结论 */
    private Long checkId;

    /** 责任员工 */
    private Long userId;

    /** 整改内容 */
    private String content;

    /** 状态：0=待处理, 1=处理中, 2=已闭环, 3=申诉中 */
    private Integer status;

    /** 下发人（自动生成时为确认事件的管理员，人工补发时为操作管理员） */
    private Long assignBy;

    private LocalDateTime assignTime;

    /** 员工处理说明（0→1 时提交） */
    private String handleNote;

    private LocalDateTime handleTime;

    /** 申诉理由（0→3 时提交） */
    private String appealReason;

    /** 管理员复核意见（申诉复核或闭环备注） */
    private String reviewNote;

    private LocalDateTime closeTime;

    private LocalDateTime createTime;

    /** 非持久化：责任员工用户名，列表展示用 */
    @TableField(exist = false)
    private String username;
}
