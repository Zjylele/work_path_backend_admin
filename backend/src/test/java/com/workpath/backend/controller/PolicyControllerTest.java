package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.dto.PolicyAskDTO;
import com.workpath.backend.dto.PolicyUploadDTO;
import com.workpath.backend.entity.PolicyClause;
import com.workpath.backend.entity.PolicyDocument;
import com.workpath.backend.service.ClauseQaService;
import com.workpath.backend.service.PolicyClauseService;
import com.workpath.backend.service.PolicyDocumentService;
import com.workpath.backend.vo.PolicyDocumentPreviewVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 制度知识库控制层测试。
 *
 * <p>只验证"参数怎么进服务层、结果怎么包成 Result"这一层 plumbing——
 * 业务规则（切分、向量化、级联停用）已在 PolicyDocumentServiceTest 覆盖。
 * 这里钉住的是接口契约：路径参数该传给哪个方法、出参结构里带什么字段。</p>
 */
class PolicyControllerTest {

    private final PolicyDocumentService documentService = Mockito.mock(PolicyDocumentService.class);
    private final PolicyClauseService clauseService = Mockito.mock(PolicyClauseService.class);
    private final ClauseQaService clauseQaService = Mockito.mock(ClauseQaService.class);
    private final PolicyController controller =
            new PolicyController(documentService, clauseService, clauseQaService);

    @Test
    @DisplayName("文件上传接口：文件名与字节透传给服务层，标题/分类可空")
    void uploadFileDelegates() {
        PolicyDocument doc = new PolicyDocument();
        doc.setDocId(11L);
        doc.setVersion("v1.0");
        doc.setTitle("外勤管理办法");
        Mockito.when(documentService.uploadFile(
                        Mockito.eq("外勤管理办法.txt"), Mockito.any(byte[].class),
                        Mockito.isNull(), Mockito.eq("考勤")))
                .thenReturn(doc);
        MockMultipartFile file = new MockMultipartFile(
                "file", "外勤管理办法.txt", "text/plain", "1.1 内容内容内容。".getBytes());

        Result<Map<String, Object>> res = controller.uploadFile(file, null, "考勤");

        assertThat(res.getCode()).isEqualTo(200);
        assertThat(res.getData().get("docId")).isEqualTo(11L);
        assertThat(res.getData().get("version")).isEqualTo("v1.0");
        assertThat(res.getData().get("title")).isEqualTo("外勤管理办法");
    }

    @Test
    @DisplayName("上传接口返回 docId 与 version，方便前端直接展示")
    void uploadReturnsDocId() {
        PolicyDocument doc = new PolicyDocument();
        doc.setDocId(7L);
        doc.setVersion("v1.0");
        doc.setTitle("外勤管理制度");
        Mockito.when(documentService.upload(Mockito.any(PolicyUploadDTO.class))).thenReturn(doc);

        PolicyUploadDTO dto = new PolicyUploadDTO();
        dto.setTitle("外勤管理制度");
        dto.setContent("1.1 外勤人员应提前报备，经部门负责人批准后方可出行。");
        Result<Map<String, Object>> res = controller.upload(dto);

        assertThat(res.getCode()).isEqualTo(200);
        assertThat(res.getData().get("docId")).isEqualTo(7L);
        assertThat(res.getData().get("version")).isEqualTo("v1.0");

        ArgumentCaptor<PolicyUploadDTO> cap = ArgumentCaptor.forClass(PolicyUploadDTO.class);
        Mockito.verify(documentService).upload(cap.capture());
        assertThat(cap.getValue().getTitle()).isEqualTo("外勤管理制度");
    }

    @Test
    @DisplayName("列表接口把 category 透传给服务层，不传则为 null")
    void listPassesCategory() {
        Mockito.when(documentService.list(Mockito.any())).thenReturn(List.of(new PolicyDocument()));

        controller.list("考勤");
        Mockito.verify(documentService).list("考勤");

        controller.list(null);
        Mockito.verify(documentService).list(null);
    }

