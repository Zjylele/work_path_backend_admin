package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.ModelProviderConfig;
import com.workpath.backend.entity.PolicyClause;
import com.workpath.backend.entity.PolicyDocument;
import com.workpath.backend.mapper.PolicyClauseMapper;import com.workpath.backend.mapper.PolicyDocumentMapper;
import com.workpath.backend.util.VectorCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 条款检索服务。
 *
 * <p>相似度在应用层算余弦，依据见第1章 1.3.4：本系统条款规模在数十至数百条，
 * 引入专用向量数据库的索引收益在这个规模下不存在。代价是这段逻辑要自己保证
 * 可测试性——见 PolicyClauseServiceTest。</p>
 *
 * <p>四条必须守住的语义：</p>
 * <ol>
 *   <li>余弦只认方向。向量长度不影响相似度，长文本与短文本可比较；</li>
 *   <li>无向量条款跳过。上传时向量化失败、或历史数据缺 vector_data 的条款
 *       不参与排序，而不是当相似度 0 参与——否则它们会排在有分数的条款之后，
 *       且看不出是被跳过的；</li>
 *   <li>相似度相等时按 clause_index 升序。保证结果可复现，
 *       不依赖 HashMap 迭代顺序（同一个请求两次调用结果可能不同）；</li>
 *   <li>向量脏数据 / 维度不一致跳过不报错。这是从"检索不该因为一条坏数据整体失败"
 *       推出的，而不是为了掩盖 bug——维度不一致说明该条款与当前查询向量
 *       不可比，算出来的分数也没有意义。</li>
 * </ol>
 */
@Slf4j
@Service
public class PolicyClauseService {

    /** 默认返回条数。管理端选条款与 M4 检索都用这个上限 */
    static final int DEFAULT_TOP_K = 5;

    /** 硬上限，防止把整个库的条款都塞进一次模型请求的上下文 */
    static final int MAX_TOP_K = 20;

    private final PolicyClauseMapper clauseMapper;
    private final PolicyDocumentMapper documentMapper;
    private final EmbeddingClient embeddingClient;
    private final ModelProviderService modelProviderService;

    public PolicyClauseService(PolicyClauseMapper clauseMapper,
                               PolicyDocumentMapper documentMapper,
                               EmbeddingClient embeddingClient,
                               ModelProviderService modelProviderService) {
        this.clauseMapper = clauseMapper;
        this.documentMapper = documentMapper;
        this.embeddingClient = embeddingClient;
        this.modelProviderService = modelProviderService;
    }

    /**
     * 按自然语言检索条款。
     *
     * @param query 查询描述，如"外勤人员报备要求"
     * @param topK  返回条数；<=0 用默认值，超过上限则截到上限
     * @return 按相似度降序的条款，score 字段带余弦相似度
     */
    public List<PolicyClause> search(String query, Integer topK) {
        if (!StringUtils.hasText(query)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "查询内容不能为空");
        }
        int k = normalizeTopK(topK);

        // 查询词先向量化。这一步失败就没什么可检索的了，
        // 明确报错而不是返回空列表——空列表会被页面渲染成"没有匹配条款"，
        // 掩盖模型配置问题。
        Optional<double[]> queryVec = embeddingClient.embed(query.trim());
        if (queryVec.isEmpty()) {
            throw new BusinessException(ResultCode.ERROR.getCode(),
                    "查询向量化失败，请检查 EMBEDDING 模型接入配置");
        }

        List<PolicyClause> candidates = loadEnabledClauses();
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<Long, PolicyDocument> docs = loadDocTitles(candidates);
        double[] q = queryVec.get();

        List<PolicyClause> scored = new ArrayList<>();
        for (PolicyClause c : candidates) {
            double[] v = VectorCodec.parse(c.getVectorData());
            // null = 没向量或脏数据；维度不符 = 与当前查询不可比。两者都跳过。
            if (v == null || v.length != q.length) {
                continue;
            }
            double sim = cosine(q, v);
            if (Double.isNaN(sim)) {
                continue;
            }
            c.setScore(sim);
            PolicyDocument doc = docs.get(c.getDocId());
            if (doc != null) {
                c.setDocTitle(doc.getTitle());
                c.setDocVersion(doc.getVersion());
            }
            scored.add(c);
        }

