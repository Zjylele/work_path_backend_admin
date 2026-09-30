package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.dto.CloseOrderDTO;
import com.workpath.backend.dto.ReviewOrderDTO;
import com.workpath.backend.entity.RectificationOrder;
import com.workpath.backend.service.RectificationOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 整改工单·管理端（M5-W7）。状态变更的闭环动作都在这里：
 * 确认整改（1→2）与申诉复核（3→2）。员工侧入口在 MyOrderController。
 */
@Tag(name = "整改工单·管理端", description = "工单列表、确认整改、申诉复核")
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final RectificationOrderService orderService;

    @Operation(summary = "工单列表")
    @GetMapping
    public Result<List<RectificationOrder>> list(@RequestParam(required = false) Integer status) {
        return Result.success(orderService.list(status));
    }

    @Operation(summary = "确认整改（1→2，处理中才可闭环）")
    @PostMapping("/{id}/close")
    public Result<Void> close(@PathVariable("id") Long id,
                              @RequestBody(required = false) CloseOrderDTO dto) {
        orderService.close(id, dto == null ? null : dto.getReviewNote());
        return Result.success();
    }

    @Operation(summary = "复核申诉（3→2，维持原结论或接受申诉）")
    @PostMapping("/{id}/review")
    public Result<Void> review(@PathVariable("id") Long id, @Valid @RequestBody ReviewOrderDTO dto) {
        orderService.review(id, dto.getAccept(), dto.getReviewNote());
        return Result.success();
    }
}
