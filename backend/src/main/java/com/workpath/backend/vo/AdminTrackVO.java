package com.workpath.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminTrackVO {

    private Long id;

    private String username;

    private Long userId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer status;

    private Double distance;

    private Long pointCount;

    private LocalDateTime createTime;

}
