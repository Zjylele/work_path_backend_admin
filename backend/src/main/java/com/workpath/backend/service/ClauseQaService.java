package com.workpath.backend.service;

import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.PolicyClause;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 制度条款问答（M6）。RAG 链路：检索条款 → 拼进 prompt → 调 CHAT 模型 →
 * 答案连同条款引用一起返回。
 *
 * <p>这是第1章 1.2 论述 RAG 落地的位置。三条设计取舍：</p>
 * <ol>
 *   <li><b>检索不到条款就不调用模型</b>。制度问答的答案是"根据制度原文怎么说"，
 *       没有依据可答时应该明说，而不是让模型凭通用常识生成一段看起来合理的话——
 *       那种答案一旦被当成制度依据引用，比"不知道"危害大得多。</li>
 *   <li><b>答案与引用分开返回</b>。模型输出是自然语言，不可作为结构化依据；
 *       引用列表（clauseId/docId/编号/分数）来自检索结果而非模型输出，
 *       前端据此溯源到具体条款原文。这里刻意不去解析模型答案里的条款编号——
 *       模型可能改写编号、漏写或编造，解析出来的引用不可信。</li>
 *   <li><b>无法抑制模型幻觉就显式声明依据边界</b>。system prompt 里要求模型
 *       只依据给定条款回答、条款没写就回答"制度中未明确"，并在前端注明
 *       引用条款为"本次问答可依据的全部候选"而非模型点名的那几个。</li>
 * </ol>
 *
 * <p>M4 的语义标注与 M5 的核查说明都将复用这条链路——它们要的是"依据制度
 * 怎么解释这个行为"，本服务的 {@link #ask} 已经给出这个能力，届时只需替换
 * system prompt 与 user prompt 的模板。</p>
 */
@Slf4j
@Service
public class ClauseQaService {

    /** 默认检索条数。问答场景给 5 条足够覆盖一个问题的相关条款 */
    private static final int DEFAULT_TOP_K = 5;

    /** 上限。条款正文动辄几百字，条数再多只会稀释模型对重点条款的注意力 */
    private static final int MAX_TOP_K = 10;

    /** 单条条款进入 prompt 前的最大长度，防止一条超长条款挤掉其它条款 */
    private static final int CLAUSE_MAX_CHARS = 600;

    /** 检索无命中时给用户的话。明确说"没找到"，不说"无法回答" */
    private static final String NO_CONTEXT_REPLY =
            "未在制度知识库中检索到与该问题相关的条款，无法给出依据充分的回答。"
                    + "请确认制度文档已上传且未被停用，或换一种问法再试。";

    /**
     * 系统提示。约束模型的三件事：只依据给定条款、条款没写就直说、按编号引用。
     *
     * <p>措辞刻意具体到可判定——"依据给定条款回答"比"不要乱说"有用，
     * 因为前者给出了判断边界。</p>
     */
    private static final String SYSTEM_PROMPT = """
            你是企业外勤管理制度问答助手。回答必须严格依据下方给出的制度条款片段，不得引入条款之外的常识、推测或其它公司规定。

            要求：
            1. 结论先行，直接回答问题，不要复述问题。
            2. 引用条款时写明其编号，格式如「依据条款 1.1」。
            3. 若条款中没有覆盖该问题的内容，明确回答「制度中未明确」，不要替制度补充规定。
            4. 若条款之间存在冲突，把冲突的两条都列出来并说明，不要自行取舍。
            5. 使用简洁的书面中文，不要分点罗列无关背景。
            """;

    private final PolicyClauseService clauseSearch;
    private final ChatClient chatClient;
    private final ModelProviderService modelProviderService;

    public ClauseQaService(PolicyClauseService clauseSearch,
                           ChatClient chatClient,
                           ModelProviderService modelProviderService) {
        this.clauseSearch = clauseSearch;
        this.chatClient = chatClient;
        this.modelProviderService = modelProviderService;
    }

