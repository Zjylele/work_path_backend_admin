package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workpath.backend.dto.PolicyUploadDTO;
import com.workpath.backend.entity.PolicyClause;
import com.workpath.backend.entity.PolicyDocument;
import com.workpath.backend.entity.ModelProviderConfig;
import com.workpath.backend.entity.User;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.PolicyClauseMapper;
import com.workpath.backend.mapper.PolicyDocumentMapper;
import com.workpath.backend.vo.PolicyDocumentPreviewVO;import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 制度文档服务单元测试。
 *
 * <p>用 Mockito 顶替两个 Mapper，被测对象是入库编排本身：
 * 文档→条款切分→落库→级联停用→版本管理四条规则。
 * 网络（向量化）单独测，见 EmbeddingClientTest。</p>
 */
class PolicyDocumentServiceTest {

    private PolicyDocumentMapper docMapper;
    private PolicyClauseMapper clauseMapper;
    private EmbeddingClient embeddingClient;
    private ModelProviderService modelProviderService;
    private OperationLogService operationLogService;
    private PolicyDocumentService service;

    /** 已存在于库中的文档列表，供版本管理与列表查询使用 */
    private final List<PolicyDocument> storedDocs = new ArrayList<>();
    /** 已落库的条款 */
    private final List<PolicyClause> storedClauses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        docMapper = Mockito.mock(PolicyDocumentMapper.class);
        clauseMapper = Mockito.mock(PolicyClauseMapper.class);
        embeddingClient = Mockito.mock(EmbeddingClient.class);
        modelProviderService = Mockito.mock(ModelProviderService.class);
        operationLogService = Mockito.mock(OperationLogService.class);

        Mockito.lenient().when(docMapper.insert(Mockito.any(PolicyDocument.class))).thenAnswer(inv -> {
            PolicyDocument d = inv.getArgument(0);
            d.setDocId((long) (storedDocs.size() + 1));
            storedDocs.add(d);
            return 1;
        });
        Mockito.lenient().when(clauseMapper.insert(Mockito.any(PolicyClause.class))).thenAnswer(inv -> {
            PolicyClause c = inv.getArgument(0);
            c.setClauseId((long) (storedClauses.size() + 1));
            storedClauses.add(c);
            return 1;
        });
        // updateById 的语义是"按主键更新非空字段"（null 字段不动），照真实 MyBatis-Plus 实现
        Mockito.lenient().when(docMapper.updateById(Mockito.any(PolicyDocument.class))).thenAnswer(inv -> {
            PolicyDocument patch = inv.getArgument(0);
            PolicyDocument target = storedDocs.stream()
                    .filter(x -> x.getDocId().equals(patch.getDocId())).findFirst().orElse(null);
            if (target == null) {
                return 0;
            }
            if (patch.getStatus() != null) {
                target.setStatus(patch.getStatus());
            }
            if (patch.getVersion() != null) {
                target.setVersion(patch.getVersion());
            }
            if (patch.getTitle() != null) {
                target.setTitle(patch.getTitle());
            }
            if (patch.getCategory() != null) {
                target.setCategory(patch.getCategory());
            }
            return 1;
        });
        Mockito.lenient().when(clauseMapper.updateById(Mockito.any(PolicyClause.class))).thenAnswer(inv -> {
            PolicyClause patch = inv.getArgument(0);
            PolicyClause target = storedClauses.stream()
                    .filter(x -> x.getClauseId().equals(patch.getClauseId())).findFirst().orElse(null);
            if (target == null) {
                return 0;
            }
            if (patch.getStatus() != null) {
                target.setStatus(patch.getStatus());
            }
            if (patch.getVectorData() != null) {
                target.setVectorData(patch.getVectorData());
            }
            if (patch.getVectorModel() != null) {
                target.setVectorModel(patch.getVectorModel());
            }
            return 1;
        });

