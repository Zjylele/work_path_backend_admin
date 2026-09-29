package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.dto.UserPasswordDTO;
import com.workpath.backend.dto.UserRoleDTO;
import com.workpath.backend.dto.UserStatusDTO;
import com.workpath.backend.entity.OperationLog;
import com.workpath.backend.service.AdminService;
import com.workpath.backend.vo.AdminTrackDetailVO;
import com.workpath.backend.vo.AdminTrackVO;
import com.workpath.backend.vo.AdminUserVO;
import com.workpath.backend.vo.PageVO;
import com.workpath.backend.vo.StatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "管理端", description = "用户管理、轨迹管理、数据统计（需 ADMIN 角色）")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @Operation(summary = "用户列表")
    @GetMapping("/users")
    public Result<PageVO<AdminUserVO>> users(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String keyword) {
        return Result.success(PageVO.of(adminService.userList(page, pageSize, keyword)));
    }

    @Operation(summary = "更新用户状态（0 禁用 / 1 启用）")
    @PutMapping("/users/{id}/status")
    public Result<Void> updateUserStatus(@PathVariable Long id, @Valid @RequestBody UserStatusDTO dto) {
        adminService.updateUserStatus(id, dto.getStatus());
        return Result.success();
    }

    @Operation(summary = "重置用户密码")
    @PutMapping("/users/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody UserPasswordDTO dto) {
        adminService.resetPassword(id, dto.getPassword());
        return Result.success();
    }

    @Operation(summary = "删除用户（级联删除其轨迹，仅超级管理员）")
    @DeleteMapping("/users/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        adminService.deleteUser(id);
        return Result.success();
    }

    @Operation(summary = "修改用户角色（授予/撤销管理员，仅超级管理员）")
    @PutMapping("/users/{id}/role")
    public Result<Void> changeUserRole(@PathVariable Long id, @Valid @RequestBody UserRoleDTO dto) {
        adminService.changeRole(id, dto.getRole());
        return Result.success();
    }

    @Operation(summary = "全量轨迹列表")
    @GetMapping("/tracks")
    public Result<PageVO<AdminTrackVO>> tracks(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return Result.success(PageVO.of(adminService.trackList(page, pageSize, userId, startTime, endTime)));
    }

    @Operation(summary = "轨迹详情")
    @GetMapping("/tracks/{id}")
    public Result<AdminTrackDetailVO> trackDetail(@PathVariable Long id) {
        return Result.success(adminService.trackDetail(id));
    }

    @Operation(summary = "删除轨迹")
    @DeleteMapping("/tracks/{id}")
    public Result<Void> deleteTrack(@PathVariable Long id) {
        adminService.deleteTrack(id);
        return Result.success();
    }

    @Operation(summary = "数据统计")
    @GetMapping("/stats")
    public Result<StatsVO> stats() {
        return Result.success(adminService.stats());
    }

    @Operation(summary = "操作日志列表")
    @GetMapping("/logs")
    public Result<PageVO<OperationLog>> logs(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return Result.success(PageVO.of(adminService.logList(page, pageSize, username, action, startTime, endTime)));
    }

    @Operation(summary = "删除单条操作日志")
    @DeleteMapping("/logs/{id}")
    public Result<Void> deleteLog(@PathVariable Long id) {
        adminService.deleteLog(id);
        return Result.success();
    }

    @Operation(summary = "批量删除操作日志（body 传 id 数组，缺省/空数组=清空全部）")
    @DeleteMapping("/logs")
    public Result<Void> deleteLogs(@RequestBody(required = false) List<Long> ids) {
        adminService.deleteLogs(ids);
        return Result.success();
    }

}
