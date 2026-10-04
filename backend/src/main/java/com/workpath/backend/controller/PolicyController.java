package com.workpath.backend.controller;

import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.Result;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.dto.PolicyAskDTO;
import com.workpath.backend.dto.PolicyUploadDTO;
import com.workpath.backend.entity.PolicyClause;
import com.workpath.backend.entity.PolicyDocument;
import com.workpath.backend.service.ClauseQaService;
import com.workpath.backend.service.PolicyClauseService;
import com.workpath.backend.service.PolicyDocumentService;
import com.workpath.backend.vo.PolicyDocumentPreviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 制度知识库（M6）。
 *
 * <p>路径挂在 /api/admin/policy 下，AuthInterceptor 已对整个 /api/admin 前缀
 * 校验 ADMIN 角色；上传与停用在 Service 层再校验操作人存在。制度是合规核查的
 * 依据，所以写操作一律要求登录用户留痕，不接受匿名。</p>
 */
@Tag(name = "制度知识库", description = "制度文档上传、条款查看与语义检索")
@RestController
@RequestMapping("/api/admin/policy")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyDocumentService documentService;
    private final PolicyClauseService clauseService;
    private final ClauseQaService clauseQaService;

    @Operation(summary = "上传制度文档（自动切分条款并向量化）")
    @PostMapping("/documents")
    public Result<Map<String, Object>> upload(@RequestBody PolicyUploadDTO dto) {
        PolicyDocument doc = documentService.upload(dto);
        Map<String, Object> data = new HashMap<>(4);
        data.put("docId", doc.getDocId());
        data.put("version", doc.getVersion());
        data.put("title", doc.getTitle());
        return Result.success(data);
    }

    @Operation(summary = "上传制度文档文件（.txt / .md / .docx，自动提取原文并切分）")
    @PostMapping("/documents/upload-file")
    public Result<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String category) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            // 读流失败按参数问题反馈，不让 IOException 裸抛成 500
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "读取上传文件失败：" + e.getMessage());
        }
        PolicyDocument doc = documentService.uploadFile(
                file.getOriginalFilename(), bytes, title, category);
        Map<String, Object> data = new HashMap<>(4);
        data.put("docId", doc.getDocId());
        data.put("version", doc.getVersion());
        data.put("title", doc.getTitle());
        return Result.success(data);
    }

    @Operation(summary = "制度文档列表")
    @GetMapping("/documents")
    public Result<List<PolicyDocument>> list(
            @RequestParam(required = false) String category) {
        return Result.success(documentService.list(category));
    }

    @Operation(summary = "查看某文档的条款列表（含向量化状态）")
    @GetMapping("/documents/{id}/clauses")
    public Result<List<PolicyClause>> clauses(@PathVariable("id") Long id) {
        return Result.success(documentService.clauses(id));
    }

    @Operation(summary = "预览制度文档（原文优先；加列前的存量文档按条款拼合并标注来源）")
    @GetMapping("/documents/{id}/preview")
    public Result<PolicyDocumentPreviewVO> preview(@PathVariable("id") Long id) {
        return Result.success(documentService.preview(id));
    }

    @Operation(summary = "停用制度文档（级联停用其条款）")
    @DeleteMapping("/documents/{id}")
    public Result<Void> disable(@PathVariable("id") Long id) {
        documentService.disable(id);
        return Result.success();
    }

    @Operation(summary = "重新向量化（补算缺失向量、按当前模型重算）")
    @PostMapping("/documents/{id}/reindex")
    public Result<Map<String, Object>> reindex(@PathVariable("id") Long id) {
        int updated = clauseService.reindex(id);
        Map<String, Object> data = new HashMap<>(2);
        data.put("updated", updated);
        return Result.success(data);
    }

    @Operation(summary = "按自然语言检索制度条款")
    @GetMapping("/clauses/search")
    public Result<List<PolicyClause>> search(
            @RequestParam String query,
            @RequestParam(required = false) Integer topK) {
        return Result.success(clauseService.search(query, topK));
    }

    @Operation(summary = "制度条款问答（RAG：检索条款后交由 CHAT 模型作答）")
    @PostMapping("/clauses/ask")
    public Result<ClauseQaService.Answer> ask(@Valid @RequestBody PolicyAskDTO dto) {
        return Result.success(clauseQaService.ask(dto.getQuestion(), dto.getTopK()));
    }
}
