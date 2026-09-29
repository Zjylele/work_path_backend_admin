package com.workpath.backend.service;

import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.dto.PolicyUploadDTO;
import com.workpath.backend.entity.ModelProviderConfig;
import com.workpath.backend.entity.PolicyClause;
import com.workpath.backend.entity.PolicyDocument;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.PolicyClauseMapper;
import com.workpath.backend.mapper.PolicyDocumentMapper;
import com.workpath.backend.util.VectorCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 制度文档服务：上传、版本管理、停用级联。
 *
 * <p>职责边界：本服务只管"文档"这一层——切分、落库、版本。条款检索见
 * {@link PolicyClauseService}。两者分开是因为将来 M4/M5 只需要检索，
 * 让它们依赖检索服务而不是文档服务，避免为了调一次搜索把上传链路也拖进来。</p>
 *
 * <p>三条关键取舍：</p>
 * <ol>
 *   <li><b>向量化失败不阻塞入库</b>（实施指南 W2 硬要求）。向量化放在文档与条款
 *       落库之后、按条款逐条补写，任何一条的向量化异常都不向上抛；</li>
 *   <li><b>版本管理是"新版本启用、旧版本停用但保留"</b>，不是覆盖。
 *       第2章 2.3.6 要求历史版本保留以便回溯当时的核查依据；</li>
 *   <li><b>操作留痕</b>。制度是核查结论的依据，上传与停用都记审计日志。</li>
 * </ol>
 */
@Slf4j
@Service
public class PolicyDocumentService {

    private static final int DEFAULT_VERSION = 1;

    private final PolicyDocumentMapper documentMapper;
    private final PolicyClauseMapper clauseMapper;
    private final ClauseSplitter clauseSplitter;
    private final EmbeddingClient embeddingClient;
    private final ModelProviderService modelProviderService;
    private final OperationLogService operationLogService;

    public PolicyDocumentService(PolicyDocumentMapper documentMapper,
                                 PolicyClauseMapper clauseMapper,
                                 ClauseSplitter clauseSplitter,
                                 EmbeddingClient embeddingClient,
                                 ModelProviderService modelProviderService,
                                 OperationLogService operationLogService) {
        this.documentMapper = documentMapper;
        this.clauseMapper = clauseMapper;
        this.clauseSplitter = clauseSplitter;
        this.embeddingClient = embeddingClient;
        this.modelProviderService = modelProviderService;
        this.operationLogService = operationLogService;
    }

    /**
     * 上传制度文档。
     *
     * <p>流程：校验 → 定版本 → 停用旧版（若同名）→ 落文档 → 切分 → 落条款 → 补向量。</p>
     *
     * <p>@Transactional 的范围到这里为止——向量化刻意不用 @Transactional 包住：
     * 向量化是外部 HTTP 调用，事务期间发网络请求会长时间持有数据库连接，
     * 让连接池被自己的慢请求占满。向量化失败只影响 vector_data 是否为 null，
     * 不影响文档与条款的存在性。</p>
     */
    @Transactional
    public PolicyDocument upload(PolicyUploadDTO dto) {
        requireText(dto.getTitle(), "文档标题不能为空");
        requireText(dto.getContent(), "制度原文不能为空");

        Long uploaderId = currentOperator();
        String title = dto.getTitle().trim();

        // 版本：同名的旧版停用但不删，新文档拿下一版号
        PolicyDocument latest = documentMapper.selectLatestByTitle(title);
        int nextVersion = latest == null
                ? DEFAULT_VERSION
                : parseVersion(latest.getVersion()) + 1;

        // 先落新文档，拿到 docId 再落条款（条款的 doc_id 是必填）
        PolicyDocument doc = new PolicyDocument();
        doc.setTitle(title);
        doc.setVersion("v" + nextVersion + ".0");
        doc.setCategory(trimToNull(dto.getCategory()));
        doc.setStatus(1);
        doc.setUploaderId(uploaderId);
        doc.setCreateTime(LocalDateTime.now());
        documentMapper.insert(doc);

        // 切分。这份原文本就是切分器的输入，不做任何预处理——
        // 切分规则（编号识别、碎片合并、去重）都在 ClauseSplitter 里，
        // 在服务层再切一次会把两套规则弄乱。
        List<ClauseSplitter.Clause> clauses = clauseSplitter.split(dto.getContent());
        if (clauses.isEmpty()) {
            // 切分结果为空说明原文没有实质内容——此时不产生"没有条款的文档"，
            // 否则检索时会出现一个永远命不中的空壳，而管理端看不出问题在哪
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "未识别到任何制度条款，请检查原文格式");
        }

        List<String> texts = new ArrayList<>(clauses.size());
        for (ClauseSplitter.Clause c : clauses) {
            texts.add(c.content());
        }
        // 向量化异常在这里兜住。它是外部 HTTP 调用，网络抖动、限流、密钥失效
        // 都不该让"制度上传"这个动作失败——条款本身已经切好了，缺的只是向量，
        // 之后配好模型 reindex 就能补上（返回码 200 + vector_data 为 null）。
        List<Optional<double[]>> vectors;
        try {
            vectors = embeddingClient.embedAll(embeddingConfigOrNull(), texts);
        } catch (Exception e) {
            log.warn("条款向量化整体失败，文档 {} 以无向量状态入库：{}", doc.getDocId(), e.getMessage());
            vectors = List.of();
        }

