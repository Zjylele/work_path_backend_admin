package com.workpath.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 模型接入配置。
 *
 * 每个用途（CHAT / EMBEDDING / GEO）在表中只有一条生效记录，由 uk_purpose 唯一键保证。
 * apiKey 是敏感字段，任何对外返回都必须脱敏，见 ModelProviderService#mask。
 */
@Data
@TableName("model_provider_config")
public class ModelProviderConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用途：CHAT=语言模型，EMBEDDING=向量化，GEO=逆地理编码 */
    private String purpose;

    /** 服务商标注，如 deepseek / siliconflow / amap */
    private String providerName;

    /** 接口地址，如 https://api.deepseek.com */
    private String baseUrl;

    /** 模型标识，如 deepseek-flash / BAAI/bge-m3 */
    private String modelName;

    /** 接口密钥。入库存储，接口层一律脱敏 */
    private String apiKey;

    /** 附加配置 JSON，如 {"dimension":1024} */
    private String extraConfig;

    /** 是否启用：1=启用，0=停用 */
    private Integer enabled;

    /** 连通性：0=未验证，1=通过，2=失败 */
    private Integer status;

    /** 最近一次连接测试的失败原因 */
    private String lastError;

    private LocalDateTime updateTime;

    private LocalDateTime createTime;
}
