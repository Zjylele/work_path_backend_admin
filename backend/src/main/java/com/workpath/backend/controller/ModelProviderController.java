package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.dto.ModelProviderSaveDTO;
import com.workpath.backend.service.ModelProviderService;
import com.workpath.backend.vo.ModelProviderVO;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型接入配置（一用途多模型）。
 *
 * <p>每个用途（CHAT/EMBEDDING/GEO）可挂多条配置，其中 selected=1 的一条是
 * 「当前使用」。三个用途卡片的下拉切换走 /select；全部模型列表走本控制器的
 * 增删改与 /test。读写都要求超级管理员身份，校验在 Service 层完成
 * （对应 UserContext.getSuperAdmin() == 1）——超管判定与业务校验放在一起，
 * 避免别的入口绕过校验直接调 Service。</p>
 */
@Tag(name = "模型接入配置", description = "语言模型/向量化/逆地理编码的多模型管理（需超级管理员）")
@RestController
@RequestMapping("/api/admin/model-providers")
@RequiredArgsConstructor
public class ModelProviderController {

    private final ModelProviderService modelProviderService;

    @Operation(summary = "查询全部模型接入配置（密钥脱敏，含当前使用标记）")
    @GetMapping
    public Result<List<ModelProviderVO>> list() {
        return Result.success(modelProviderService.listAll());
    }

    @Operation(summary = "添加模型")
    @PostMapping
    public Result<Map<String, Long>> create(@RequestBody ModelProviderSaveDTO dto) {
        return Result.success(Map.of("id", modelProviderService.create(dto)));
    }

    @Operation(summary = "更改模型配置（密钥留空保持原值，模型类型不可改）")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ModelProviderSaveDTO dto) {
        modelProviderService.update(id, dto);
        return Result.success();
    }

    @Operation(summary = "删除模型（当前使用中的需先切换）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        modelProviderService.delete(id);
        return Result.success();
    }

    @Operation(summary = "切换为该用途的当前使用模型")
    @PostMapping("/{id}/select")
    public Result<Void> select(@PathVariable Long id) {
        modelProviderService.select(id);
        return Result.success();
    }

    @Operation(summary = "测试该模型的连通性（body 可省略，默认测已保存的值）")
    @PostMapping("/{id}/test")
    public Result<String> test(@PathVariable Long id,
                               @RequestBody(required = false) ModelProviderSaveDTO override) {
        return Result.success(modelProviderService.testById(id, override));
    }
}
