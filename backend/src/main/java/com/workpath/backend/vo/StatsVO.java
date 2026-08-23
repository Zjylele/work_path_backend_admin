package com.workpath.backend.vo;

import lombok.Data;

@Data
public class StatsVO {

    private Long userCount;

    private Long trackCount;

    private Long todayTrackCount;

    private Long todayUserCount;

}
