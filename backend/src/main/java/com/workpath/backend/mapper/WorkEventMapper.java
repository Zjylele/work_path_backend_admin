package com.workpath.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workpath.backend.entity.WorkEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkEventMapper extends BaseMapper<WorkEvent> {
}
