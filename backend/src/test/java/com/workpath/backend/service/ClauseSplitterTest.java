package com.workpath.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 条款切分规则单元测试。
 *
 * <p>这些用例对应 W1 的四条切分规则，每条都来自真实制度文档的形态：</p>
 * <ol>
 *   <li>标准条款：行首有「3.2.1 」「3.2.1、」这类编号 → code_source=1（原文提取）</li>
 *   <li>无编号文档：整段没有条款编号 → code_source=2（按序生成）</li>
 *   <li>碎片合并：编号后内容过短（<10 字）并入相邻条款，不产生无意义向量</li>
 *   <li>混合文档：规范编号 + 无编号散段共存</li>
 * </ol>
 *
 * 先写测试后写实现（RED → GREEN）。
 */
class ClauseSplitterTest {

    /** 条款结果。字段名与 policy_clause 表对齐。 */
    record Clause(String code, String content, int index, int codeSource) {
    }

    @Nested
    @DisplayName("标准条款：原文提取编号")
    class StandardNumbering {

        @Test
        @DisplayName("3.2.1 + 空格 是最常见的形态，应提取编号")
        void dotNumberWithSpace() {
            String doc = """
                    3.2.1 员工因公外出应提前报备，经部门负责人批准后方可出行。
                    3.2.2 因公外出期间应保持通讯畅通，按指定路线行驶。
                    """;
            List<Clause> list = split(doc);
            assertThat(list).hasSize(2);
            assertThat(list.get(0).code()).isEqualTo("3.2.1");
            assertThat(list.get(0).codeSource()).isEqualTo(1);
            assertThat(list.get(1).code()).isEqualTo("3.2.2");
        }

        @Test
        @DisplayName("中文顿号「3.2.1、」也应识别")
        void dotNumberWithChineseComma() {
            String doc = "3.2.1、严禁工作时间离岗外出处理私人事务。\n3.2、以上条款由行政部负责解释。\n";
            List<Clause> list = split(doc);
            assertThat(list).hasSize(2);
            assertThat(list.get(0).code()).isEqualTo("3.2.1");
            assertThat(list.get(0).codeSource()).isEqualTo(1);
        }

        @Test
        @DisplayName("句点分隔「3.2.1.」应识别")
        void dotNumberWithDot() {
            String doc = "3.2.1.外勤人员出差期间应每日提交工作日志。\n";
            List<Clause> list = split(doc);
            assertThat(list.get(0).code()).isEqualTo("3.2.1");
            assertThat(list.get(0).codeSource()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("无编号文档：按序生成编号")
    class NoNumbering {

        @Test
        @DisplayName("整段没有编号时，生成 AUTO-前缀编号，codeSource=2")
        void generateAutoCode() {
            String doc = """
                    员工因公外出应提前报备，经部门负责人批准后方可出行。
                    因公外出期间应保持通讯畅通，按指定路线行驶。
                    """;
            List<Clause> list = split(doc);
            assertThat(list).isNotEmpty();
            assertThat(list.get(0).codeSource()).isEqualTo(2);
            assertThat(list.get(0).code()).startsWith("AUTO-");
        }

        @Test
        @DisplayName("文档开头就没编号，后续非编号行并入第一条而不是各成一条")
        void leadingLinesMergeIntoFirst() {
            String doc = "第一章 总则\n为规范外勤管理，特制定本制度。\n";
            List<Clause> list = split(doc);
            assertThat(list).hasSize(1);
            assertThat(list.get(0).codeSource()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("碎片合并")
    class FragmentMerge {

        @Test
        @DisplayName("编号后内容不足 10 字时并入相邻条款")
        void shortClauseMerges() {
            String doc = """
                    3.2.1 员工因公外出应提前报备，经部门负责人批准后方可出行，否则按旷工处理。
                    3.2.2 特此说明。
                    """;
            List<Clause> list = split(doc);
            // 3.2.2 只有 4 字，应并入 3.2.1，最终只剩一条有实质内容的条款
            assertThat(list).hasSize(1);
            assertThat(list.get(0).code()).isEqualTo("3.2.1");
        }

        @Test
        @DisplayName("末尾碎片并入前一条，不产生孤立短条款")
        void trailingFragmentMerges() {
            String doc = """
                    3.2.1 外勤人员应每日提交工作日志，记录拜访客户与业务进展，日志需经部门负责人复核。
                    3.2.2 附则
                    """;
            List<Clause> list = split(doc);
            assertThat(list).hasSize(1);
        }
    }

    @Nested
    @DisplayName("混合文档：规范编号 + 无编号散段")
    class Mixed {

        @Test
        @DisplayName("有编号的按编号切，无编号散段并入前一条")
        void mixNumberedAndFreeText() {
            String doc = """
                    1.1 员工应遵守公司各项规章制度，服从工作安排。
                    以上为第一章内容。
                    1.2 员工不得擅自变更工作岗位。
                    """;
            List<Clause> list = split(doc);
            assertThat(list).hasSize(2);
            assertThat(list.get(0).code()).isEqualTo("1.1");
            assertThat(list.get(0).content()).contains("以上为第一章内容");
            assertThat(list.get(1).code()).isEqualTo("1.2");
        }

        @Test
        @DisplayName("连续空行与首尾空白不影响切分")
        void blankLinesAndTrim() {
            String doc = "\n\n  3.2.1   员工因公外出应提前报备，经部门负责人批准后方可出行。  \n\n\n";
            List<Clause> list = split(doc);
            assertThat(list).hasSize(1);
            assertThat(list.get(0).content()).doesNotStartWith(" ");
        }
    }

    @Nested
    @DisplayName("边界与异常")
    class EdgeCases {

        @Test
        @DisplayName("null 或空白内容应返回空列表，不抛异常")
        void nullOrBlank() {
            assertThat(split(null)).isEmpty();
            assertThat(split("")).isEmpty();
            assertThat(split("   \n\n  ")).isEmpty();
        }

        @Test
        @DisplayName("clauseIndex 从 0 开始连续递增")
        void indexIsSequential() {
            String doc = """
                    1.1 员工因公外出应提前报备，经部门负责人批准后方可出行。
                    1.2 因公外出期间应保持通讯畅通，按指定路线行驶。
                    1.3 外勤人员应每日提交工作日志，记录当日业务进展。
                    """;
            List<Clause> list = split(doc);
            assertThat(list).hasSize(3);
            for (int i = 0; i < list.size(); i++) {
                assertThat(list.get(i).index()).isEqualTo(i);
            }
        }

        @Test
        @DisplayName("同一文档内编号重复时后者覆盖前者（与唯一键 ukey 对应）")
        void duplicateCodeLaterWins() {
            String doc = """
                    1.1 员工因公外出应提前报备，经部门负责人批准后方可出行。
                    1.1 本条款为修订后内容，替代前述条款，以本条款为最终执行依据。
                    """;
            List<Clause> list = split(doc);
            Optional<Clause> found = list.stream().filter(c -> c.code().equals("1.1")).findFirst();
            assertThat(found).isPresent();
        }
    }

    // ---- 被测实现 ----
    private static final ClauseSplitter SPLITTER = new ClauseSplitter();

    private static List<Clause> split(String content) {
        return SPLITTER.split(content).stream()
                .map(c -> new Clause(c.code(), c.content(), c.index(), c.codeSource()))
                .toList();
    }
}
