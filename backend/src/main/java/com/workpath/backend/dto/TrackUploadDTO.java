package com.workpath.backend.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TrackUploadDTO {

    /** 移动端本地轨迹 UUID（幂等键，可选；重复上传时后端直接返回已有 id） */
    private String clientTrackId;

    /** 轨迹名称（移动端开始记录时填写；旧版客户端不传，按 null 入库、展示为「未命名」） */
    @Size(max = 100, message = "轨迹名称长度不能超过 100 字")
    private String name;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    @NotNull(message = "状态不能为空")
    private Integer status;

    private Double distance;

    @NotEmpty(message = "坐标点不能为空")
    private List<TrackPointDTO> points;

}
