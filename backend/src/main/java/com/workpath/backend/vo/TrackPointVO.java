package com.workpath.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TrackPointVO {

    private Double latitude;

    private Double longitude;

    private LocalDateTime recordTime;

}
