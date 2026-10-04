package com.workpath.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 仪表盘最近动态。
 *
 * <p>四块面板一次请求取齐（轨迹 / 待复核工作事件 / 操作日志 / 制度文档，
 * 各取最新 10 条）。与 {@link StatsVO} 的职责切分：StatsVO 是聚合数字，
 * 本 VO 是列表条目——分开后两者各自演进，互不牵动契约。</p>
 *
 * <p>嵌套条目类只放面板要用的字段，不直接暴露实体：日志的 detail 是完整
 * 返回的，「最多 10 个字、超出省略号」属于前端展示规则，不在数据层截断——
 * 截断后的数据没法还原，将来列表页要复用时会被迫再加接口。</p>
 */
@Data
public class DashboardRecentVO {

    private List<RecentTrack> recentTracks;

    private List<RecentEvent> pendingEvents;

    private List<RecentLog> recentLogs;

    private List<RecentDocument> recentDocuments;

    /** 最近新增轨迹条目。展示名由前端按「{username}的轨迹（{name}）」组合，name 为空显示「未命名」。 */
    @Data
    public static class RecentTrack {
        private Long id;
        private String name;
        private String username;
        private LocalDateTime startTime;
        private LocalDateTime createTime;
    }

    /** 待复核工作事件条目（confirm_status=0 的最近条目）。 */
    @Data
    public static class RecentEvent {
        private Long eventId;
        private String username;
        private String eventType;
        private String poiName;
        private String semanticLabel;
        private LocalDateTime startTime;
        private LocalDateTime createTime;
    }

    /** 最新操作日志条目。detail 为完整文本，前端截断展示。 */
    @Data
    public static class RecentLog {
        private Long id;
        private String username;
        private String action;
        private String targetName;
        private String detail;
        private LocalDateTime createTime;
    }

    /** 最近上传的制度文档条目（仅启用版本，面板只展示标题）。 */
    @Data
    public static class RecentDocument {
        private Long docId;
        private String title;
        private String version;
        private LocalDateTime createTime;
    }
}
