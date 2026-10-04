package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.OperationLog;
import com.workpath.backend.entity.PolicyDocument;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.TrackPoint;
import com.workpath.backend.entity.User;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.OperationLogMapper;
import com.workpath.backend.mapper.PolicyDocumentMapper;
import com.workpath.backend.mapper.TrackMapper;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.UserMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import com.workpath.backend.vo.AdminTrackDetailVO;
import com.workpath.backend.vo.AdminTrackVO;
import com.workpath.backend.vo.AdminUserVO;
import com.workpath.backend.vo.DashboardRecentVO;
import com.workpath.backend.vo.StatsVO;
import com.workpath.backend.vo.TrackPointVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserMapper userMapper;
    private final TrackMapper trackMapper;
    private final TrackPointMapper trackPointMapper;
    private final OperationLogMapper operationLogMapper;
    private final OperationLogService operationLogService;
    private final WorkEventMapper workEventMapper;
    private final PolicyDocumentMapper policyDocumentMapper;

    /** 每块仪表盘面板最多展示的条目数：面板是"最近动态"入口，不是完整列表 */
    private static final int RECENT_LIMIT = 10;

    public IPage<AdminUserVO> userList(Integer page, Integer pageSize, String keyword) {
        return userMapper.selectAdminUsers(new Page<>(page, pageSize), keyword);
    }

    public void updateUserStatus(Long id, Integer status) {
        User user = getUserOrThrow(id);
        user.setStatus(status);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        recordLog(status == 0 ? "USER_DISABLE" : "USER_ENABLE",
                "USER", user.getId(), user.getUsername(), null);
    }

    public void resetPassword(Long id, String password) {
        User user = getUserOrThrow(id);
        user.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        recordLog("PASSWORD_RESET", "USER", user.getId(), user.getUsername(), null);
    }

    /** 删除用户：级联删除其轨迹；仅超级管理员可操作，且不能删自己、不能删超管 */
    @Transactional
    public void deleteUser(Long id) {
        User operator = UserContext.get();
        if (operator == null || !isSuperAdmin(operator)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "仅超级管理员可执行此操作");
        }

        User target = getUserOrThrow(id);
        Long operatorId = operator.getId();

        if (target.getId().equals(operatorId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能删除当前登录账号");
        }
        if (isSuperAdmin(target)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能删除超级管理员");
        }

        // 级联删除：轨迹点 → 轨迹 → 用户
        List<Track> tracks = trackMapper.selectList(
                new LambdaQueryWrapper<Track>().eq(Track::getUserId, id));
        for (Track track : tracks) {
            trackPointMapper.delete(new LambdaQueryWrapper<TrackPoint>()
                    .eq(TrackPoint::getTrackId, track.getId()));
        }
        trackMapper.delete(new LambdaQueryWrapper<Track>().eq(Track::getUserId, id));
        userMapper.deleteById(id);
        recordLog("USER_DELETE", "USER", target.getId(), target.getUsername(),
                "删除用户并级联删除其 " + tracks.size() + " 条轨迹");
    }

    /** 授予/撤销管理员：仅超级管理员可操作；不能改自己、不能改其他超管、禁用账号不可授予 ADMIN */
    public void changeRole(Long id, String role) {
        User operator = UserContext.get();
        if (operator == null || !isSuperAdmin(operator)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "仅超级管理员可执行此操作");
        }
        if (!"ADMIN".equals(role) && !"USER".equals(role)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "角色只能是 ADMIN 或 USER");
        }

        User target = getUserOrThrow(id);
        if (target.getId().equals(operator.getId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能修改自己的角色");
        }
        if (isSuperAdmin(target)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能修改超级管理员的角色");
        }
        if ("ADMIN".equals(role) && target.getStatus() != null && target.getStatus() == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "禁用的账号不能授予管理员");
        }

        target.setRole(role);
        target.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(target);
        recordLog("ADMIN".equals(role) ? "ROLE_GRANT" : "ROLE_REVOKE",
                "USER", target.getId(), target.getUsername(),
                "ADMIN".equals(role) ? "授予管理员" : "撤销管理员");
    }

    private boolean isSuperAdmin(User user) {
        return user.getSuperAdmin() != null && user.getSuperAdmin() == 1;
    }

    public IPage<AdminTrackVO> trackList(Integer page, Integer pageSize, Long userId,
                                         LocalDateTime startTime, LocalDateTime endTime) {
        return trackMapper.selectAdminTracks(new Page<>(page, pageSize), userId, startTime, endTime);
    }

    public AdminTrackDetailVO trackDetail(Long id) {
        Track track = trackMapper.selectById(id);
        if (track == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "轨迹不存在");
        }
        User owner = userMapper.selectById(track.getUserId());

        AdminTrackDetailVO vo = new AdminTrackDetailVO();
        vo.setId(track.getId());
        vo.setName(track.getName());
        vo.setUsername(owner == null ? null : owner.getUsername());
        vo.setStartTime(track.getStartTime());
        vo.setEndTime(track.getEndTime());
        vo.setStatus(track.getStatus());
        vo.setDistance(track.getDistance());

        List<TrackPoint> points = trackPointMapper.selectByTrackId(track.getId());
        List<TrackPointVO> pointVos = points.stream().map(p -> {
            TrackPointVO pv = new TrackPointVO();
            pv.setLatitude(p.getLatitude());
            pv.setLongitude(p.getLongitude());
            pv.setRecordTime(p.getRecordTime());
            return pv;
        }).collect(Collectors.toList());
        vo.setPoints(pointVos);
        return vo;
    }

    @Transactional
    public void deleteTrack(Long id) {
        Track track = trackMapper.selectById(id);
        if (track == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "轨迹不存在");
        }
        trackPointMapper.delete(new QueryWrapper<TrackPoint>().eq("track_id", id));
        trackMapper.deleteById(id);
        recordLog("TRACK_DELETE", "TRACK", track.getId(), String.valueOf(track.getId()),
                "删除轨迹（开始于 " + track.getStartTime() + "）");
    }

    /** 操作日志列表：分页 + 筛选（操作人/动作/时间范围） */
    public IPage<OperationLog> logList(Integer page, Integer pageSize, String username,
                                       String action, LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>()
                .eq(StringUtils.hasText(username), OperationLog::getUsername, username)
                .eq(StringUtils.hasText(action), OperationLog::getAction, action)
                .ge(startTime != null, OperationLog::getCreateTime, startTime)
                .le(endTime != null, OperationLog::getCreateTime, endTime)
                .orderByDesc(OperationLog::getCreateTime);
        return operationLogMapper.selectPage(new Page<>(page, pageSize), wrapper);
    }

    /** 删除单条操作日志 */
    public void deleteLog(Long id) {
        if (operationLogMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "日志不存在");
        }
        operationLogMapper.deleteById(id);
    }

    /** 批量删除操作日志（ids 为空则视为删除全部） */
    @Transactional
    public void deleteLogs(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            operationLogMapper.delete(null);
        } else {
            operationLogMapper.deleteBatchIds(ids);
        }
    }

    /** 记录当前操作人（UserContext）的管理操作日志；记录失败不影响主流程 */
    private void recordLog(String action, String targetType, Long targetId,
                           String targetName, String detail) {
        User operator = UserContext.get();
        operationLogService.record(
                operator == null ? null : operator.getId(),
                operator == null ? null : operator.getUsername(),
                action, targetType, targetId, targetName, detail, true);
    }

    public StatsVO stats() {
        StatsVO vo = new StatsVO();
        vo.setUserCount(userMapper.selectCount(null));
        vo.setTrackCount(trackMapper.selectCount(null));
        vo.setTodayTrackCount(trackMapper.selectCount(
                new QueryWrapper<Track>().ge("create_time", LocalDate.now().atStartOfDay())));
        vo.setTodayUserCount(userMapper.selectCount(
                new QueryWrapper<User>().ge("create_time", LocalDate.now().atStartOfDay())));
        return vo;
    }

    /**
     * 仪表盘最近动态：轨迹 / 待复核工作事件 / 操作日志 / 制度文档各取最新 10 条。
     *
     * <p>四张表各一条 LIMIT 10 查询，不用分页——面板只回答「最近有什么」，
     * 完整列表在各管理页。用户名用一次批量查询填充，避免逐条 N+1。</p>
     */
    public DashboardRecentVO recent() {
        DashboardRecentVO vo = new DashboardRecentVO();

        List<Track> tracks = trackMapper.selectList(new LambdaQueryWrapper<Track>()
                .orderByDesc(Track::getCreateTime)
                .orderByDesc(Track::getId)
                .last("LIMIT " + RECENT_LIMIT));
        Map<Long, String> trackNames = userNames(
                tracks.stream().map(Track::getUserId).distinct().toList());
        vo.setRecentTracks(tracks.stream().map(t -> {
            DashboardRecentVO.RecentTrack item = new DashboardRecentVO.RecentTrack();
            item.setId(t.getId());
            item.setName(t.getName());
            item.setUsername(trackNames.get(t.getUserId()));
            item.setStartTime(t.getStartTime());
            item.setCreateTime(t.getCreateTime());
            return item;
        }).toList());

        List<WorkEvent> events = workEventMapper.selectList(new LambdaQueryWrapper<WorkEvent>()
                .eq(WorkEvent::getConfirmStatus, 0)
                .orderByDesc(WorkEvent::getCreateTime)
                .orderByDesc(WorkEvent::getEventId)
                .last("LIMIT " + RECENT_LIMIT));
        Map<Long, String> eventNames = userNames(
                events.stream().map(WorkEvent::getUserId).distinct().toList());
        vo.setPendingEvents(events.stream().map(e -> {
            DashboardRecentVO.RecentEvent item = new DashboardRecentVO.RecentEvent();
            item.setEventId(e.getEventId());
            item.setUsername(eventNames.get(e.getUserId()));
            item.setEventType(e.getEventType());
            item.setPoiName(e.getPoiName());
            item.setSemanticLabel(e.getSemanticLabel());
            item.setStartTime(e.getStartTime());
            item.setCreateTime(e.getCreateTime());
            return item;
        }).toList());

        List<OperationLog> logs = operationLogMapper.selectList(new LambdaQueryWrapper<OperationLog>()
                .orderByDesc(OperationLog::getCreateTime)
                .orderByDesc(OperationLog::getId)
                .last("LIMIT " + RECENT_LIMIT));
        vo.setRecentLogs(logs.stream().map(l -> {
            DashboardRecentVO.RecentLog item = new DashboardRecentVO.RecentLog();
            item.setId(l.getId());
            item.setUsername(l.getUsername());
            item.setAction(l.getAction());
            item.setTargetName(l.getTargetName());
            item.setDetail(l.getDetail());
            item.setCreateTime(l.getCreateTime());
            return item;
        }).toList());

        // 只取启用版本：与知识库列表页口径一致——面板展示了已停用文档，
        // 用户点进列表页反而找不到这一条
        List<PolicyDocument> docs = policyDocumentMapper.selectList(new LambdaQueryWrapper<PolicyDocument>()
                .eq(PolicyDocument::getStatus, 1)
                .orderByDesc(PolicyDocument::getCreateTime)
                .orderByDesc(PolicyDocument::getDocId)
                .last("LIMIT " + RECENT_LIMIT));
        vo.setRecentDocuments(docs.stream().map(d -> {
            DashboardRecentVO.RecentDocument item = new DashboardRecentVO.RecentDocument();
            item.setDocId(d.getDocId());
            item.setTitle(d.getTitle());
            item.setVersion(d.getVersion());
            item.setCreateTime(d.getCreateTime());
            return item;
        }).toList());

        return vo;
    }

    /** 批量查用户名；空集合不查库。 */
    private Map<Long, String> userNames(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }

    private User getUserOrThrow(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        return user;
    }

}
