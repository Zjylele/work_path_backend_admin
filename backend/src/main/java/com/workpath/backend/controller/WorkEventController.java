package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.service.WorkEventService;
import com.workpath.backend.vo.PageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作事件（M4 产出的人工复核入口）。
 *
 * <p>确认与驳回是「AI 结论 → 管理依据」的开关：M5 的违规判定只认
 * confirm_status=1 的事件。这是论文强调的「管理辅助工具」定位在接口层的体现。</p>
 */
@Tag(name = "工作事件", description = "AI 分析产出的事件列表与人工复核")
@RestController
@RequestMapping("/api/admin/work-events")
@RequiredArgsConstructor
public class WorkEventController {

    private final WorkEventService workEventService;

    @GetMapping("/track-groups")
    public Result<PageVO<java.util.Map<String, Object>>> trackGroups(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Integer confirmStatus) {
        return Result.success(workEventService.listTracks(confirmStatus, page, pageSize));
    }

    @Operation(summary = "工作事件列表（分页）")
    @GetMapping
    public Result<PageVO<WorkEvent>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Integer confirmStatus) {
        return Result.success(workEventService.list(confirmStatus, page, pageSize));
    }

    @Operation(summary = "工作事件详情")
    @GetMapping("/{id}")
    public Result<WorkEvent> detail(@PathVariable("id") Long id) {
        return Result.success(workEventService.detail(id));
    }

    @Operation(summary = "某条轨迹的工作事件列表（分析详情页：轨迹线 + 事件时序同屏）")
    @GetMapping("/tracks/{trackId}")
    public Result<List<WorkEvent>> listByTrack(@PathVariable("trackId") Long trackId) {
        return Result.success(workEventService.listByTrack(trackId));
    }

    @Operation(summary = "确认事件（AI 结论转为管理依据，并触发合规核查）")
    @PostMapping("/{id}/confirm")
    public Result<WorkEventService.ConfirmResult> confirm(@PathVariable("id") Long id) {
        // 返回核查执行情况：核查失败时确认仍然成功，但调用方要能区分提示，
        // 不能像以前那样一律显示「已确认」而掩盖核查静默失效
        return Result.success(workEventService.confirm(id));
    }

    @Operation(summary = "驳回事件")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable("id") Long id) {
        workEventService.reject(id);
        return Result.success();
    }

    @Operation(summary = "撤销确认（已确认 → 待确认；存在未闭环工单时拒绝）")
    @PostMapping("/{id}/revoke")
    public Result<Void> revoke(@PathVariable("id") Long id) {
        workEventService.revoke(id);
        return Result.success();
    }

    @Operation(summary = "触发轨迹重新分析（提交后由手动分析批次执行）")
    @PostMapping("/tracks/{trackId}/reanalyze")
    public Result<Void> reanalyze(@PathVariable("trackId") Long trackId) {
        workEventService.reanalyze(trackId);
        return Result.success();
    }
}
