package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workpath.backend.entity.ModelProviderConfig;
import com.workpath.backend.entity.PolicyClause;
import com.workpath.backend.entity.PolicyDocument;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.PolicyClauseMapper;
import com.workpath.backend.mapper.PolicyDocumentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 条款检索单元测试。
 *
 * <p>相似度计算是 M6 的技术核心（第1章 1.3.4 已论证不走向量数据库、
 * 由应用层算余弦相似度），所以这里逐条钉住这些必须成立的性质：
 * 余弦只看方向、无向量条款跳过、相似度相等时按 index 排序保证可复现、
 * 脏数据跳过而非报错、结果带来源文档标题以支撑引用可追溯。</p>
 */
class PolicyClauseServiceTest {

    private PolicyClauseMapper clauseMapper;
    private PolicyDocumentMapper docMapper;
    private EmbeddingClient embeddingClient;
    private ModelProviderService modelProviderService;
    private PolicyClauseService service;

    @BeforeEach
    void setUp() {
        clauseMapper = Mockito.mock(PolicyClauseMapper.class);
        docMapper = Mockito.mock(PolicyDocumentMapper.class);
        embeddingClient = Mockito.mock(EmbeddingClient.class);
        modelProviderService = Mockito.mock(ModelProviderService.class);
        service = new PolicyClauseService(clauseMapper, docMapper, embeddingClient,
                modelProviderService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---- 测试替身 ----

    /** 造一条带向量的条款。 */
    private PolicyClause clause(long id, long docId, String code, int index,
                                String content, double[] vec) {
        PolicyClause c = new PolicyClause();
        c.setClauseId(id);
        c.setDocId(docId);
        c.setClauseCode(code);
        c.setClauseIndex(index);
        c.setContent(content);
        c.setStatus(1);
        c.setVectorData(vec == null ? null : com.workpath.backend.util.VectorCodec.format(vec));
        c.setVectorModel(vec == null ? null : "BAAI/bge-m3");
        return c;
    }

    /**
     * 放行检索链路的两步查询：先查启用文档，再查这些文档下的启用条款。
     *
     * <p>两步都放行是因为检索必须先确认文档启用——停用文档的条款不该被检索到。
     * 测试若只桩了条款查询，代码在第一步就返回空，结果恒为空。</p>
     */
    private void stubRetrieval(List<PolicyClause> clauses) {
        PolicyDocument doc = new PolicyDocument();
        doc.setDocId(1L);
        doc.setStatus(1);
        Mockito.when(docMapper.selectList(Mockito.any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc));
        Mockito.when(clauseMapper.selectList(Mockito.any(LambdaQueryWrapper.class)))
                .thenReturn(clauses);
        Mockito.when(docMapper.selectBatchIds(Mockito.anyList())).thenReturn(List.of(doc));
    }

    /** 桩住"当前 EMBEDDING 配置的模型名" */
    private void stubModelName(String model) {
        ModelProviderConfig cfg = new ModelProviderConfig();
        cfg.setModelName(model);
        Mockito.when(modelProviderService.requireConfig(Mockito.anyString())).thenReturn(cfg);
    }

    // ---- 入参校验 ----

    @Test
    @DisplayName("空查询词直接拒绝，不消耗 API 调用")
    void blankQueryRejected() {
        assertThatThrownBy(() -> service.search("   ", 5))
                .isInstanceOf(com.workpath.backend.common.BusinessException.class);
        Mockito.verifyNoInteractions(clauseMapper);
        Mockito.verifyNoInteractions(embeddingClient);
    }

    @Test
    @DisplayName("查询向量化失败时明确报错，不返回空列表伪装成没命中")
    void nullQueryVectorThrows() {
        Mockito.when(embeddingClient.embed(Mockito.anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.search("外勤人员报备要求", 5))
                .isInstanceOf(com.workpath.backend.common.BusinessException.class)
                .hasMessageContaining("向量化失败");
    }

    // ---- 相似度语义 ----

    @Nested
    @DisplayName("余弦相似度语义")
    class Cosine {

        @Test
        @DisplayName("只认方向，向量长度不影响排序")
        void directionNotMagnitude() {
            Mockito.when(embeddingClient.embed(Mockito.anyString()))
                    .thenReturn(Optional.of(new double[]{1d, 1d, 0d}));
            stubRetrieval(List.of(
                    clause(1, 1, "1.1", 0, "同向但很长", new double[]{1000d, 1000d, 0d}),
                    clause(2, 1, "1.2", 1, "反向", new double[]{-1d, -1d, 0d})));

            List<PolicyClause> out = service.search("查询", 5);
            assertThat(out).hasSize(2);
            assertThat(out.get(0).getClauseCode()).isEqualTo("1.1");
            assertThat(out.get(0).getScore()).isCloseTo(1.0d, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(out.get(1).getScore()).isCloseTo(-1.0d, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("按相似度降序，编号顺序不是排序依据")
        void sortedBySimilarityDesc() {
            Mockito.when(embeddingClient.embed(Mockito.anyString()))
                    .thenReturn(Optional.of(new double[]{0.9d, 0.1d}));
            stubRetrieval(Arrays.asList(
                    clause(1, 1, "1.1", 0, "偏y的条款", new double[]{0.1d, 0.9d}),
                    clause(2, 1, "1.2", 1, "完全匹配的条款", new double[]{1d, 0d}),
                    clause(3, 1, "1.3", 2, "稍偏的条款", new double[]{0.8d, 0.2d})));

            assertThat(service.search("查询", 5)).extracting(PolicyClause::getClauseCode)
                    .containsExactly("1.2", "1.3", "1.1");
        }

        @Test
        @DisplayName("相似度相等时按 clause_index 升序，结果可复现")
        void tiesBrokenByIndex() {
            Mockito.when(embeddingClient.embed(Mockito.anyString()))
                    .thenReturn(Optional.of(new double[]{1d, 0d}));
            stubRetrieval(List.of(
                    clause(5, 1, "1.5", 4, "第四条", new double[]{1d, 0d}),
                    clause(2, 1, "1.2", 1, "第二条", new double[]{1d, 0d}),
                    clause(9, 1, "1.9", 8, "第九条", new double[]{1d, 0d}),
                    clause(1, 1, "1.1", 0, "第一条", new double[]{1d, 0d})));

            // shuffle 输入顺序后断言输出顺序由 index 决定，而非输入顺序
            assertThat(service.search("查询", 4)).extracting(PolicyClause::getClauseIndex)
                    .containsExactly(0, 1, 4, 8);
        }

        @Test
        @DisplayName("topK 生效：只返回前 K 条")
        void topKLimit() {
            Mockito.when(embeddingClient.embed(Mockito.anyString()))
                    .thenReturn(Optional.of(new double[]{1d, 0d}));
            java.util.List<PolicyClause> many = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                many.add(clause(i + 1, 1, "1." + i, i, "条款" + i, new double[]{1d, 0d}));
            }
            stubRetrieval(many);

            assertThat(service.search("查询", 3)).hasSize(3);
            // topK<=0 走默认值而非空结果
            assertThat(service.search("查询", 0)).hasSize(PolicyClauseService.DEFAULT_TOP_K);
            // 超过硬上限时截到上限，避免把整个库塞进模型上下文。
            // 库里只有 10 条可命中，所以这里断言"返回全部 10 条"——
            // 上限是截断保护，不会为了凑数造出空结果
            assertThat(service.search("查询", 999)).hasSize(10);
        }
    }

    // ---- 跳过策略 ----

    @Nested
    @DisplayName("无向量与脏数据")
    class Skipping {

        @Test
        @DisplayName("无向量的条款被跳过，只返回有向量的")
        void skipsNullVectors() {
            Mockito.when(embeddingClient.embed(Mockito.anyString()))
                    .thenReturn(Optional.of(new double[]{1d, 0d, 0d}));
            stubRetrieval(List.of(
                    clause(1, 1, "1.1", 0, "无向量的老条款", null),
                    clause(2, 1, "1.2", 1, "方向一致的条款", new double[]{1d, 0d, 0d})));

            List<PolicyClause> out = service.search("查询", 5);
            assertThat(out).extracting(PolicyClause::getClauseCode).containsExactly("1.2");
        }

        @Test
        @DisplayName("维度与查询不一致的条款跳过而不是报错")
        void toleratesDirtyVectorData() {
            Mockito.when(embeddingClient.embed(Mockito.anyString()))
                    .thenReturn(Optional.of(new double[]{1d, 0d}));
            // 4 维与 2 维查询不可比：算出来的相似度没有意义，必须跳过
            stubRetrieval(List.of(
                    clause(1, 1, "1.1", 0, "维度不符的条款", new double[]{1d, 0d, 0d, 0d}),
                    clause(2, 1, "1.2", 1, "正常条款", new double[]{1d, 0d})));

            assertThat(service.search("查询", 5)).extracting(PolicyClause::getClauseCode)
                    .containsExactly("1.2");
        }

        @Test
        @DisplayName("库里只有一条全零向量时返回空，不抛除零异常")
        void zeroVectorSkipped() {
            Mockito.when(embeddingClient.embed(Mockito.anyString()))
                    .thenReturn(Optional.of(new double[]{1d, 1d}));
            stubRetrieval(List.of(clause(1, 1, "1.1", 0, "零向量条款", new double[]{0d, 0d})));

            assertThat(service.search("查询", 5)).isEmpty();
        }

        @Test
        @DisplayName("库里没有任何条款时返回空列表，不报错")
        void emptyStore() {
            Mockito.when(embeddingClient.embed(Mockito.anyString()))
                    .thenReturn(Optional.of(new double[]{1d}));
            stubRetrieval(List.of());
            assertThat(service.search("查询", 5)).isEmpty();
        }
    }

    // ---- 可追溯性 ----

    @Test
    @DisplayName("检索结果带来源文档标题与版本，支撑引用可追溯")
    void resultHasDocTitle() {
        Mockito.when(embeddingClient.embed(Mockito.anyString()))
                .thenReturn(Optional.of(new double[]{1d}));
        PolicyDocument doc = new PolicyDocument();
        doc.setDocId(7L);
        doc.setTitle("外勤人员工作纪律");
        doc.setVersion("v1.0");
        Mockito.when(docMapper.selectList(Mockito.any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc));
        Mockito.when(docMapper.selectBatchIds(Mockito.anyList())).thenReturn(List.of(doc));
        Mockito.when(clauseMapper.selectList(Mockito.any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(clause(3, 7, "3.2.1", 2, "条款内容", new double[]{1d})));

        List<PolicyClause> out = service.search("查询", 5);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getDocTitle()).isEqualTo("外勤人员工作纪律");
        assertThat(out.get(0).getDocVersion()).isEqualTo("v1.0");
    }

    // ---- 重新向量化 ----

    @Nested
    @DisplayName("重新向量化")
    class Reindex {

        @Test
        @DisplayName("只对缺向量的条款补算，已有向量的不动")
        void fillsMissingOnly() {
            PolicyClause missing = clause(1, 1, "1.1", 0, "没向量的条款", null);
            PolicyClause has = clause(2, 1, "1.2", 1, "已有向量的条款", new double[]{1d});
            PolicyDocument doc = new PolicyDocument();
            doc.setDocId(1L);
            doc.setTitle("外勤管理制度");
            Mockito.when(docMapper.selectById(1L)).thenReturn(doc);
            Mockito.when(clauseMapper.selectList(Mockito.any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(missing, has));
            Mockito.when(embeddingClient.embedAll(Mockito.any(), Mockito.anyList()))
                    .thenReturn(List.of(Optional.of(new double[]{1d, 2d})));

            assertThat(service.reindex(1L)).isEqualTo(1);

            ArgumentCaptor<PolicyClause> cap = ArgumentCaptor.forClass(PolicyClause.class);
            Mockito.verify(clauseMapper).updateById(cap.capture());
            PolicyClause updated = cap.getValue();
            assertThat(updated.getClauseId()).isEqualTo(1L);
            assertThat(updated.getVectorData()).isNotNull();
            assertThat(com.workpath.backend.util.VectorCodec.parse(updated.getVectorData()))
                    .containsExactly(1d, 2d);
        }

        @Test
        @DisplayName("全部已有向量时一次 API 都不调，返回 0")
        void noopWhenAllVectorized() {
            // 关键前置：当前 EMBEDDING 模型名必须与条款里的 vectorModel 一致，
            // 否则 reindex 会认为"换过模型要重算"而发起 API 请求
            stubModelName("BAAI/bge-m3");
            Mockito.when(docMapper.selectById(1L)).thenReturn(new PolicyDocument());
            Mockito.when(clauseMapper.selectList(Mockito.any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(clause(1, 1, "1.1", 0, "已有向量", new double[]{1d})));

            assertThat(service.reindex(1L)).isZero();
            Mockito.verifyNoInteractions(embeddingClient);
        }

        @Test
        @DisplayName("换过 embedding 模型时，模型名不同的旧向量也会被重算")
        void modelChangeTriggersReindex() {
            // 条款向量是旧模型算的，当前配置换成了新模型 → 必须重算
            stubModelName("text-embedding-v2");
            Mockito.when(docMapper.selectById(1L)).thenReturn(new PolicyDocument());
            Mockito.when(clauseMapper.selectList(Mockito.any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(clause(1, 1, "1.1", 0, "旧模型的向量", new double[]{1d})));
            Mockito.when(embeddingClient.embedAll(Mockito.any(), Mockito.anyList()))
                    .thenReturn(List.of(Optional.of(new double[]{2d})));

            assertThat(service.reindex(1L)).isEqualTo(1);

            ArgumentCaptor<PolicyClause> cap = ArgumentCaptor.forClass(PolicyClause.class);
            Mockito.verify(clauseMapper).updateById(cap.capture());
            assertThat(com.workpath.backend.util.VectorCodec.parse(cap.getValue().getVectorData()))
                    .containsExactly(2d);
            assertThat(cap.getValue().getVectorModel()).isEqualTo("text-embedding-v2");
        }

        @Test
        @DisplayName("文档不存在时抛 404，不静默返回 0")
        void missingDocThrows() {
            Mockito.when(docMapper.selectById(999L)).thenReturn(null);
            assertThatThrownBy(() -> service.reindex(999L))
                    .isInstanceOf(com.workpath.backend.common.BusinessException.class);
        }
    }
}
