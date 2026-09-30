package com.workpath.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 合规规则（M5-W5）。
 *
 * <p>规则判定由程序完成、不调 LLM（实施指南 W5 的硬性分工）：数值比较
 * 确定、可复现、可测试；LLM 只在命中后生成「违规性质说明」。</p>
 *
 * <p>{@code threshold} 的含义随 {@code ruleType} 变化：
 * DURATION=分钟，FREQUENCY=次/天，RANGE=米，ABNORMAL=无。</p>
 */
@Data
@TableName("compliance_rule")
public class ComplianceRule {

    @TableId(type = IdType.AUTO)
    private Long ruleId;

    /** 规则编码，唯一，如 DUR-30MIN */
    private String ruleCode;

    private String ruleName;

    /** 类型：DURATION=时长, FREQUENCY=频次, RANGE=范围, ABNORMAL=异常 */
    private String ruleType;

    /** 阈值，含义依 ruleType 而定 */
    private Double threshold;

    /**
     * 附加配置（JSON）。驻地类规则在这里给中心与半径：
     * {@code {"station_lat":30.548,"station_lng":104.062,"station_radius_m":500}}。
     * user 表没有驻地字段，报备驻地只能挂在规则上。
     */
    private String extraConfig;

    /** 关联的制度条款 ID（可追溯性外键），可空。条款回查以此定位文档范围 */
    private Long policyClauseId;

    /** 1=启用，0=停用 */
    private Integer enabled;

    private LocalDateTime createTime;

    /** 非持久化：关联条款编号与内容摘要，规则列表展示用（可追溯性可读化） */
    @TableField(exist = false)
    private String clauseCode;

    @TableField(exist = false)
    private String clauseContent;
}
