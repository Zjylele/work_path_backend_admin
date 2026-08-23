package com.workpath.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("track")
public class Track {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 移动端本地轨迹 UUID，用于上传幂等（同一用户 + clientTrackId 只入库一次） */
    private String clientTrackId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer status;

    private Double distance;

    private LocalDateTime createTime;

}