        for (int i = 0; i < clauses.size(); i++) {
            ClauseSplitter.Clause c = clauses.get(i);
            PolicyClause row = new PolicyClause();
            row.setDocId(doc.getDocId());
            row.setClauseCode(c.code());
            row.setClauseIndex(c.index());
            row.setCodeSource(c.codeSource());
            row.setContent(c.content());
            row.setStatus(1);
            row.setCreateTime(LocalDateTime.now());

            Optional<double[]> vec = i < vectors.size() ? vectors.get(i) : Optional.empty();
            if (vec.isPresent()) {
                row.setVectorData(VectorCodec.format(vec.get()));
                row.setVectorModel(embeddingModelName());
            }
            clauseMapper.insert(row);
        }

        // 旧版本停用。放在最后而不是最前：若切分或落库失败，旧版仍然是启用状态，
        // 系统不会出现"新旧两版都没生效"的空窗。
        if (latest != null && latest.getStatus() != null && latest.getStatus() == 1) {
            PolicyDocument patch = new PolicyDocument();
            patch.setDocId(latest.getDocId());
            patch.setStatus(0);
            documentMapper.updateById(patch);
        }

        operationLogService.record(uploaderId, currentOperatorName(), "POLICY_DOC_UPLOAD",
                "policy_document", doc.getDocId(), doc.getTitle(),
                "标题=" + doc.getTitle() + "，版本=" + doc.getVersion()
                        + "，条款数=" + clauses.size(), true);
        return doc;
    }

    /**
     * 停用文档，并级联停用其全部条款。
     *
     * <p>级联停用而不是删条款：核查结论表是按 clause_id 关联的（compliance_rule.policy_clause_id），
     * 删掉条款会让历史核查结论变成悬空引用。</p>
     */
    @Transactional
    public void disable(Long docId) {
        Long operator = currentOperator();
        PolicyDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "制度文档不存在");
        }
        if (doc.getStatus() == null || doc.getStatus() == 0) {
            // 幂等：已停用则不再动，但也不报错——重试接口不该失败
            return;
        }
        PolicyDocument patch = new PolicyDocument();
        patch.setDocId(docId);
        patch.setStatus(0);
        documentMapper.updateById(patch);

        List<PolicyClause> active = clauseMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PolicyClause>()
                        .eq(PolicyClause::getDocId, docId)
                        .eq(PolicyClause::getStatus, 1));
        for (PolicyClause c : active) {
            PolicyClause row = new PolicyClause();
            row.setClauseId(c.getClauseId());
            row.setStatus(0);
            clauseMapper.updateById(row);
        }

        operationLogService.record(operator, currentOperatorName(), "POLICY_DOC_DISABLE",
                "policy_document", docId, doc.getTitle(),
                "标题=" + doc.getTitle() + "，版本=" + doc.getVersion(), true);
    }

    /**
     * 文档列表。
     *
     * <p>只返回启用的版本：历史版本用来回溯，但不该占据管理端的列表视野，
     * 否则同名制度会排出一长串让人分不清当前生效的是哪个。</p>
     */
    public List<PolicyDocument> list(String category) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PolicyDocument> q =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        q.eq(PolicyDocument::getStatus, 1);
        if (StringUtils.hasText(category)) {
            q.eq(PolicyDocument::getCategory, category);
        }
        q.orderByDesc(PolicyDocument::getCreateTime);
        return documentMapper.selectList(q);
    }

    /** 查询某文档的条款列表，按文档内顺序。 */
    public List<PolicyClause> clauses(Long docId) {
        PolicyDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "制度文档不存在");
        }
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PolicyClause> q =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        q.eq(PolicyClause::getDocId, docId);
        q.orderByAsc(PolicyClause::getClauseIndex);
        return clauseMapper.selectList(q);
    }

    /**
     * 取 EMBEDDING 配置。取不到返回 null——调用方据此跳过向量化。
     *
     * <p>之所以不把 requireConfig 的异常抛出去：模型配置是管理员手动填的，
     * 文档上传不该因为"还没配向量模型"就整体失败。制度原文与条款本身先入库，
     * 之后配好模型再 reindex。</p>
     */
    private ModelProviderConfig embeddingConfigOrNull() {
        try {
            return modelProviderService.requireConfig(ModelProviderService.PURPOSE_EMBEDDING);
        } catch (BusinessException e) {
            log.info("未配置 EMBEDDING 模型接入，本次上传跳过向量化：{}", e.getMessage());
            return null;
        }
    }

    /** 当前 EMBEDDING 配置的模型名，写进 vector_model 供换模型时判断哪些要重算。 */
    private String embeddingModelName() {
        ModelProviderConfig c = embeddingConfigOrNull();
        return c == null ? null : c.getModelName();
    }

    // ---- 版本号与通用工具 ----

    /** 从 v1.0 / v2 这类版本号里取出数字部分，取不到按第 1 版处理。 */
    static int parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return DEFAULT_VERSION;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : version.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return DEFAULT_VERSION;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return DEFAULT_VERSION;
        }
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static void requireText(String v, String msg) {
        if (!StringUtils.hasText(v)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /** 取当前操作人 ID，未登录直接拒绝。 */
    private static Long currentOperator() {
        com.workpath.backend.entity.User user = UserContext.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
        }
        return user.getId();
    }

    private static String currentOperatorName() {
        com.workpath.backend.entity.User user = UserContext.get();
        return user == null ? null : user.getUsername();
    }
}