    @Test
    @DisplayName("条款列表与检索结果按 List<PolicyClause> 原样返回")
    void clausesAndSearch() {
        PolicyClause c = new PolicyClause();
        c.setClauseCode("1.1");
        Mockito.when(documentService.clauses(2L)).thenReturn(List.of(c));
        Mockito.when(clauseService.search("报备", 5)).thenReturn(List.of(c));

        Result<List<PolicyClause>> clauses = controller.clauses(2L);
        assertThat(clauses.getData()).extracting(PolicyClause::getClauseCode).containsExactly("1.1");

        Result<List<PolicyClause>> search = controller.search("报备", 5);
        assertThat(search.getData()).extracting(PolicyClause::getClauseCode).containsExactly("1.1");
    }

    @Test
    @DisplayName("停用接口把 id 传给服务层，返回标准成功结构")
    void disableDelegates() {
        Result<Void> res = controller.disable(9L);
        assertThat(res.getCode()).isEqualTo(200);
        Mockito.verify(documentService).disable(9L);
    }

    @Test
    @DisplayName("reindex 返回本次更新的条数")
    void reindexReturnsCount() {
        Mockito.when(clauseService.reindex(3L)).thenReturn(4);
        Result<Map<String, Object>> res = controller.reindex(3L);
        assertThat(res.getData().get("updated")).isEqualTo(4);
    }

    @Test
    @DisplayName("预览接口返回内容与来源标注，来源决定前端是否提示「条款拼合视图」")
    void previewReturnsContentAndSource() {
        PolicyDocumentPreviewVO vo = new PolicyDocumentPreviewVO();
        vo.setDocId(9L);
        vo.setTitle("外勤管理制度");
        vo.setContent("1.1 外勤人员应提前报备。");
        vo.setSource(PolicyDocumentPreviewVO.SOURCE_CLAUSES);
        Mockito.when(documentService.preview(9L)).thenReturn(vo);

        Result<PolicyDocumentPreviewVO> res = controller.preview(9L);

        assertThat(res.getCode()).isEqualTo(200);
        assertThat(res.getData().getDocId()).isEqualTo(9L);
        assertThat(res.getData().getContent()).isEqualTo("1.1 外勤人员应提前报备。");
        assertThat(res.getData().getSource()).isEqualTo("clauses");
        Mockito.verify(documentService).preview(9L);
    }

    @Test
    @DisplayName("问答接口把答案与引用一起返回")
    void askReturnsAnswerAndCitations() {
        ClauseQaService.Citation citation = new ClauseQaService.Citation(
                11L, 7L, "1.1", "外勤人员管理制度", "v1.0", "外勤人员应提前一个工作日报备。", 0.83);
        ClauseQaService.Answer answer = new ClauseQaService.Answer(
                "需要提前报备，依据条款 1.1。", List.of(citation), true);
        Mockito.when(clauseQaService.ask("外出要报备吗", 5)).thenReturn(answer);

        PolicyAskDTO dto = new PolicyAskDTO();
        dto.setQuestion("外出要报备吗");
        dto.setTopK(5);
        Result<ClauseQaService.Answer> res = controller.ask(dto);

        assertThat(res.getCode()).isEqualTo(200);
        assertThat(res.getData().answered()).isTrue();
        assertThat(res.getData().text()).contains("依据条款 1.1");
        assertThat(res.getData().citations()).extracting(ClauseQaService.Citation::clauseId)
                .containsExactly(11L);
    }

    @Test
    @DisplayName("未检索到条款时 answered=false，答案说明没找到依据")
    void askWithoutContext() {
        // topK 不传时为 null，mock 必须用 any() 才能匹配（anyInt() 不匹配 null）
        Mockito.when(clauseQaService.ask(Mockito.anyString(), Mockito.any()))
                .thenReturn(new ClauseQaService.Answer("未在制度知识库中检索到相关内容", List.of(), false));

        PolicyAskDTO dto = new PolicyAskDTO();
        dto.setQuestion("公司食堂几点开饭");
        Result<ClauseQaService.Answer> res = controller.ask(dto);

        assertThat(res.getData().answered()).isFalse();
        assertThat(res.getData().citations()).isEmpty();
    }
}
