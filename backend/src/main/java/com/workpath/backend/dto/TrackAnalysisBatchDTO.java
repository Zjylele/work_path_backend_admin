package com.workpath.backend.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class TrackAnalysisBatchDTO {

    @NotEmpty(message = "请选择需要分析的轨迹")
    @Size(max = 10, message = "每批最多分析 10 条轨迹")
    private List<Long> trackIds;
}
