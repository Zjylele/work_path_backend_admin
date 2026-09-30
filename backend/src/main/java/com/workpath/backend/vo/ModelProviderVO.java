package com.workpath.backend.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型接入配置的出参。
 *
 * apiKeyBack 只保留后 4 位，完整密钥绝不出现在任何接口响应里。
 */
@Data
@Schema(description = "模型接入配置")
public class ModelProviderVO {

    @Schema(description = "配置ID")
    private Long id;

    @Schema(description = "用途：CHAT/语言模型，EMBEDDING=向量化，GEO=逆地理编码")
    private String purpose;

    @Schema(description = "服务商标注")
    private String providerName;

    @Schema(description = "接口地址")
    private String baseUrl;

    @Schema(description = "模型标识")
    private String modelName;

    @Schema(description = "密钥后4位，如 ****3d02")
    private String apiKeyBack;

    @Schema(description = "附加配置 JSON")
    private String extraConfig;

    @Schema(description = "是否启用")
    private Integer enabled;

    @Schema(description = "是否该用途当前使用的配置：1=是")
    private Integer selected;

    @Schema(description = "连通性：0=未验证，1=通过，2=失败")
    private Integer status;

    @Schema(description = "最近一次连接测试的失败原因")
    private String lastError;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
