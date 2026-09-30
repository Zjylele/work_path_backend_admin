package com.workpath.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 合规核查结论（M5-W6）。
 *
 * <p>result 取值与 init_ai.sql 的列注释一致：0=合规，1=疑似违规（条款回查通过），
 * 2=引用无效待复核。「疑似」二字是论文「管理辅助工具」定位的延续——
 * 即使条款回查通过，结论也只是疑似违规，处置走工单由人闭环。</p>
 *
 * <p>条款回查（W6 的创新点）发生在模型输出之后、本表入库之前：
 * {@code citedClauseCode} 必须真实存在于规则关联的文档，回查不通过时
 * result=2 转人工复核，无效引用没有机会进入工单流程。</p>
 */
@Data
@TableName("compliance_check")
public class ComplianceCheck {

    @TableId(type = IdType.AUTO)
    private Long checkId;

    /** 被核查员工 */
    private Long userId;

    /** 核查日期（事件开始时间所在日） */
    private LocalDate workDate;

    /** 触发核查的工作事件；ABNORMAL 等轨迹级核查为 NULL */
    private Long workEventId;

    /** 命中的规则 */
    private Long ruleId;

    /** 结果：0=合规, 1=疑似违规, 2=引用无效待复核 */
    private Integer result;

    /** 客观证据：具体数值或关联 ID，由规则引擎产出（非 LLM） */
    private String evidence;

    /** LLM 给出的违规性质说明；模型不可用时为 NULL */
    private String aiReason;

    /** LLM 引用的条款编号；回查通过才值得看，不通过时它只是模型的说法 */
    private String citedClauseCode;

    /** 回查结果：1=通过, 0=引用无效（含模型未给出引用） */
    private Integer clauseVerified;

    private LocalDateTime createTime;

    /** 非持久化：规则编码/名称，列表展示用 */
    @TableField(exist = false)
    private String ruleCode;

    @TableField(exist = false)
    private String ruleName;

    /** 非持久化：员工用户名，列表展示用 */
    @TableField(exist = false)
    private String username;
}