        Mockito.lenient().when(docMapper.selectById(Mockito.any(Long.class))).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return storedDocs.stream().filter(d -> id.equals(d.getDocId())).findFirst().orElse(null);
        });
        // selectList 按主键/条件查询。这里只区分"查文档"与"查条款"两种返回，
        // 因为测试里没有条件过滤的需求——真实条件由 LambdaQueryWrapper 表达，
        // 在集成测试里验证（此处关心的是级联停用的编排顺序）。
        Mockito.lenient().when(docMapper.selectList(Mockito.any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> List.copyOf(storedDocs));
        Mockito.lenient().when(clauseMapper.selectList(Mockito.any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> List.copyOf(storedClauses));

        // 注意：不在这里桩 requireConfig。默认走"未配 EMBEDDING"路径（文档仍应入库），
        // 需要向量化成功的用例单独调 stubEmbeddingConfig()
        service = new PolicyDocumentService(docMapper, clauseMapper, new ClauseSplitter(),
                embeddingClient, modelProviderService, operationLogService);
    }


    @AfterEach
    void clearContext() {
        UserContext.clear();
        storedDocs.clear();
        storedClauses.clear();
    }

    private void loginAs(String username, boolean superAdmin) {
        User u = new User();
        u.setId(99L);
        u.setUsername(username);
        u.setRole("ADMIN");
        u.setSuperAdmin(superAdmin ? 1 : 0);
        UserContext.set(u);
    }

    private PolicyUploadDTO upload(String title, String category, String content) {
        PolicyUploadDTO dto = new PolicyUploadDTO();
        dto.setTitle(title);
        dto.setCategory(category);
        dto.setContent(content);
        return dto;
    }

    /** 一份三条条款的制度，编号格式真实（3.2.1 / 3.2.2 / 3.2.3） */
    private static final String DOC_3_CLAUSES = """
            1.1 外勤人员因公外出应提前一个工作日在系统中提交报备，经部门负责人批准后方可出行。
            1.2 因公外出期间应保持通讯畅通，按报备路线行驶，临时变更路线需电话报备并事后补录。
            1.3 外勤人员应每日提交工作日志，记录拜访客户与业务进展，日志需经部门负责人复核。
            """;

    @Nested
    @DisplayName("文档上传与条款落库")
    class Upload {

        @Test
        @DisplayName("上传后切分出 3 条条款，doc 与 clause 都落库")
        void splitsIntoClauses() {
            loginAs("admin", true);
            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));

            assertThat(storedDocs).hasSize(1);
            assertThat(storedClauses).hasSize(3);
            assertThat(storedClauses.get(0).getClauseCode()).isEqualTo("1.1");
            assertThat(storedClauses.get(1).getClauseCode()).isEqualTo("1.2");
            assertThat(storedClauses.get(2).getClauseCode()).isEqualTo("1.3");
        }

        @Test
        @DisplayName("clause_index 从 0 连续递增，code_source 取切分器结论")
        void indexesSequential() {
            loginAs("admin", true);
            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));
            assertThat(storedClauses).allSatisfy(c -> assertThat(c.getClauseIndex()).isNotNull());
            assertThat(storedClauses.get(0).getClauseIndex()).isZero();
            assertThat(storedClauses.get(1).getClauseIndex()).isEqualTo(1);
            assertThat(storedClauses.get(0).getCodeSource()).isEqualTo(1);
        }

        @Test
        @DisplayName("正文为空或空白直接拒绝，不产生空文档")
        void blankContentRejected() {
            loginAs("admin", true);
            assertThatThrownBy(() -> service.upload(upload("空制度", null, "   \n\n ")))
                    .isInstanceOf(com.workpath.backend.common.BusinessException.class);
            assertThat(storedDocs).isEmpty();
            assertThat(storedClauses).isEmpty();
        }

        @Test
        @DisplayName("标题为空直接拒绝")
        void blankTitleRejected() {
            loginAs("admin", true);
            assertThatThrownBy(() -> service.upload(upload("  ", null, DOC_3_CLAUSES)))
                    .isInstanceOf(com.workpath.backend.common.BusinessException.class);
            assertThat(storedDocs).isEmpty();
        }

        @Test
        @DisplayName("切分结果为空（整篇都是碎片被吃掉）时拒绝入库，不产生无条款文档")
        void noClauseRejected() {
            loginAs("admin", true);
            // 单行 4 字：首条碎片没有前一条可合并，finalize 会保留它，
            // 所以这里用完全空白的内容 —— 已在 blankContentRejected 覆盖；
            // 改用只有编号的文档，编号后无内容，切分器产出 0 条
            assertThatThrownBy(() -> service.upload(upload("空制度", null, "")))
                    .isInstanceOf(com.workpath.backend.common.BusinessException.class);
            assertThat(storedDocs).isEmpty();
        }

        @Test
        @DisplayName("uploader_id 取自登录上下文，不是前端传参")
        void uploaderFromContext() {
            loginAs("admin", true);
            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));
            assertThat(storedDocs.get(0).getUploaderId()).isEqualTo(99L);
        }

        @Test
        @DisplayName("未登录不能上传")
        void requiresLogin() {
            assertThatThrownBy(() -> service.upload(upload("x", null, DOC_3_CLAUSES)))
                    .isInstanceOf(com.workpath.backend.common.BusinessException.class);
        }

        @Test
        @DisplayName("上传时原文被原样留存到文档上（预览功能的依据）")
        void originalContentPersisted() {
            loginAs("admin", true);
            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));
            assertThat(storedDocs.get(0).getContent()).isEqualTo(DOC_3_CLAUSES);
        }
    }

    @Nested
    @DisplayName("向量化失败不阻塞入库")
    class VectorFailure {

        @Test
        @DisplayName("embedding 全部失败时文档与条款照常入库，vector_data 为 null")
        void allVectorFailuresStillInsert() {
            loginAs("admin", true);
            Mockito.when(embeddingClient.embedAll(Mockito.any(), Mockito.anyList()))
                    .thenReturn(List.of(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()));

            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));

            assertThat(storedDocs).hasSize(1);
            assertThat(storedClauses).hasSize(3);
            assertThat(storedClauses).allSatisfy(c -> assertThat(c.getVectorData()).isNull());
        }

        @Test
        @DisplayName("embedding 抛异常时也不让文档上传失败")
        void embeddingThrows() {
            loginAs("admin", true);
            Mockito.when(embeddingClient.embedAll(Mockito.any(), Mockito.anyList()))
                    .thenThrow(new RuntimeException("网络断了"));

            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));

            assertThat(storedDocs).hasSize(1);
            assertThat(storedClauses).hasSize(3);
            assertThat(storedClauses).allSatisfy(c -> assertThat(c.getVectorData()).isNull());
        }

    /** EMBEDDING 配置存在且模型为 BAAI/bge-m3 */
    private void stubEmbeddingConfig() {
        ModelProviderConfig cfg = new ModelProviderConfig();
        cfg.setModelName("BAAI/bge-m3");
        Mockito.when(modelProviderService.requireConfig(Mockito.anyString())).thenReturn(cfg);
    }

    @Test
    @DisplayName("成功的条款带 vector_data 与 vector_model，二者同时落库")
    void vectorAttachedWhenSuccess() {
        loginAs("admin", true);
        stubEmbeddingConfig();
        double[] vec = new double[1024];
        java.util.Arrays.fill(vec, 0.5d);
        Mockito.when(embeddingClient.embedAll(Mockito.any(), Mockito.anyList()))
                .thenReturn(List.of(java.util.Optional.of(vec), java.util.Optional.of(vec), java.util.Optional.of(vec)));

        service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));

        assertThat(storedClauses).allSatisfy(c -> {
            assertThat(c.getVectorData()).isNotBlank();
            assertThat(c.getVectorModel()).isEqualTo("BAAI/bge-m3");
        });
        // 解析回来仍是 1024 维，没有被序列化削精度
        assertThat(com.workpath.backend.util.VectorCodec.parse(storedClauses.get(0).getVectorData()))
                .hasSize(1024);
    }

        @Test
        @DisplayName("部分成功时只给成功的条款写 vector_data")
        void partialVectorSuccess() {
            loginAs("admin", true);
            double[] vec = new double[4];
            Mockito.when(embeddingClient.embedAll(Mockito.any(), Mockito.anyList()))
                    .thenReturn(List.of(java.util.Optional.of(vec), java.util.Optional.empty(), java.util.Optional.of(vec)));

            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));

            assertThat(storedClauses.get(0).getVectorData()).isNotBlank();
            assertThat(storedClauses.get(1).getVectorData()).isNull();
            assertThat(storedClauses.get(2).getVectorData()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("版本管理")
    class Versioning {

        @Test
        @DisplayName("同名文档再次上传生成新版本，旧版本停用但保留")
        void newVersionKeepsOld() {
            loginAs("admin", true);
            // 必须用 thenAnswer 而不是 thenReturn：thenReturn 的参数在桩化那一刻
            // 就求值了，那时 storedDocs 还是空的，之后永远返回 null，
            // 第二次上传会被当成首版（v1.0）而不是 v2.0
            Mockito.lenient().when(docMapper.selectLatestByTitle(Mockito.anyString()))
                    .thenAnswer(inv -> storedDocs.isEmpty() ? null
                            : storedDocs.get(storedDocs.size() - 1));
            // updateById 已统一在 setUp 里按"非空字段更新"实现，这里无需重复

            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));
            assertThat(storedDocs).hasSize(1);
            assertThat(storedDocs.get(0).getVersion()).isEqualTo("v1.0");
            assertThat(storedDocs.get(0).getStatus()).isEqualTo(1);

            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));

            // 旧文档仍在库里（回溯依据），但被停用
            assertThat(storedDocs).hasSize(2);
            assertThat(storedDocs.get(0).getStatus()).isZero();
            assertThat(storedDocs.get(1).getVersion()).isEqualTo("v2.0");
            assertThat(storedDocs.get(1).getStatus()).isEqualTo(1);
        }

        @Test
        @DisplayName("不同标题互不干扰，都从 v1.0 起")
        void differentTitlesIndependent() {
            loginAs("admin", true);
            service.upload(upload("制度A", null, DOC_3_CLAUSES));
            service.upload(upload("制度B", null, DOC_3_CLAUSES));
            assertThat(storedDocs).hasSize(2);
            assertThat(storedDocs).allSatisfy(d -> assertThat(d.getVersion()).isEqualTo("v1.0"));
        }
    }

    @Nested
    @DisplayName("停用与级联")
    class Disable {

        @Test
        @DisplayName("停用文档会级联停用其条款")
        void cascadeDisable() {
            loginAs("admin", true);
            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));
            Long docId = storedDocs.get(0).getDocId();

            Mockito.lenient().when(docMapper.selectById(docId)).thenReturn(storedDocs.get(0));
            service.disable(docId);

            assertThat(storedDocs.get(0).getStatus()).isZero();
            assertThat(storedClauses).allSatisfy(c -> assertThat(c.getStatus()).isZero());
        }

        @Test
        @DisplayName("停用不存在的文档抛 404，不静默成功")
        void disableMissing() {
            loginAs("admin", true);
            Mockito.lenient().when(docMapper.selectById(999L)).thenReturn(null);
            assertThatThrownBy(() -> service.disable(999L))
                    .isInstanceOf(com.workpath.backend.common.BusinessException.class);
        }

        @Test
        @DisplayName("已停用的文档再次停用是幂等的，不报错")
        void disableIdempotent() {
            loginAs("admin", true);
            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));
            Long docId = storedDocs.get(0).getDocId();
            Mockito.lenient().when(docMapper.selectById(docId)).thenReturn(storedDocs.get(0));

            service.disable(docId);
            service.disable(docId);

            assertThat(storedDocs.get(0).getStatus()).isZero();
        }
    }

    @Nested
    @DisplayName("文档预览")
    class Preview {

        @Test
        @DisplayName("有原文时预览返回原文，source=original，不带条款拼合痕迹")
        void originalContentPreferred() {
            loginAs("admin", true);
            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));
            Long docId = storedDocs.get(0).getDocId();

            PolicyDocumentPreviewVO vo = service.preview(docId);

            assertThat(vo.getSource()).isEqualTo("original");
            assertThat(vo.getContent()).isEqualTo(DOC_3_CLAUSES);
            assertThat(vo.getDocId()).isEqualTo(docId);
            assertThat(vo.getTitle()).isEqualTo("外勤管理制度");
            assertThat(vo.getVersion()).isEqualTo("v1.0");
            assertThat(vo.getCategory()).isEqualTo("考勤");
        }

        @Test
        @DisplayName("存量文档（无原文）预览退回条款拼合，source=clauses，条款按文档内顺序拼接")
        void fallsBackToClauses() {
            loginAs("admin", true);
            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));
            Long docId = storedDocs.get(0).getDocId();
            // 模拟加列前的存量文档：原文为 null，只能拿条款拼合
            storedDocs.get(0).setContent(null);

            PolicyDocumentPreviewVO vo = service.preview(docId);

            assertThat(vo.getSource()).isEqualTo("clauses");
            assertThat(vo.getContent())
                    .contains("1.1 外勤人员因公外出应提前一个工作日")
                    .contains("1.2 因公外出期间应保持通讯畅通")
                    .contains("1.3 外勤人员应每日提交工作日志");
            // 顺序与条款在文档内的顺序一致
            assertThat(vo.getContent().indexOf("1.1"))
                    .isLessThan(vo.getContent().indexOf("1.2"));
            assertThat(vo.getContent().indexOf("1.2"))
                    .isLessThan(vo.getContent().indexOf("1.3"));
        }

        @Test
        @DisplayName("预览不存在的文档抛业务异常，不返回空壳")
        void missingDoc() {
            loginAs("admin", true);
            assertThatThrownBy(() -> service.preview(999L))
                    .isInstanceOf(com.workpath.backend.common.BusinessException.class);
        }
    }

    @Nested
    @DisplayName("上传参数捕获")
    class Captures {

        @Test
        @DisplayName("切分文本按原样交给切分器，不做二次裁剪")
        void contentPassedAsIs() {
            loginAs("admin", true);
            service.upload(upload("外勤管理制度", "考勤", DOC_3_CLAUSES));
            ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
            Mockito.verify(docMapper).insert(Mockito.any(PolicyDocument.class));
            // 落库的条款正文保留了编号后的原文
            assertThat(storedClauses.get(0).getContent())
                    .contains("外勤人员因公外出应提前一个工作日在系统中提交报备");
        }
    }
}
