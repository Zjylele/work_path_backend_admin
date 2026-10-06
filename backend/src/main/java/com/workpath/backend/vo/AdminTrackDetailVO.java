package com.workpath.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminTrackDetailVO {

    private Long id;

    private String name;

    private String username;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer status;

    private Double distance;

    /**
     * AI 分析状态：0=未分析，1=分析中，2=已完成，3=分析失败。
     *
     * <p>详情页需要它来区分空态：「还没分析」与「分析完了确实没有停留」
     * 是两回事，只有后者才能说成一条业务结论。</p>
     */
    private Integer analysisStatus;

    private List<TrackPointVO> points;

}
