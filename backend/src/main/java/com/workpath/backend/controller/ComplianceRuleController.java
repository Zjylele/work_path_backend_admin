package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.entity.ComplianceRule;
import com.workpath.backend.service.ComplianceRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 合规规则配置（M5-W5）。挂在 /api/admin 下，仅管理员可操作——
 * 规则决定「什么算违规」，这种定义权不能下放。
 */
@Tag(name = "合规规则", description = "M5 规则的配置管理")
@RestController
@RequestMapping("/api/admin/rules")
@RequiredArgsConstructor
public class ComplianceRuleController {

    private final ComplianceRuleService ruleService;

    @Operation(summary = "规则列表")
    @GetMapping
    public Result<List<ComplianceRule>> list(@RequestParam(required = false) Integer enabled) {
        return Result.success(ruleService.list(enabled));
    }

    @Operation(summary = "新建规则")
    @PostMapping
    public Result<Map<String, Long>> create(@RequestBody ComplianceRule rule) {
        return Result.success(Map.of("ruleId", ruleService.create(rule)));
    }

    @Operation(summary = "更新规则")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable("id") Long id, @RequestBody ComplianceRule rule) {
        ruleService.update(id, rule);
        return Result.success();
    }

    @Operation(summary = "删除规则（被结论引用时拒绝，可改为停用）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        ruleService.delete(id);
        return Result.success();
    }
}
