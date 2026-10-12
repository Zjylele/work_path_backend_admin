package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.dto.TrackPointDTO;
import com.workpath.backend.dto.TrackUploadDTO;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.TrackPoint;
import com.workpath.backend.entity.User;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.TrackMapper;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import com.workpath.backend.vo.TrackDetailVO;
import com.workpath.backend.vo.TrackEventVO;
import com.workpath.backend.vo.TrackListVO;
import com.workpath.backend.vo.TrackPointVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrackService {

    private final TrackMapper trackMapper;
    private final TrackPointMapper trackPointMapper;
    private final WorkEventMapper workEventMapper;

    @Transactional
    public Long upload(TrackUploadDTO dto) {
        Long userId = UserContext.getUserId();

        // 幂等：同一用户 + clientTrackId 已存在则直接返回已有 id，不重复入库
        String clientTrackId = dto.getClientTrackId();
        if (StringUtils.hasText(clientTrackId)) {
            Track exist = trackMapper.selectOne(new LambdaQueryWrapper<Track>()
                    .eq(Track::getUserId, userId)
                    .eq(Track::getClientTrackId, clientTrackId)
                    .last("LIMIT 1"));
            if (exist != null) {
                return exist.getId();
            }
        }

        Track track = new Track();
        track.setUserId(userId);
        track.setClientTrackId(clientTrackId);
        // 名称来自移动端开始记录时的录入；空白按 null 入库，展示层统一走「未命名」
        track.setName(StringUtils.hasText(dto.getName()) ? dto.getName().trim() : null);
        track.setStartTime(dto.getStartTime());
        track.setEndTime(dto.getEndTime());
        track.setStatus(dto.getStatus());
        track.setDistance(dto.getDistance());
        track.setCreateTime(LocalDateTime.now());
        trackMapper.insert(track);

        for (TrackPointDTO p : dto.getPoints()) {
            TrackPoint point = new TrackPoint();
            point.setTrackId(track.getId());
            point.setLatitude(p.getLatitude());
            point.setLongitude(p.getLongitude());
            point.setRecordTime(p.getRecordTime());
            trackPointMapper.insert(point);
        }

        return track.getId();
    }

    public IPage<TrackListVO> list(Integer page, Integer pageSize) {
        return trackMapper.selectMyTracks(new Page<>(page, pageSize), UserContext.getUserId());
    }

    public TrackDetailVO detail(Long id) {
        Track track = getTrackOrThrow(id);
        checkPermission(track);
        return toDetailVO(track);
    }

    /**
     * 某条轨迹的语义标注结果（员工侧，W9 移动端轨迹详情用）。
     *
     * <p>归属校验复用详情那一套：员工的 token 只能取到自己的轨迹，管理员不受限。
     * 按开始时间升序，不分页——一条轨迹通常只产出 1-3 个停留事件。</p>
     */
    public List<TrackEventVO> listEvents(Long trackId) {
        Track track = getTrackOrThrow(trackId);
        checkPermission(track);

        List<WorkEvent> events = workEventMapper.selectList(new LambdaQueryWrapper<WorkEvent>()
                .eq(WorkEvent::getTrackId, trackId)
                .orderByAsc(WorkEvent::getStartTime));
        return events.stream().map(TrackService::toEventVO).collect(Collectors.toList());
    }

    @Transactional
    public void delete(Long id) {
        Track track = getTrackOrThrow(id);
        checkPermission(track);
        trackPointMapper.delete(new LambdaQueryWrapper<TrackPoint>().eq(TrackPoint::getTrackId, id));
        trackMapper.deleteById(id);
    }

    private Track getTrackOrThrow(Long id) {
        Track track = trackMapper.selectById(id);
        if (track == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "轨迹不存在");
        }
        return track;
    }

    private void checkPermission(Track track) {
        User current = UserContext.get();
        if (current == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "未登录");
        }
        if ("ADMIN".equals(current.getRole())) {
            return;
        }
        if (track.getUserId() != null && track.getUserId().equals(current.getId())) {
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN.getCode(), ResultCode.FORBIDDEN.getMessage());
    }

    private TrackDetailVO toDetailVO(Track track) {
        TrackDetailVO vo = new TrackDetailVO();
        vo.setId(track.getId());
        vo.setName(track.getName());
        vo.setStartTime(track.getStartTime());
        vo.setEndTime(track.getEndTime());
        vo.setStatus(track.getStatus());
        vo.setDistance(track.getDistance());

        List<TrackPoint> points = trackPointMapper.selectByTrackId(track.getId());
        vo.setPoints(points.stream().map(this::toPointVO).collect(Collectors.toList()));
        return vo;
    }

    private TrackPointVO toPointVO(TrackPoint point) {
        TrackPointVO vo = new TrackPointVO();
        vo.setLatitude(point.getLatitude());
        vo.setLongitude(point.getLongitude());
        vo.setRecordTime(point.getRecordTime());
        return vo;
    }

    /** 实体 → 员工侧视图：复核人、判定过程原文这类管理端字段不外发 */
    private static TrackEventVO toEventVO(WorkEvent event) {
        TrackEventVO vo = new TrackEventVO();
        vo.setEventId(event.getEventId());
        vo.setEventType(event.getEventType());
        vo.setStartTime(event.getStartTime());
        vo.setEndTime(event.getEndTime());
        vo.setCenterLat(event.getCenterLat());
        vo.setCenterLng(event.getCenterLng());
        vo.setDwellSeconds(event.getDwellSeconds());
        vo.setPoiName(event.getPoiName());
        vo.setSemanticLabel(event.getSemanticLabel());
        vo.setConfidence(event.getConfidence());
        vo.setAiReason(event.getAiReason());
        vo.setConfirmStatus(event.getConfirmStatus());
        return vo;
    }

}
