package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.dto.TrackUploadDTO;
import com.workpath.backend.service.TrackService;
import com.workpath.backend.vo.PageVO;
import com.workpath.backend.vo.TrackDetailVO;
import com.workpath.backend.vo.TrackEventVO;
import com.workpath.backend.vo.TrackListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "轨迹", description = "手机端轨迹上传、列表、详情、删除")
@RestController
@RequestMapping("/api/track")
@RequiredArgsConstructor
public class TrackController {

    private final TrackService trackService;

    @Operation(summary = "上传轨迹")
    @PostMapping("/upload")
    public Result<Map<String, Long>> upload(@Valid @RequestBody TrackUploadDTO dto) {
        Long id = trackService.upload(dto);
        return Result.success(Map.of("id", id));
    }

    @Operation(summary = "我的轨迹列表")
    @GetMapping("/list")
    public Result<PageVO<TrackListVO>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(PageVO.of(trackService.list(page, pageSize)));
    }

    @Operation(summary = "轨迹详情")
    @GetMapping("/{id}")
    public Result<TrackDetailVO> detail(@PathVariable Long id) {
        return Result.success(trackService.detail(id));
    }

    @Operation(summary = "轨迹的语义标注结果")
    @GetMapping("/{id}/events")
    public Result<List<TrackEventVO>> events(@PathVariable("id") Long id) {
        return Result.success(trackService.listEvents(id));
    }

    @Operation(summary = "删除轨迹")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        trackService.delete(id);
        return Result.success();
    }

}
