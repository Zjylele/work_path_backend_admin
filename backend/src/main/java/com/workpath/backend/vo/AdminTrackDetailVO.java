package com.workpath.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminTrackDetailVO {

    private Long id;

    private String username;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer status;

    private Double distance;

    private List<TrackPointVO> points;

}
