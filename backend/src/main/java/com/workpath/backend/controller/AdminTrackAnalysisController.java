package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.dto.TrackAnalysisBatchDTO;
import com.workpath.backend.service.AnalysisTaskQueue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "AI 轨迹分析", description = "管理员手动选择轨迹并启动分析")
@RestController
@RequestMapping("/api/admin/ai-analysis")
@RequiredArgsConstructor
public class AdminTrackAnalysisController {

    private final AnalysisTaskQueue analysisTaskQueue;

    @Operation(summary = "手动启动一批轨迹分析")
    @PostMapping("/start")
    public Result<Map<String, Object>> start(@Valid @RequestBody TrackAnalysisBatchDTO dto) {
        analysisTaskQueue.startManualBatch(dto.getTrackIds());
        return Result.success(Map.of("trackIds", dto.getTrackIds(), "count", dto.getTrackIds().size()));
    }
}
