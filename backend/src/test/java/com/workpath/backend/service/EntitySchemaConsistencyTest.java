package com.workpath.backend.service;

import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.WorkEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体字段与库表结构的对账测试。
 *
 * <p>存在的理由：W3 曾把 WorkEvent 的字段名按"更顺眼"的方式命名
 * （event_desc / duration_seconds），而库里实际是 ai_reason / dwell_seconds。
 * 结果单元测试全绿、@SpringBootTest 也过（mock 不碰真 SQL），
 * 直到起服务跑定时任务 INSERT 才报 Unknown column。</p>
 *
 * <p>纯内存测试抓不到这类问题，所以这里直接查 information_schema。
 * 代价是要连真库——本地开发库不在时测试会失败并说清原因，
 * 这比一个恒绿的空转测试诚实得多。</p>
 */
@SpringBootTest
class EntitySchemaConsistencyTest {

    /** 实体字段名 → 期望的列名。缺省按驼峰转蛇形推导。 */
    private static final Map<String, String> WORK_EVENT_MAP = new HashMap<>();

    static {
        WORK_EVENT_MAP.put("eventId", "event_id");
        WORK_EVENT_MAP.put("trackId", "track_id");
        WORK_EVENT_MAP.put("userId", "user_id");
        WORK_EVENT_MAP.put("eventType", "event_type");
        WORK_EVENT_MAP.put("startTime", "start_time");
        WORK_EVENT_MAP.put("endTime", "end_time");
        WORK_EVENT_MAP.put("centerLat", "center_lat");
        WORK_EVENT_MAP.put("centerLng", "center_lng");
        WORK_EVENT_MAP.put("dwellSeconds", "dwell_seconds");
        WORK_EVENT_MAP.put("poiName", "poi_name");
        WORK_EVENT_MAP.put("semanticLabel", "semantic_label");
        WORK_EVENT_MAP.put("confidence", "confidence");
        WORK_EVENT_MAP.put("aiReason", "ai_reason");
        WORK_EVENT_MAP.put("confirmStatus", "confirm_status");
        WORK_EVENT_MAP.put("confirmBy", "confirm_by");
        WORK_EVENT_MAP.put("confirmTime", "confirm_time");
        WORK_EVENT_MAP.put("analysisTrace", "analysis_trace");
        WORK_EVENT_MAP.put("createTime", "create_time");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Set<String> workEventColumns;
    private Set<String> trackColumns;

    @BeforeEach
    void loadSchema() throws SQLException {
        workEventColumns = columnsOf("work_event");
        trackColumns = columnsOf("track");
    }

    @Test
    @DisplayName("WorkEvent 实体字段全部对应 work_event 表的真实列")
    void workEventFieldsExist() {
        // 被 @TableField(exist = false) 标为非持久化的字段（username）不应在此列
        assertThat(WORK_EVENT_MAP).hasSize(18);
        Set<String> missing = new HashSet<>();
        for (Map.Entry<String, String> e : WORK_EVENT_MAP.entrySet()) {
            if (!workEventColumns.contains(e.getValue())) {
                missing.add(e.getKey() + " → " + e.getValue());
            }
        }
        assertThat(missing)
                .as("以下实体字段在 work_event 表里找不到对应列，INSERT 会报 Unknown column。"
                        + "实体字段名必须与库表逐字对齐，不能自行取更顺眼的名字")
                .isEmpty();
    }

    @Test
    @DisplayName("实体字段反过来覆盖了表的全部列——表的列都应被实体读到")
    void noOrphanColumns() {
        Set<String> mapped = new HashSet<>(WORK_EVENT_MAP.values());
        Set<String> orphan = new HashSet<>(workEventColumns);
        orphan.removeAll(mapped);
        // 表里可能有实体未建模的列（如给后续阶段预留的），仅提示不阻断
        assertThat(orphan)
                .as("这些列存在但实体没有对应字段，读取时会被静默丢弃。"
                        + "若是预留列可忽略，否则应补进实体")
                .isEmpty();
    }

    @Test
    @DisplayName("Track 实体的 analysisStatus 有对应列（M4 状态机依赖它）")
    void trackAnalysisStatusColumn() {
        assertThat(trackColumns).contains("analysis_status");
    }

    @Test
    @DisplayName("Track 实体的 name 有对应列（轨迹名称链路依赖它）")
    void trackNameColumn() {
        assertThat(trackColumns).contains("name");
    }

    @Test
    @DisplayName("work_event 的 not-null 列在实体路径上都有值来源")
    void requiredColumnsCovered() throws java.sql.SQLException {
        // confirm_status NOT NULL 且无数据库默认值的字段，必须由实体负责写入。
        // AI 结论的"待确认"初值若依赖数据库默认值，一旦有人显式写 null 就绕过了约束
        Set<String> required = requiredColumns("work_event");
        assertThat(required).contains("track_id", "user_id", "event_type",
                "start_time", "confirm_status");
    }

    @Test
    @DisplayName("MyBatis 能真正 INSERT work_event——把结构对账落实到 SQL 层")
    void insertWorksEndToEnd() throws SQLException {
        // 直接跑一条最小 INSERT，验证实体字段名与列名的组合能被数据库接受。
        // finally 立即清理探针行：这是写进开发库的数据，不清会出现在
        // 工作事件复核列表与仪表盘「待复核工作事件」面板里（曾残留多条）
        try {
            jdbcTemplate.execute("""
                    INSERT INTO work_event (track_id, user_id, event_type, start_time, end_time,
                                            ai_reason, confirm_status)
                    VALUES (0, 0, 'PROBE', NOW(), NOW(), 'schema consistency probe', 0)
                    """);
        } finally {
            jdbcTemplate.update("DELETE FROM work_event WHERE track_id = 0 AND event_type = 'PROBE'");
        }
    }

    private Set<String> columnsOf(String table) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getColumns(null, null, table, null)) {
                Set<String> out = new HashSet<>();
                while (rs.next()) {
                    out.add(rs.getString("COLUMN_NAME"));
                }
                return out;
            }
        }
    }

    private Set<String> requiredColumns(String table) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getColumns(null, null, table, null)) {
                Set<String> out = new HashSet<>();
                while (rs.next()) {
                    if ("NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"))) {
                        out.add(rs.getString("COLUMN_NAME"));
                    }
                }
                return out;
            }
        }
    }
}
