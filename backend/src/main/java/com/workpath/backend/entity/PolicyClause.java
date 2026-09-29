package com.workpath.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 制度条款。切分的结果单元，也是 M5 规则关联与 M4 检索的最小对象。
 *
 * <p>三个字段承载了三个必须可追溯的设计意图（第2章 2.3.6、实施指南 W1）：</p>
 * <ul>
 *   <li>{@code codeSource}：编号来自原文正则（1）还是按序生成（2）。
 *       若编号本身是生成的，调用方必须知道，否则引用时会被误导；</li>
 *   <li>{@code vectorModel}：记录生成向量的模型。换 embedding 模型后旧向量
 *       全部作废（不同模型的向量空间不兼容），据此判断哪些条款要重算；</li>
 *   <li>{@code vectorData}：JSON 数组文本，相似度在应用层算。</li>
 * </ul>
 */
@Data
@TableName("policy_clause")
public class PolicyClause {

    @TableId(type = IdType.AUTO)
    private Long clauseId;

    private Long docId;

    /** 条款编号，如 3.2.1；原文没有编号时为 AUTO-0001 这类生成值 */
    private String clauseCode;

    /** 条款在文档内的顺序号，从 0 连续递增 */
    private Integer clauseIndex;

    /** 编号来源：1=原文提取，2=按序生成 */
    private Integer codeSource;

    private String content;

    /** 语义向量 JSON 数组；未向量化或向量化失败的条款为 null，检索时跳过 */
    private String vectorData;

    private String vectorModel;

    /** 状态：1=启用，0=停用 */
    private Integer status;

    private LocalDateTime createTime;

    /** 非持久化：来源文档标题，检索结果里带上，便于引用可追溯 */
    @TableField(exist = false)
    private String docTitle;

    /** 非持久化：来源文档版本 */
    @TableField(exist = false)
    private String docVersion;

    /** 非持久化：检索时算出的余弦相似度，仅作为返回值，不落库 */
    @TableField(exist = false)
    private Double score;
}
