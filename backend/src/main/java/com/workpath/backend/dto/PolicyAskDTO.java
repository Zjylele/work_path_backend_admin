package com.workpath.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 制度条款问答入参。
 *
 * <p>question 为必填：空问题不该消耗一次 embedding + 检索 + 模型调用的开销，
 * 而应该直接被 Bean Validation 拦在入口。topK 可空，服务端给默认值。</p>
 */
@Data
@Schema(description = "制度条款问答参数")
public class PolicyAskDTO {

    @NotBlank(message = "问题不能为空")
    @Schema(description = "自然语言问题", requiredMode = Schema.RequiredMode.REQUIRED)
    private String question;

    @Schema(description = "作为依据的条款条数，默认 5")
    private Integer topK;
}
