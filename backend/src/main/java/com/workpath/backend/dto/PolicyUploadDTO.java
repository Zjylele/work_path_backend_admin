package com.workpath.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 制度文档上传入参。
 *
 * <p>title + content 一次提交，服务端切分条款、算向量后落库。
 * 这里刻意不收 file 上传——课程场景下制度原文以文本粘贴为主，
 * 且省掉文件解析依赖（PDF/Word 各一套解析器）能让切分质量这件事
 * 真正可验证，而不是被解析器的噪声掩盖。</p>
 */
@Data
@Schema(description = "制度文档上传参数")
public class PolicyUploadDTO {

    @Schema(description = "文档标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Schema(description = "分类，如 考勤/行为规范")
    private String category;

    @Schema(description = "制度原文，服务端按条款编号切分", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;
}
