package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.dto.AppealOrderDTO;
import com.workpath.backend.dto.HandleOrderDTO;
import com.workpath.backend.entity.RectificationOrder;
import com.workpath.backend.interceptor.UserContext;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 整改工单·员工侧（M5-W7，W9 移动端对接）。员工只有两个动作：
 * 提交处理说明（0→1）与申诉（0→3）；关闭工单是管理员的权力。
 */
@Tag(name = "整改工单·员工侧", description = "我的工单、提交处理说明、申诉")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class MyOrderController {

    private final RectificationOrderService orderService;

    @Operation(summary = "我的工单")
    @GetMapping
    public Result<List<RectificationOrder>> my() {
        return Result.success(orderService.myOrders(UserContext.getUserId()));
    }

    @Operation(summary = "提交处理说明（0→1）")
    @PostMapping("/{id}/handle")
    public Result<Void> handle(@PathVariable("id") Long id, @Valid @RequestBody HandleOrderDTO dto) {
        orderService.handle(id, UserContext.getUserId(), dto.getHandleNote());
        return Result.success();
    }

    @Operation(summary = "提交申诉（0→3）")
    @PostMapping("/{id}/appeal")
    public Result<Void> appeal(@PathVariable("id") Long id, @Valid @RequestBody AppealOrderDTO dto) {
        orderService.appeal(id, UserContext.getUserId(), dto.getAppealReason());
        return Result.success();
    }
}
