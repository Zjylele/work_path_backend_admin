package com.workpath.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 制度文档预览出参。
 *
 * <p>{@code source} 是这套结构的要害：文档表加 content 列之前上传的存量
 * 文档没有原文，预览只能拿条款拼合——两条路径的内容在"是否原文档形态"
 * 上不等价，接口必须如实标注来源，前端据此给用户提示，不假装拼合结果是原文。</p>
 */
@Data
public class PolicyDocumentPreviewVO {

    /** 来源：原文（加 content 列之后上传的文档） */
    public static final String SOURCE_ORIGINAL = "original";

    /** 来源：条款拼合（加列前的存量文档，content 为 null） */
    public static final String SOURCE_CLAUSES = "clauses";

    private Long docId;

    private String title;

    private String version;

    private String category;

    private LocalDateTime createTime;

    /** 预览内容：原文或条款拼合结果 */
    private String content;

    /** 内容来源：original=原文，clauses=条款拼合 */
    private String source;
}
