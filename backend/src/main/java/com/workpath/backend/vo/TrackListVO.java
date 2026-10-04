package com.workpath.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TrackListVO {

    private Long id;

    private String name;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer status;

    private Double distance;

    private Long pointCount;

    private LocalDateTime createTime;

}
