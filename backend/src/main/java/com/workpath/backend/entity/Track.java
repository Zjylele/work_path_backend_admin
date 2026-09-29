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

    /**
     * AI 分析状态：0=未分析，1=分析中，2=已完成，3=分析失败。
     *
     * <p>成员名刻意带 analysis 前缀而不叫 status——因为 Track 已经有一个
     * status 字段表示「轨迹是否已结束」，两个含义完全不同的状态重名会让
     * 读代码的人（以及未来的自己）搞混哪一个是分析状态。</p>
     *
     * <p>这一列是「先落库再异步分析」架构的支点：上传接口只写 0，
     * 分析任务把它推到 1/2/3。状态全程可见，积压与失败都能从库里直接看出来。</p>
     */
    private Integer analysisStatus;

}
