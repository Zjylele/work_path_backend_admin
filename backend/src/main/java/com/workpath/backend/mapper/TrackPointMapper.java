package com.workpath.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workpath.backend.entity.TrackPoint;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TrackPointMapper extends BaseMapper<TrackPoint> {

    @Select("SELECT * FROM track_point WHERE track_id = #{trackId} ORDER BY record_time ASC")
    List<TrackPoint> selectByTrackId(@Param("trackId") Long trackId);

}