    /**
     * 发起一次制度问答。
     *
     * @param question 用户问题
     * @param topK     参与依据的条款条数；null 或 <=0 用默认值，超过上限截到上限
     */
    public Answer ask(String question, Integer topK) {
        String q = question == null ? "" : question.strip();
        if (!StringUtils.hasText(q)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "问题不能为空");
        }
        int k = normalizeTopK(topK);

        List<PolicyClause> hits = clauseSearch.search(q, k);
        if (hits.isEmpty()) {
            // 关键分支：无依据不答。此时不调模型，答案里明确说没找到
            log.info("制度问答未检索到条款，问题={}", q);
            return new Answer(NO_CONTEXT_REPLY, List.of(), false);
        }

        String prompt = buildPrompt(q, hits);
        // CHAT 配置缺失时 requireConfig 抛 BusinessException，消息里带「CHAT」字样，
        // 前端据此提示去配模型，而不是看到一个泛泛的服务器错误
        modelProviderService.requireConfig(ModelProviderService.PURPOSE_CHAT);

        String answer;
        try {
            answer = chatClient.chat(SYSTEM_PROMPT, prompt)
                    .orElse(null);
        } catch (RuntimeException e) {
            // ChatClient 自身已把 HTTP 失败转成 empty，走到这里说明是未预期的异常。
            // 必须上抛：把模型故障说成"未返回有效回答"会让人以为是模型拒答，
            // 而实际可能是代码 bug
            throw new BusinessException(ResultCode.ERROR.getCode(),
                    "模型调用失败：" + e.getMessage());
        }
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.ERROR.getCode(),
                    "模型未返回有效回答，请检查 CHAT 模型接入配置或稍后重试");
        }

        List<Citation> citations = toCitations(hits);
        log.info("制度问答完成 问题={} 条款数={} 答案长度={}", q, citations.size(),
                answer.length());
        return new Answer(answer, citations, true);
    }

    /**
     * 拼 user prompt：先条款（带片段编号），再问题。
     *
     * <p>条款在前问题在后，与 system prompt 的"依据下方给出的条款片段"对应。
     * 每条冠以「片段N」，而不是直接把数据库的 clause_code 当序号——
     * 条款编号（1.1 / 3.2.1）本身要原样展示给模型用来引用，
     * 另用片段号组织上下文才不会把两者混为一谈。</p>
     */
    static String buildPrompt(String question, List<PolicyClause> clauses) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("【制度条款片段】\n");
        for (int i = 0; i < clauses.size(); i++) {
            PolicyClause c = clauses.get(i);
            sb.append("片段").append(i + 1)
                    .append("（编号 ").append(c.getClauseCode())
                    .append("，来源《").append(c.getDocTitle()).append("》")
                    .append(nullAsEmpty(c.getDocVersion())).append("）：\n")
                    .append(truncate(c.getContent()))
                    .append("\n\n");
        }
        sb.append("【问题】\n").append(question.strip());
        return sb.toString();
    }

    /** 检索命中 → 引用列表。顺序即检索顺序（相似度降序）。 */
    private static List<Citation> toCitations(List<PolicyClause> hits) {
        List<Citation> out = new ArrayList<>(hits.size());
        for (PolicyClause c : hits) {
            out.add(new Citation(c.getClauseId(), c.getDocId(), c.getClauseCode(),
                    c.getDocTitle(), c.getDocVersion(), c.getContent(), c.getScore()));
        }
        return out;
    }

    private static int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private static String nullAsEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= CLAUSE_MAX_CHARS ? t : t.substring(0, CLAUSE_MAX_CHARS) + "…（已截断）";
    }

    /** 一条可溯源的条款引用。字段全部来自检索结果，不来自模型输出。 */
    public record Citation(Long clauseId, Long docId, String clauseCode, String docTitle,
                           String docVersion, String content, Double score) {
    }

    /** 问答结果。answered=false 表示"无依据可答"，此时 text 是说明、citations 为空。 */
    public record Answer(String text, List<Citation> citations, boolean answered) {
    }
}
