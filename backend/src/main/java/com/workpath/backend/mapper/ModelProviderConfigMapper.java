package com.workpath.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workpath.backend.entity.ModelProviderConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ModelProviderConfigMapper extends BaseMapper<ModelProviderConfig> {

    /**
     * 查某用途「当前使用」的配置（selected=1）。每用途可挂多条配置，
     * 选中的至多一条；LIMIT 1 兜底历史脏数据，避免 TooManyResults。
     */
    @Select("SELECT * FROM model_provider_config WHERE purpose = #{purpose} AND selected = 1 LIMIT 1")
    ModelProviderConfig selectSelectedByPurpose(@Param("purpose") String purpose);
}
