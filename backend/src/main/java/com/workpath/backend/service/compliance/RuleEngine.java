package com.workpath.backend.service.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workpath.backend.entity.ComplianceRule;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.WorkEvent;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * M5-W5 规则引擎：四种规则的判定，纯程序完成，不调 LLM。
 *
 * <p>分工依据实施指南 W5：数值比较结果确定、可复现、可测试；模型只做它
 * 擅长的语言组织。命中返回证据文本（进 compliance_check.evidence），
 * 不命中或规则不适用返回 empty——「不适用」与「合规」在引擎层面是同一回事，
 * 引擎只回答「是否命中」。</p>
 *
 * <p>驻地配置来自规则的 extra_config JSON（user 表没有驻地字段）：
 * {@code {"station_lat":..,"station_lng":..,"station_radius_m":..}}。
 * 没配驻地的 DURATION 规则退化为纯时长判定；RANGE 没配驻地则整个规则不可用。</p>
 */
@Slf4j
public class RuleEngine {

    /** ABNORMAL 判定需要的最少轨迹点数：低于它说明人基本没动，不算「有移动」 */
    static final int ABNORMAL_MIN_POINTS = 12;

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 事件级判定（DURATION / FREQUENCY / RANGE）。
     *
     * @param event          刚被人工确认的事件
     * @param rule           启用中的规则
     * @param sameDayStays   该员工当日已确认的停留事件（含本条），FREQUENCY 用
     * @return 命中证据；不命中或不适用返回 empty
     */
    public Optional<String> evaluate(WorkEvent event, ComplianceRule rule,
                                     List<WorkEvent> sameDayStays) {
        if (event == null || rule == null || rule.getRuleType() == null) {
            return Optional.empty();
        }
        return switch (rule.getRuleType()) {
            case "DURATION" -> evaluateDuration(event, rule);
            case "FREQUENCY" -> evaluateFrequency(event, rule, sameDayStays);
            case "RANGE" -> evaluateRange(event, rule);
            // ABNORMAL 是轨迹级判定，走 evaluateAbnormal，事件级直接不适用
            case "ABNORMAL" -> Optional.empty();
            default -> {
                log.warn("未知规则类型 type={} ruleCode={}", rule.getRuleType(), rule.getRuleCode());
                yield Optional.empty();
            }
        };
    }

    /**
     * DURATION：单次停留时长超过阈值（分钟）且不在报备驻地内。
     *
     * <p>阈值是「超过才违规」，判定用严格大于——恰好等于阈值是制度容忍的边界，
     * 与停留识别「≥300 秒才算停留」的口径一致。</p>
     */
    private Optional<String> evaluateDuration(WorkEvent event, ComplianceRule rule) {
        if (!isStay(event) || event.getDwellSeconds() == null || rule.getThreshold() == null) {
            return Optional.empty();
        }
        Station station = parseStation(rule);
        if (station != null && event.getCenterLat() != null && event.getCenterLng() != null
                && distanceMeters(event.getCenterLat(), event.getCenterLng(),
                        station.lat(), station.lng()) <= station.radiusM()) {
            // 在报备驻地范围内：长停留是正常办公，不是违规
            return Optional.empty();
        }
        double minutes = event.getDwellSeconds() / 60.0;
        if (minutes > rule.getThreshold()) {
            return Optional.of(String.format("单次停留 %.1f 分钟，超过阈值 %.0f 分钟%s",
                    minutes, rule.getThreshold(), where(event)));
        }
        return Optional.empty();
    }

