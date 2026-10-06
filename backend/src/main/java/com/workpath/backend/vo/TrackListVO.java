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

    /**
     * 移动端本地轨迹 UUID。
     *
     * <p>手机端的轨迹列表读的是本地 sqflite，而分析状态只存在于服务端，
     * 两边靠这个 UUID 对上——它正是上传时的幂等键。</p>
     */
    private String clientTrackId;

    /**
     * AI 分析状态：0=未分析，1=分析中，2=已完成，3=分析失败。
     * 移动端轨迹列表据此显示分析进度。
     */
    private Integer analysisStatus;

    private LocalDateTime createTime;

}
