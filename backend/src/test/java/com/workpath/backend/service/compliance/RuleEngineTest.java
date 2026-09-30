package com.workpath.backend.service.compliance;

import com.workpath.backend.entity.ComplianceRule;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.WorkEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W5 规则引擎测试。实施指南 8.1 要求：四种规则各一个命中 + 一个不命中用例。
 * 引擎是纯函数（不碰 DB、不调模型），命中/不命中的边界都在这里钉死。
 */
class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 30, 9, 0, 0);

    // ============================================================
    // DURATION：单次停留超过阈值（分钟）且不在驻地
    // ============================================================

    @Test
    @DisplayName("DURATION 命中：停留 60 分钟超过阈值 30 分钟")
    void durationHit() {
        Optional<String> hit = engine.evaluate(stay(3600), rule("DURATION", 30.0, null), List.of());
        assertThat(hit).isPresent();
        assertThat(hit.get()).contains("60.0 分钟").contains("30 分钟");
    }

    @Test
    @DisplayName("DURATION 不命中：停留 20 分钟低于阈值")
    void durationMiss() {
        assertThat(engine.evaluate(stay(1200), rule("DURATION", 30.0, null), List.of())).isEmpty();
    }

    @Test
    @DisplayName("DURATION 边界：恰好等于阈值不算违规（严格大于）")
    void durationBoundary() {
        assertThat(engine.evaluate(stay(1800), rule("DURATION", 30.0, null), List.of())).isEmpty();
    }

    @Test
    @DisplayName("DURATION 在报备驻地内的长停留不违规")
    void durationInStationMiss() {
        // 驻地 (30.5483, 104.0625) 半径 500m，事件中心就在驻地里
        ComplianceRule r = rule("DURATION", 30.0,
                "{\"station_lat\":30.5483,\"station_lng\":104.0625,\"station_radius_m\":500}");
        WorkEvent e = stay(7200);
        e.setCenterLat(30.54831);
        e.setCenterLng(104.06251);
        assertThat(engine.evaluate(e, r, List.of())).isEmpty();
    }

    // ============================================================
    // FREQUENCY：当日同类停留次数达到阈值
    // ============================================================

    @Test
    @DisplayName("FREQUENCY 命中：当日同类停留 5 次达到阈值")
    void frequencyHit() {
        List<WorkEvent> day = List.of(
                stayWithLabel("用餐"), stayWithLabel("用餐"), stayWithLabel("用餐"),
                stayWithLabel("用餐"), stayWithLabel("用餐"));
        Optional<String> hit = engine.evaluate(stayWithLabel("用餐"),
                rule("FREQUENCY", 5.0, null), day);
        assertThat(hit).isPresent();
        assertThat(hit.get()).contains("5 次").contains("用餐");
    }

    @Test
    @DisplayName("FREQUENCY 不命中：同类只有 4 次")
    void frequencyMiss() {
        List<WorkEvent> day = List.of(
                stayWithLabel("用餐"), stayWithLabel("用餐"), stayWithLabel("用餐"),
                stayWithLabel("用餐"));
        // 触发事件的标注与其余不同类，同类只有 4 次
        assertThat(engine.evaluate(stayWithLabel("用餐"),
                rule("FREQUENCY", 5.0, null), day)).isEmpty();
    }

    @Test
    @DisplayName("FREQUENCY 不同语义标注不并入同类计数")
    void frequencyDifferentLabelNotCounted() {
        List<WorkEvent> day = List.of(
                stayWithLabel("客户拜访"), stayWithLabel("用餐"), stayWithLabel("用餐"),
                stayWithLabel("用餐"), stayWithLabel("用餐"));
        assertThat(engine.evaluate(stayWithLabel("客户拜访"),
                rule("FREQUENCY", 5.0, null), day)).isEmpty();
    }

    // ============================================================
    // RANGE：停留点距驻地中心超过阈值（米）
    // ============================================================

    @Test
    @DisplayName("RANGE 命中：距驻地约 1.1 公里超过阈值 500 米")
    void rangeHit() {
        ComplianceRule r = rule("RANGE", 500.0,
                "{\"station_lat\":30.5483,\"station_lng\":104.0625}");
        WorkEvent e = stay(600);
        // 纬度差 0.01 度 ≈ 1.1 公里
        e.setCenterLat(30.5583);
        e.setCenterLng(104.0625);
        Optional<String> hit = engine.evaluate(e, r, List.of());
        assertThat(hit).isPresent();
        assertThat(hit.get()).contains("超过阈值 500 米");
    }

    @Test
    @DisplayName("RANGE 不命中：距驻地 300 米在阈值内")
    void rangeMiss() {
        ComplianceRule r = rule("RANGE", 500.0,
                "{\"station_lat\":30.5483,\"station_lng\":104.0625}");
        WorkEvent e = stay(600);
        e.setCenterLat(30.5483 + 0.0027); // ≈300 米
        e.setCenterLng(104.0625);
        assertThat(engine.evaluate(e, r, List.of())).isEmpty();
    }

    @Test
    @DisplayName("RANGE 未配置驻地时不适用而不是违规")
    void rangeWithoutStationNotApplicable() {
        assertThat(engine.evaluate(stay(600), rule("RANGE", 500.0, null), List.of())).isEmpty();
    }

    // ============================================================
    // ABNORMAL：轨迹级——有移动但无任何停留
    // ============================================================

    @Test
    @DisplayName("ABNORMAL 命中：轨迹 50 个点、90 分钟无任何停留")
    void abnormalHit() {
        Track track = track(90);
        Optional<String> hit = engine.evaluateAbnormal(track, 0, 50);
        assertThat(hit).isPresent();
        assertThat(hit.get()).contains("未识别出任何停留").contains("50 个轨迹点");
    }

    @Test
    @DisplayName("ABNORMAL 不命中：存在停留事件的轨迹")
    void abnormalWithStayMiss() {
        assertThat(engine.evaluateAbnormal(track(90), 2, 50)).isEmpty();
    }

    @Test
    @DisplayName("ABNORMAL 不命中：点数过少说明人基本没动")
    void abnormalFewPointsMiss() {
        assertThat(engine.evaluateAbnormal(track(90), 0, 5)).isEmpty();
    }

    // ============================================================
    // 公共约束：非 STAY 事件不参与事件级判定
    // ============================================================

    @Test
    @DisplayName("MOVE 事件不参与 DURATION/RANGE 判定")
    void moveEventNotApplicable() {
        WorkEvent move = stay(3600);
        move.setEventType("MOVE");
        assertThat(engine.evaluate(move, rule("DURATION", 30.0, null), List.of())).isEmpty();
        assertThat(engine.evaluate(move, rule("RANGE", 500.0,
                "{\"station_lat\":30.5,\"station_lng\":104.0}"), List.of())).isEmpty();
    }

    @Test
    @DisplayName("未知规则类型不命中也不抛")
    void unknownTypeSafe() {
        assertThat(engine.evaluate(stay(3600), rule("UNKNOWN", 1.0, null), List.of())).isEmpty();
    }

    // ============================================================
    // 造数
    // ============================================================

    private WorkEvent stay(int dwellSeconds) {
        WorkEvent e = new WorkEvent();
        e.setEventId(1L);
        e.setEventType("STAY");
        e.setUserId(5L);
        e.setStartTime(T0);
        e.setEndTime(T0.plusSeconds(dwellSeconds));
        e.setDwellSeconds(dwellSeconds);
        e.setCenterLat(30.5484);
        e.setCenterLng(104.0626);
        e.setPoiName("成都市武侯区某大厦");
        return e;
    }

    private WorkEvent stayWithLabel(String label) {
        WorkEvent e = stay(600);
        e.setSemanticLabel(label);
        return e;
    }

    private ComplianceRule rule(String type, Double threshold, String extraConfig) {
        ComplianceRule r = new ComplianceRule();
        r.setRuleId(100L);
        r.setRuleCode("T-" + type);
        r.setRuleName("测试规则");
        r.setRuleType(type);
        r.setThreshold(threshold);
        r.setExtraConfig(extraConfig);
        r.setEnabled(1);
        return r;
    }

    private Track track(long minutes) {
        Track t = new Track();
        t.setId(7L);
        t.setUserId(5L);
        t.setStartTime(T0);
        t.setEndTime(T0.plusMinutes(minutes));
        return t;
    }
}