    /**
     * FREQUENCY：当日同类停留次数达到阈值。「同类」按语义标注分组——
     * 一天内多次短停留本身正常（送完 A 送 B），同类反复才值得警惕
     * （如同为「用餐」的 5 次停留）。
     */
    private Optional<String> evaluateFrequency(WorkEvent event, ComplianceRule rule,
                                               List<WorkEvent> sameDayStays) {
        if (!isStay(event) || rule.getThreshold() == null || sameDayStays == null) {
            return Optional.empty();
        }
        String label = event.getSemanticLabel();
        long count = sameDayStays.stream()
                .filter(e -> label == null
                        ? e.getSemanticLabel() == null
                        : label.equals(e.getSemanticLabel()))
                .count();
        if (count >= rule.getThreshold()) {
            return Optional.of(String.format("当日同类（%s）停留 %d 次，达到阈值 %.0f 次",
                    label == null ? "未标注" : label, count, rule.getThreshold()));
        }
        return Optional.empty();
    }

    /**
     * RANGE：停留点距驻地中心超过阈值（米）。驻地中心来自 extra_config，
     * 没配驻地的 RANGE 规则无从判定，视为不适用而不是违规——配置缺失
     * 是管理员的事，不该自动变成对员工的指控。
     */
    private Optional<String> evaluateRange(WorkEvent event, ComplianceRule rule) {
        if (!isStay(event) || rule.getThreshold() == null) {
            return Optional.empty();
        }
        Station station = parseStation(rule);
        if (station == null || event.getCenterLat() == null || event.getCenterLng() == null) {
            return Optional.empty();
        }
        double dist = distanceMeters(event.getCenterLat(), event.getCenterLng(),
                station.lat(), station.lng());
        if (dist > rule.getThreshold()) {
            return Optional.of(String.format("停留点距驻地 %.0f 米，超过阈值 %.0f 米%s",
                    dist, rule.getThreshold(), where(event)));
        }
        return Optional.empty();
    }

    /**
     * ABNORMAL（轨迹级）：轨迹已分析完成但没有任何停留事件，且轨迹点数
     * 达到「确有移动」的下限。threshold 无含义（指南标注为「—」）。
     *
     * @param track     已完成分析的轨迹
     * @param stayCount 该轨迹的 STAY 事件数
     * @param pointCount 该轨迹的轨迹点数
     */
    public Optional<String> evaluateAbnormal(Track track, long stayCount, long pointCount) {
        if (track == null || stayCount > 0 || pointCount < ABNORMAL_MIN_POINTS) {
            return Optional.empty();
        }
        long minutes = -1;
        if (track.getStartTime() != null && track.getEndTime() != null) {
            minutes = Duration.between(track.getStartTime(), track.getEndTime()).toMinutes();
        }
        return Optional.of(String.format("轨迹移动 %d 分钟、%d 个轨迹点，未识别出任何停留",
                minutes, pointCount));
    }

    /** 解析 extra_config 的驻地配置；缺字段或 JSON 非法返回 null（配置错误不算违规）。 */
    public Station parseStation(ComplianceRule rule) {
        String json = rule.getExtraConfig();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode lat = root.path("station_lat");
            JsonNode lng = root.path("station_lng");
            if (!lat.isNumber() || !lng.isNumber()) {
                return null;
            }
            double radius = root.path("station_radius_m").asDouble(200.0);
            return new Station(lat.asDouble(), lng.asDouble(), radius);
        } catch (Exception e) {
            log.warn("规则 extra_config 解析失败 ruleCode={} : {}", rule.getRuleCode(), e.getMessage());
            return null;
        }
    }

    private static boolean isStay(WorkEvent event) {
        return "STAY".equals(event.getEventType());
    }

    private static String where(WorkEvent event) {
        return event.getPoiName() == null ? "" : "（" + event.getPoiName() + "）";
    }

    /**
     * Haversine 距离（米），与 StayPointDetector 内部同式——那里是算法私有实现，
     * 抽公共类反而让两处耦合，这里是复制的有意取舍。
     */
    static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
    }

    /** 驻地：中心坐标 + 报备范围半径（米），缺省 200 米（GPS 精度的合理上界）。 */
    record Station(double lat, double lng, double radiusM) {
    }
}
