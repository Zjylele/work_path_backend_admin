package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.TrackPoint;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import com.workpath.backend.service.analyze.StayPoint;
import com.workpath.backend.service.analyze.StayPointDetector;
import com.workpath.backend.service.modelProvider.GeoClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 轨迹分析服务。M4 的核心：把轨迹点序列变成「工作事件」。
 *
 * <p>W4 起这里落的是真实算法：漂移剔除 → 停留点识别 → POI 解析 →
 * 语义标注。W3 的骨架事件（一条 SUMMARY）已删除，因为第5章演示要看的
 * 是「停留点被识别出来」，骨架事件只会造成噪音。</p>
 *
 * <p>两个外部依赖（POI、语义标注）都设计成<b>失败降级而非阻塞</b>：
 * 高德限流、模型超时都很常见，但它们的结果只是「锦上添花」，
 * 为它们丢掉整条事件的落库价值不成比例。</p>
 *
 * <p>幂等约定：同一条轨迹重复分析会先删除旧事件再写新的，
 * 因此 {@link #analyze} 可安全重入（W3 失败重试依赖这一点）。</p>
 */
@Slf4j
@Service
public class TrackAnalysisService {

    /** 未分析。上传接口写入的初值 */
    public static final int STATUS_PENDING = 0;

    /** 分析中。进入分析前置，防止定时任务重复捞同一条 */
    public static final int STATUS_RUNNING = 1;

    /** 已完成 */
    public static final int STATUS_DONE = 2;

    /** 分析失败。与 RUNNING 分开是关键：停在 1 会让该轨迹永远不再被捞起 */
    public static final int STATUS_FAILED = 3;

    /** 事件类型：停留。聚类与时间维度过滤的产出就是停留。 */
    private static final String EVENT_STAY = "STAY";

    /** 模型返回的「标签|置信度」分隔符。 */
    private static final char LABEL_SEPARATOR = '|';

    private final TrackPointMapper trackPointMapper;
    private final WorkEventMapper workEventMapper;
    private final StayPointDetector stayPointDetector;
    private final GeoClient geoClient;
    private final ChatClient chatClient;

    public TrackAnalysisService(TrackPointMapper trackPointMapper,
                                WorkEventMapper workEventMapper,
                                StayPointDetector stayPointDetector,
                                GeoClient geoClient,
                                ChatClient chatClient) {
        this.trackPointMapper = trackPointMapper;
        this.workEventMapper = workEventMapper;
        this.stayPointDetector = stayPointDetector;
        this.geoClient = geoClient;
        this.chatClient = chatClient;
    }

    /**
     * 分析一条轨迹，产出工作事件。
     *
     * <p>执行顺序：校验点数下限 → 清掉旧事件（保证重入幂等）→ 识别停留点
     * → 逐个落事件。前两步的失败是确定性的（点数不足、轨迹已删），
     * 应让调用方把状态置 FAILED；后两步的失败大多是临时性的外部依赖问题，
     * 因此降级处理而不是抛出。</p>
     *
     * @throws com.workpath.backend.common.BusinessException
     *         点数不足等确定性失败，由调用方置 FAILED 并记录
     */
    @Transactional
    public void analyze(Track track) {
        if (track == null || track.getId() == null) {
            throw new com.workpath.backend.common.BusinessException(
                    com.workpath.backend.common.ResultCode.BAD_REQUEST.getCode(), "轨迹不存在");
        }
        List<TrackPoint> points =
                trackPointMapper.selectByTrackId(track.getId());
        // 少于两点连一条线段都构不成：此时不该产出任何事件，明确失败
        if (points.size() < 2) {
            throw new com.workpath.backend.common.BusinessException(
                    com.workpath.backend.common.ResultCode.BAD_REQUEST.getCode(),
                    "轨迹点数不足（仅 " + points.size() + " 个），无法分析");
        }

        // 幂等：重跑同一条轨迹时先清旧结论，否则会累积出重复事件。
        // 放在识别之前而非之后——若识别阶段抛异常，也不会留下半新半旧的混合数据
        deleteExistingEvents(track.getId());

        List<StayPoint> stays = stayPointDetector.detect(points);
        for (StayPoint stay : stays) {
            workEventMapper.insert(buildEvent(track, stay));
        }

        log.info("轨迹分析完成 trackId={} 点数={} 停留数={}",
                track.getId(), points.size(), stays.size());
    }

    /**
     * 删除该轨迹已有的全部事件。
     *
     * <p>用 trackId 精确删除而不是整表清：定时任务可能同时在跑别的轨迹，
     * 整表清会把并发轮次的成果一起删掉。</p>
     */
    private void deleteExistingEvents(Long trackId) {
        workEventMapper.delete(
                new LambdaQueryWrapper<WorkEvent>()
                        .eq(WorkEvent::getTrackId, trackId));
    }

    /**
     * 把一个停留点变成一条待复核事件。
     *
     * <p>POI 与语义标注各自独立降级，互不影响：POI 成功但标注失败时，
     * poi_name 仍然要留下——那是已经拿到的真实信息。</p>
     */
    private WorkEvent buildEvent(Track track, StayPoint stay) {
        WorkEvent event = new WorkEvent();
        event.setTrackId(track.getId());
        event.setUserId(track.getUserId());
        event.setEventType(EVENT_STAY);
        event.setStartTime(stay.getStartTime());
        event.setEndTime(stay.getEndTime());
        event.setCenterLat(stay.getCenterLat());
        event.setCenterLng(stay.getCenterLng());
        event.setDwellSeconds(stay.getDwellSeconds());

        event.setPoiName(resolvePoi(stay));
        applySemantic(event, stay);

        event.setAiReason(buildReason(stay, event.getPoiName(), event.getSemanticLabel()));

        // AI 结论一律是「待复核」，不由前端指定——否则调用方可把未复核结果标成已确认
        event.setConfirmStatus(0);
        return event;
    }

    /** POI 解析。任何失败都只是返回空，不向上抛。 */
    private String resolvePoi(StayPoint stay) {
        try {
            return geoClient.reverseGeocode(stay.getCenterLat(), stay.getCenterLng())
                    .orElse(null);
        } catch (Exception e) {
            // GeoClient 自己已保证不抛，这里再兜一层是防止将来它接入了
            // 别的实现（比如换成别的地图服务）时不至于把分析拖挂
            log.warn("POI 解析异常 lat={} lng={}: {}",
                    stay.getCenterLat(), stay.getCenterLng(), e.getMessage());
            return null;
        }
    }

    /**
     * 语义标注。调 2 参重载 {@code chat(system, user)}——它会自己取 CHAT 配置。
     *
     * <p>这里刻意不去假设模型一定返回「标签|置信度」：解析不出就只用标签，
     * 连分隔符都没有就整体当标签、置信度留空。把所有解析失败当作
     * 「没有标注」处理，而不是把事件丢掉。</p>
     */
    private void applySemantic(WorkEvent event, StayPoint stay) {
        Optional<String> answer;
        try {
            answer = chatClient.chat(systemPrompt(), userPrompt(event, stay));
        } catch (Exception e) {
            log.warn("语义标注调用异常 trackId={} lat={} lng={}: {}",
                    event.getTrackId(), stay.getCenterLat(), stay.getCenterLng(), e.getMessage());
            return;
        }
        if (answer.isEmpty()) {
            return;
        }
        parseLabel(answer.get(), event);
    }

    /**
     * 解析模型输出。
     *
     * <p>格式约定「标签|置信度」，但模型不会严格遵守，所以逐个兜：</p>
     * <ol>
     *   <li>有分隔符且后面能解析成 0-1 的小数 → 标签 + 置信度</li>
     *   <li>有分隔符但后面不是数字 → 只用标签</li>
     *   <li>没有分隔符 → 整体当标签</li>
     *   <li>解析出的置信度越界（模型可能给 85 表示 85%）→ 截断到 [0,1]</li>
     * </ol>
     */
    private void parseLabel(String answer, WorkEvent event) {
        String[] parts = answer.split("\\" + LABEL_SEPARATOR, 2);
        String label = parts[0].trim();
        if (label.isEmpty()) {
            return;
        }
        event.setSemanticLabel(label);
        if (parts.length == 2) {
            try {
                double c = Double.parseDouble(parts[1].trim());
                if (!Double.isNaN(c) && !Double.isInfinite(c)) {
                    event.setConfidence(Math.max(0.0, Math.min(1.0, c)));
                }
            } catch (NumberFormatException ignore) {
                // 置信度解析失败不影响标签本身
            }
        }
    }

    /**
     * 语义标注的系统提示。
     *
     * <p>第一版只给了「时长 + 地名」两个输入和五个候选标签，
     * 实测（DeepSeek）输出几乎全是「其他|0.3」——模型不是坏了，
     * 是<b>没有任何判定依据可用</b>：它既不知道每个标签意味着什么，
     * 也不知道时长与场地性质之间通常有什么对应关系，只能选最安全的兜底。</p>
     *
     * <p>补上依据后的实测差异：天府软件园/腾讯大厦这类园区与写字楼地名，
     * 20 分钟以上稳定判「客户拜访」且置信度 0.4-0.6；
     * 天府广场、体育中心判「其他」。同一份输入，各标签各得其所。</p>
     *
     * <p>刻意保留「信息不足就给低置信度」这条约束：宁可标得没底气，
     * 也不能为了好看编一个高置信度——那是给管理层的假信号。</p>
     */
    private String systemPrompt() {
        return "你是外勤工作轨迹分析助手。根据停留时长与地点名，判断这次停留最可能的工作性质。"
                + "判定依据（地名优先，时长为辅）："
                + "① 产业园区、写字楼、企业名称 → 客户拜访或驻点办公；"
                + "② 餐厅、饭店、咖啡、食堂 → 用餐；"
                + "③ 车站、机场、地铁、高速 → 通勤；"
                + "④ 住宅区、小区、酒店 → 视时长，短为驻点办公长为休息；"
                + "⑤ 商圈、广场、体育场馆等公共设施 → 其他（外勤目的不明显）。"
                + "时长只作辅助：短于 5 分钟的低置信度，20 分钟以上且地点明确时可以提高置信度。"
                + "只输出一行，格式为「标签|置信度」，"
                + "标签只能从这些里选一个：客户拜访、用餐、通勤、驻点办公、其他。"
                + "置信度是 0 到 1 之间的小数，表示你对这个判断的把握。"
                + "信息不足以判断时输出「其他|0.3」，不要编造细节。";
    }

    private String userPrompt(WorkEvent event, StayPoint stay) {
        StringBuilder sb = new StringBuilder();
        sb.append("停留时长：").append(stay.getDwellSeconds()).append(" 秒");
        if (event.getPoiName() != null) {
            sb.append("\n地点名：").append(event.getPoiName());
        } else {
            sb.append("\n地点名：（未解析到）");
        }
        return sb.toString();
    }

    /** 给人看的判定摘要，落 ai_reason 列。 */
    private String buildReason(StayPoint stay, String poi, String label) {
        StringBuilder sb = new StringBuilder();
        sb.append("停留 ").append(stay.getDwellSeconds()).append(" 秒");
        if (poi != null) {
            sb.append("，地点「").append(poi).append("」");
        }
        if (label != null) {
            sb.append("，判定为").append(label);
        } else {
            sb.append("，未获得语义标注");
        }
        sb.append("（基于 ").append(stay.getPointCount()).append(" 个轨迹点）");
        return sb.toString();
    }
}
