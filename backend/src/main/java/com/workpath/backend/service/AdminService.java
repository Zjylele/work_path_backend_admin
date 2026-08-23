package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.TrackPoint;
import com.workpath.backend.entity.User;
import com.workpath.backend.entity.OperationLog;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.OperationLogMapper;
import com.workpath.backend.mapper.TrackMapper;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.UserMapper;
import com.workpath.backend.vo.AdminTrackDetailVO;
import com.workpath.backend.vo.AdminTrackVO;
import com.workpath.backend.vo.AdminUserVO;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserMapper userMapper;
    private final TrackMapper trackMapper;
    private final TrackPointMapper trackPointMapper;
    private final OperationLogMapper operationLogMapper;
    private final OperationLogService operationLogService;

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

    private User getUserOrThrow(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        return user;
    }

}
