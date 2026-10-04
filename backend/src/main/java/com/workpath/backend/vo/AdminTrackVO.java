package com.workpath.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminTrackVO {

    private Long id;

    private String name;

    private String username;

    private Long userId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer status;

    private Double distance;

    private Long pointCount;

    private LocalDateTime createTime;

    /**
     * AI 分析状态：0=未分析，1=分析中，2=已完成，3=分析失败。
     *
     * <p>对应 TrackAnalysisService 的四个常量，值必须一致——
     * 这里刻意不引用常量，是为了让 VO 不依赖 service 层
     * （VO 是出参契约，service 是实现细节）。</p>
     */
    private Integer analysisStatus;

}
