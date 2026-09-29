package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.dto.ModelProviderSaveDTO;
import com.workpath.backend.service.ModelProviderService;
import com.workpath.backend.vo.ModelProviderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型接入配置。
 *
 * <p>读写都要求超级管理员身份，校验在 Service 层完成（对应 UserContext.getSuperAdmin() == 1）。
 * 之所以不放 Controller 而放 Service，是因为超管判定与业务数据校验放在一起，
 * 避免别的入口绕过校验直接调 Service。</p>
 */
@Tag(name = "模型接入配置", description = "语言模型/向量化/逆地理编码的密钥与参数配置（需超级管理员）")
@RestController
@RequestMapping("/api/admin/model-providers")
@RequiredArgsConstructor
public class ModelProviderController {

    private final ModelProviderService modelProviderService;

    @Operation(summary = "查询全部模型接入配置（密钥脱敏）")
    @GetMapping
    public Result<List<ModelProviderVO>> list() {
        return Result.success(modelProviderService.listAll());
    }

    @Operation(summary = "按用途查询配置（CHAT/EMBEDDING/GEO）")
    @GetMapping("/{purpose}")
    public Result<ModelProviderVO> get(@PathVariable String purpose) {
        return Result.success(modelProviderService.getByPurpose(purpose));
    }

    @Operation(summary = "保存模型接入配置")
    @PostMapping
    public Result<Void> save(@RequestBody ModelProviderSaveDTO dto) {
        modelProviderService.save(dto);
        return Result.success();
    }

    @Operation(summary = "测试连接（不落库密钥以外的字段）")
    @PostMapping("/{purpose}/test")
    public Result<String> test(@PathVariable String purpose, @RequestBody ModelProviderSaveDTO dto) {
        String err = modelProviderService.testConnection(purpose, dto);
        return Result.success(err);
    }
}
