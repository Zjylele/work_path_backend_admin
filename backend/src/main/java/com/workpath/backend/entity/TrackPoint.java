package com.workpath.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("track_point")
public class TrackPoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long trackId;

    private Double latitude;

    private Double longitude;

    private LocalDateTime recordTime;

}
