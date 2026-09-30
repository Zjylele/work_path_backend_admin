package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.entity.ComplianceCheck;
import com.workpath.backend.service.ComplianceCheckService;
import com.workpath.backend.service.ComplianceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 合规核查结论（M5-W6 产出）。列表给管理端看「谁、哪天、命中什么规则、
 * 引用了哪条条款、回查是否通过」；result=2 的结论经人工复核后可手动下发工单。
 */
@Tag(name = "合规核查", description = "核查结论列表、异常轨迹扫描、人工补发工单")
@RestController
@RequestMapping("/api/admin/compliance-checks")
@RequiredArgsConstructor
public class ComplianceCheckController {

    private final ComplianceCheckService checkService;
    private final ComplianceService complianceService;

    @Operation(summary = "核查结论列表")
    @GetMapping
    public Result<List<ComplianceCheck>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
            @RequestParam(required = false) Integer result) {
        return Result.success(checkService.list(userId, workDate, result));
    }

    @Operation(summary = "扫描异常轨迹（ABNORMAL：已分析但无任何停留）")
    @PostMapping("/scan-abnormal")
    public Result<Map<String, Integer>> scanAbnormal() {
        return Result.success(Map.of("created", complianceService.scanAbnormalTracks()));
    }

    @Operation(summary = "人工复核后手动下发工单（result=2 的结论）")
    @PostMapping("/{id}/order")
    public Result<Map<String, Long>> createOrder(@PathVariable("id") Long id) {
        return Result.success(Map.of("orderId", complianceService.createOrderFromCheck(id)));
    }
}
