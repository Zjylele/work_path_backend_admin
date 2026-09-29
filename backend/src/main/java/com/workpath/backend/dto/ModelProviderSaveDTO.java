package com.workpath.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 模型接入配置的保存入参。
 *
 * apiKey 允许为空字符串，表示"不修改原有密钥"——这样前端不回传完整密钥也能保存其他字段。
 * 若 apiKey 为非空字符串则视为更新密钥。
 */
@Data
@Schema(description = "模型接入配置保存参数")
public class ModelProviderSaveDTO {

    @Schema(description = "用途：CHAT/EMBEDDING/GEO", requiredMode = Schema.RequiredMode.REQUIRED)
    private String purpose;

    @Schema(description = "服务商标注")
    private String providerName;

    @Schema(description = "接口地址")
    private String baseUrl;

    @Schema(description = "模型标识")
    private String modelName;

    @Schema(description = "接口密钥；空字符串表示不修改原密钥")
    private String apiKey;

    @Schema(description = "附加配置 JSON")
    private String extraConfig;

    @Schema(description = "是否启用：1=启用，0=停用")
    private Integer enabled;
}
