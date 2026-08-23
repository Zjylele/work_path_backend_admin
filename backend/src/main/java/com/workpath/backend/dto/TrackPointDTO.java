package com.workpath.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TrackPointDTO {

    @NotNull(message = "纬度不能为空")
    private Double latitude;

    @NotNull(message = "经度不能为空")
    private Double longitude;

    @NotNull(message = "记录时间不能为空")
    private LocalDateTime recordTime;

}