        // 相似度降序；相等时按 clause_index 升序保证可复现
        scored.sort(Comparator
                .comparing(PolicyClause::getScore, Comparator.reverseOrder())
                .thenComparing(PolicyClause::getClauseIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        return scored.size() <= k ? scored : new ArrayList<>(scored.subList(0, k));
    }

    /**
     * 重新向量化：给缺向量的条款补算，已换模型的旧向量也重算。
     *
     * <p>判定"要重算"的条件是 vector_data 为空，或 vector_model 与当前
     * EMBEDDING 配置的模型名不一致——后者对应第1章 1.3.4 提到的
     * "换 embedding 模型后旧向量全部作废"。</p>
     *
     * @return 补算成功的条数，供前端展示"本次更新了 N 条"
     */
    @Transactional
    public int reindex(Long docId) {
        PolicyDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "制度文档不存在");
        }

        String model = embeddingModelName();
        List<PolicyClause> all = clauseMapper.selectList(
                new LambdaQueryWrapper<PolicyClause>().eq(PolicyClause::getDocId, docId));

        // 两种要重算：从没算过向量的，和向量模型换过的（旧向量空间已不兼容）
        List<PolicyClause> todo = all.stream()
                .filter(c -> (c.getStatus() == null || c.getStatus() == 1))
                .filter(c -> !StringUtils.hasText(c.getVectorData())
                        || !c.getVectorModel().equals(model))
                .toList();
        if (todo.isEmpty()) {
            return 0;
        }

        List<String> texts = todo.stream().map(PolicyClause::getContent).toList();
        List<Optional<double[]>> vectors = embeddingClient.embedAll(
                embeddingClientConfigOrNull(), texts);

        int updated = 0;
        for (int i = 0; i < todo.size(); i++) {
            Optional<double[]> vec = i < vectors.size() ? vectors.get(i) : Optional.empty();
            if (vec.isEmpty()) {
                continue;
            }
            PolicyClause row = new PolicyClause();
            row.setClauseId(todo.get(i).getClauseId());
            row.setVectorData(VectorCodec.format(vec.get()));
            row.setVectorModel(model);
            clauseMapper.updateById(row);
            updated++;
        }
        log.info("重新向量化 docId={} title={} 待重算={} 成功={}",
                docId, doc.getTitle(), todo.size(), updated);
        return updated;
    }

    /**
     * 当前 EMBEDDING 配置的模型名。取不到返回 null（此时向量化会全部失败，
     * reindex 返回 0，由调用方去查配置）。
     *
     * <p>刻意只读配置、不发探测请求：模型名是配置本身的信息，为一个字段
     * 额外发一次 HTTP 会让 reindex 在"没配模型"的场景下也消耗一次请求，
     * 而这次请求注定失败。</p>
     */
    private String embeddingModelName() {
        try {
            return modelProviderService.requireConfig(ModelProviderService.PURPOSE_EMBEDDING)
                    .getModelName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取 EMBEDDING 配置，取不到返回 null。与 PolicyDocumentService
     * 同一口径：模型是管理员手配的，不该让"还没配模型"变成系统异常。
     */
    private ModelProviderConfig embeddingClientConfigOrNull() {
        try {
            return modelProviderService.requireConfig(ModelProviderService.PURPOSE_EMBEDDING);
        } catch (BusinessException e) {
            log.info("未配置 EMBEDDING 模型接入，跳过向量化：{}", e.getMessage());
            return null;
        }
    }

    /** 只取启用文档的启用条款——停用文档的条款不应被检索到。 */
    private List<PolicyClause> loadEnabledClauses() {
        List<PolicyDocument> docs = documentMapper.selectList(
                new LambdaQueryWrapper<PolicyDocument>().eq(PolicyDocument::getStatus, 1));
        if (docs.isEmpty()) {
            return List.of();
        }
        List<Long> docIds = docs.stream().map(PolicyDocument::getDocId).toList();
        return clauseMapper.selectList(
                new LambdaQueryWrapper<PolicyClause>()
                        .in(PolicyClause::getDocId, docIds)
                        .eq(PolicyClause::getStatus, 1));
    }

    private Map<Long, PolicyDocument> loadDocTitles(List<PolicyClause> clauses) {
        List<Long> ids = clauses.stream().map(PolicyClause::getDocId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return documentMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(PolicyDocument::getDocId, Function.identity(),
                        (a, b) -> a));
    }

    private static int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    /**
     * 余弦相似度。任一向量是全零时返回 NaN，调用方据此跳过——
     * 零向量没有方向，与任何向量的"夹角"都无定义。
     */
    static double cosine(double[] a, double[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return Double.NaN;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
