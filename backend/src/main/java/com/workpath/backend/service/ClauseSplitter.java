package com.workpath.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 制度条款切分器。
 *
 * <p>规则（与 ClauseSplitterTest 的四个嵌套类一一对应）：</p>
 * <ol>
 *   <li><b>编号提取</b>：行首匹配 {@code ^\s*(\d+(?:\.\d+)+)[、．.\s]}，
 *       命中则 code_source=1，编号取捕获组；</li>
 *   <li><b>按序生成</b>：未命中的行从文档开头就出现，或前一条也是生成态时，
 *       code_source=2，编号形如 {@code AUTO-0001}；</li>
 *   <li><b>碎片合并</b>：条款正文不足 {@link #MIN_LEN} 字时并入相邻条款，
 *       不产生无意义向量（第1章 1.3.3 提到切分质量决定检索天花板）；</li>
 *   <li><b>去重</b>：同一文档内编号重复时后者覆盖前者，
 *       对应 policy_clause 的 {@code uk_clause_doc_code} 唯一键。</li>
 * </ol>
 *
 * <p>关于第三条的必要性：制度文档里常见「1.2 附则」这种只有标题没有内容的行，
 * 单独入库会得到一个语义几乎为空的向量，检索时还会抢占相似度排名。</p>
 */
@Slf4j
@Component
public class ClauseSplitter {

    /** 条款正文长度下限。低于此值视为碎片，并入相邻条款。 */
    public static final int MIN_LEN = 10;

    /**
     * 条款编号头部。
     * 要求至少两级数字（如 3.2），因为「第 3 条」「三、」这类非数字编号
     * 无法与外勤制度常见的「3.2.1」区分，统一不识别。
     */
    private static final Pattern CODE_HEAD = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)+)[、．.\\s]");

    /** 切分结果。字段名与 policy_clause 表对齐。 */
    public record Clause(String code, String content, int index, int codeSource) {
    }

    /**
     * 切分制度文档正文。
     *
     * @param content 文档全文；null 或空白返回空列表
     * @return 按出现顺序排列的条款；index 从 0 连续递增
     */
    public List<Clause> split(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }

        // 用 LinkedHashMap 保序去重：同一编号后写覆盖先写
        Map<String, StringBuilder> bodies = new LinkedHashMap<>();
        Map<String, Integer> sources = new LinkedHashMap<>();

        String currentCode = null;
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }

            Matcher m = CODE_HEAD.matcher(line);
            if (m.find()) {
                // 命中编号头部，开启新条款
                currentCode = m.group(1);
                bodies.computeIfAbsent(currentCode, k -> new StringBuilder());
                sources.putIfAbsent(currentCode, 1);
                String rest = line.substring(m.end()).strip();
                if (!rest.isEmpty()) {
                    bodies.get(currentCode).append(rest);
                }
            } else {
                // 无编号行：并入当前条款；若还没有当前条款，先开一条生成态
                if (currentCode == null) {
                    currentCode = nextAutoCode(bodies.keySet());
                    bodies.computeIfAbsent(currentCode, k -> new StringBuilder());
                    sources.putIfAbsent(currentCode, 2);
                }
                if (!bodies.get(currentCode).isEmpty()) {
                    bodies.get(currentCode).append('\n');
                }
                bodies.get(currentCode).append(line);
            }
        }

        return finalize(bodies, sources);
    }

    /**
     * 生成下一个 AUTO 编号。
     * 扫一遍已有编号，避免与已提取的数字编号撞车。
     */
    private String nextAutoCode(java.util.Set<String> existing) {
        int n = 1;
        while (existing.contains(String.format("AUTO-%04d", n))) {
            n++;
        }
        return String.format("AUTO-%04d", n);
    }

    /**
     * 后处理：过滤碎片、重建连续 index。
     *
     * <p>碎片向前合并：从第二条起，若正文长度不足 MIN_LEN，就追加到前一条尾部。</p>
     */
    private List<Clause> finalize(Map<String, StringBuilder> bodies,
                                  Map<String, Integer> sources) {
        List<Clause> result = new ArrayList<>();
        String prevCode = null;
        StringBuilder prevBody = null;
        int autoSeq = 0;

        for (Map.Entry<String, StringBuilder> e : bodies.entrySet()) {
            String code = e.getKey();
            String text = e.getValue().toString().strip();
            int source = sources.getOrDefault(code, 1);

            // 碎片处理：长度不足则并入前一条；没有前一条时保留（至少比空好）
            if (text.length() < MIN_LEN && prevBody != null) {
                prevBody.append('\n').append(text);
                continue;
            }
            // 没有前一条且仍是碎片：保留自身，避免整篇文档被吃空
            if (text.isEmpty()) {
                continue;
            }

            if (prevBody != null) {
                result.add(build(prevCode, prevBody.toString(), result.size(), sources));
            }
            prevCode = code;
            prevBody = new StringBuilder(text);
        }
        if (prevBody != null) {
            result.add(build(prevCode, prevBody.toString(), result.size(), sources));
        }
        return result;
    }

    private Clause build(String code, String body, int index,
                         Map<String, Integer> sources) {
        int src = sources.getOrDefault(code, 1);
        return new Clause(code, body.strip(), index, src);
    }
}
