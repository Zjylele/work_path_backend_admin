package com.workpath.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workpath.backend.entity.ModelProviderConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ModelProviderConfigMapper extends BaseMapper<ModelProviderConfig> {

    /**
     * 按用途查单条配置。purpose 上有唯一键，理论上最多一条。
     * 用 LIMIT 1 兜底，避免历史脏数据导致 TooManyResults。
     */
    @Select("SELECT * FROM model_provider_config WHERE purpose = #{purpose} LIMIT 1")
    ModelProviderConfig selectByPurpose(@Param("purpose") String purpose);
}
