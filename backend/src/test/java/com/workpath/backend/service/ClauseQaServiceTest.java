package com.workpath.backend.service;

import com.workpath.backend.entity.PolicyClause;
import com.workpath.backend.entity.ModelProviderConfig;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 制度条款问答单元测试。
 *
 * <p>钉住的是 RAG 链路的编排契约，而不是模型输出质量（模型输出不可测也无法断言）。
 * 具体管四件事：</p>
 * <ol>
 *   <li><b>只有检索到的条款能作为依据</b>——prompt 里带的条款必须是 search 实际命中的
 *       那些，不能让模型拿语料外的东西编答案；</li>
 *   <li><b>模型没检索到条款就不调用</b>——无依据可答时应直接告知，而不是让模型
 *       凭常识胡说；</li>
 *   <li><b>引用可核对</b>——返回结构要带 clauseId/docId/编号/分数，前端才能溯源；</li>
 *   <li><b>模型失败大声报错</b>，不返回一个看起来完整但没有出处的答案。</li>
 * </ol>
 */
class ClauseQaServiceTest {

    private PolicyClauseService clauseSearch;
    private ChatClient chatClient;
    private ModelProviderService modelProviderService;
    private ClauseQaService service;

    @BeforeEach
    void setUp() {
        clauseSearch = Mockito.mock(PolicyClauseService.class);
        chatClient = Mockito.mock(ChatClient.class);
        modelProviderService = Mockito.mock(ModelProviderService.class);
        service = new ClauseQaService(clauseSearch, chatClient, modelProviderService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private PolicyClause clause(long id, long docId, String code, String content) {
        PolicyClause c = new PolicyClause();
        c.setClauseId(id);
        c.setDocId(docId);
        c.setClauseCode(code);
        c.setClauseIndex(0);
        c.setCodeSource(1);
        c.setContent(content);
        c.setDocTitle("外勤人员管理制度");
        c.setDocVersion("v1.0");
        c.setScore(0.83);
        return c;
    }

    private void stubConfig() {
        ModelProviderConfig cfg = new ModelProviderConfig();
        cfg.setModelName("deepseek-flash");
        cfg.setBaseUrl("http://127.0.0.1:9/v1");
        cfg.setApiKey("sk-test");
        Mockito.when(modelProviderService.requireConfig(ModelProviderService.PURPOSE_CHAT))
                .thenReturn(cfg);
    }

    // ---- 入参校验 ----

    @Test
    @DisplayName("空问题直接拒绝，不发检索也不调模型")
    void blankQuestionRejected() {
        assertThatThrownBy(() -> service.ask("   ", 5))
                .isInstanceOf(com.workpath.backend.common.BusinessException.class);
        Mockito.verifyNoInteractions(clauseSearch);
        Mockito.verifyNoInteractions(chatClient);
    }

    // ---- 无依据不答 ----

    @Nested
    @DisplayName("检索不到依据时不调用模型")
    class NoContextNoAnswer {

        @Test
        @DisplayName("检索无命中：明确告知未找到依据，不调模型")
        void emptySearchSkipsModel() {
            Mockito.when(clauseSearch.search(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(List.of());

            ClauseQaService.Answer a = service.ask("公司食堂几点开饭", 5);

            Mockito.verifyNoInteractions(chatClient);
            assertThat(a.answered()).isFalse();
            assertThat(a.text()).contains("未在制度知识库中检索到");
            assertThat(a.citations()).isEmpty();
        }

        @Test
        @DisplayName("检索抛异常时向上抛，不吞成空答案")
        void searchFailurePropagates() {
            Mockito.when(clauseSearch.search(Mockito.anyString(), Mockito.anyInt()))
                    .thenThrow(new com.workpath.backend.common.BusinessException(500, "查询向量化失败"));

            assertThatThrownBy(() -> service.ask("外出要报备吗", 5))
                    .isInstanceOf(com.workpath.backend.common.BusinessException.class)
                    .hasMessageContaining("查询向量化失败");
            Mockito.verifyNoInteractions(chatClient);
        }
    }

    // ---- prompt 拼接契约 ----

    @Nested
    @DisplayName("prompt 只含检索命中的条款")
    class PromptBuilding {

        @Test
        @DisplayName("模型拿到的 prompt 必须包含每条命中条款的编号与正文")
        void promptContainsClauses() {
            PolicyClause c1 = clause(1L, 7L, "1.1", "外勤人员因公外出应提前一个工作日报备。");
            PolicyClause c2 = clause(2L, 7L, "1.2", "外出期间应按报备路线行驶。");
            Mockito.when(clauseSearch.search(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(List.of(c1, c2));
            stubConfig();
            Mockito.when(chatClient.chat(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(Optional.of("需要提前报备，依据条款 1.1。"));

            service.ask("外出要报备吗", 5);

            ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
            Mockito.verify(chatClient).chat(system.capture(), user.capture());

            // 条款正文与编号都进了 prompt
            assertThat(user.getValue())
                    .contains("外勤人员因公外出应提前一个工作日报备")
                    .contains("外出期间应按报备路线行驶")
                    .contains("1.1")
                    .contains("1.2")
                    // 用户问题本身也要在
                    .contains("外出要报备吗");
        }

        @Test
        @DisplayName("prompt 带编号片段标记，便于模型按编号引用")
        void promptLabelsEachClause() {
            Mockito.when(clauseSearch.search(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(List.of(clause(1L, 7L, "3.2.1", "外勤人员应每日提交工作日志。")));
            stubConfig();
            Mockito.when(chatClient.chat(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(Optional.of("应每日提交，依据 3.2.1。"));

            service.ask("日志要交吗", 5);

            ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
            Mockito.verify(chatClient).chat(Mockito.anyString(), user.capture());
            // 用「片段1」这类显式编号组织上下文，避免模型把条款编号当成序号引用
            assertThat(user.getValue()).contains("片段1");
            assertThat(user.getValue()).contains("3.2.1");
        }

        @Test
        @DisplayName("topK 透传给检索，控制 prompt 长度")
        void topKPassedThrough() {
            Mockito.when(clauseSearch.search(Mockito.eq("问题"), Mockito.eq(3)))
                    .thenReturn(List.of(clause(1L, 7L, "1.1", "某条款内容")));
            stubConfig();
            Mockito.when(chatClient.chat(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(Optional.of("答"));

            service.ask("问题", 3);
            Mockito.verify(clauseSearch).search("问题", 3);
        }

        @Test
        @DisplayName("topK 为 null/0 时用默认值，不传 null 给检索")
        void topKDefaults() {
            Mockito.when(clauseSearch.search(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(List.of(clause(1L, 7L, "1.1", "某条款内容")));
            stubConfig();
            Mockito.when(chatClient.chat(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(Optional.of("答"));

            service.ask("问题", null);

            ArgumentCaptor<Integer> k = ArgumentCaptor.forClass(Integer.class);
            Mockito.verify(clauseSearch).search(Mockito.anyString(), k.capture());
            assertThat(k.getValue()).isPositive();
        }
    }

    // ---- 引用与结果结构 ----

    @Nested
    @DisplayName("引用可溯源")
    class Citations {

        @Test
        @DisplayName("返回结构与检索命中的条款一一对应，含 clauseId/docId/编号/分数")
        void citationsMatchSearchResults() {
            PolicyClause c1 = clause(11L, 7L, "1.1", "外勤人员因公外出应提前一个工作日报备。");
            PolicyClause c2 = clause(12L, 7L, "1.2", "外出期间应按报备路线行驶。");
            Mockito.when(clauseSearch.search(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(List.of(c1, c2));
            stubConfig();
            Mockito.when(chatClient.chat(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(Optional.of("依据条款 1.1 与 1.2，外出需提前报备并按规定路线行驶。"));

            ClauseQaService.Answer a = service.ask("外出要注意什么", 5);

            assertThat(a.answered()).isTrue();
            assertThat(a.citations()).hasSize(2);
            assertThat(a.citations().get(0).clauseId()).isEqualTo(11L);
            assertThat(a.citations().get(0).docId()).isEqualTo(7L);
            assertThat(a.citations().get(0).clauseCode()).isEqualTo("1.1");
            assertThat(a.citations().get(0).docTitle()).isEqualTo("外勤人员管理制度");
            assertThat(a.citations().get(0).docVersion()).isEqualTo("v1.0");
            assertThat(a.citations().get(0).score()).isEqualTo(0.83);
            // 答案正文就是模型返回的文本
            assertThat(a.text()).contains("外出需提前报备");
        }

        @Test
        @DisplayName("模型只引用了其中一个条款时，引用列表仍列全部候选供核对")
        void citationsAreAllCandidates() {
            PolicyClause c1 = clause(1L, 7L, "1.1", "条款一内容");
            PolicyClause c2 = clause(2L, 7L, "1.2", "条款二内容");
            Mockito.when(clauseSearch.search(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(List.of(c1, c2));
            stubConfig();
            Mockito.when(chatClient.chat(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(Optional.of("仅引用 1.1。"));

            ClauseQaService.Answer a = service.ask("问题", 5);
            // 引用区是"本次问答可依据的全部条款"，不是模型主动点名的那几个
            assertThat(a.citations()).extracting(ClauseQaService.Citation::clauseCode)
                    .containsExactly("1.1", "1.2");
        }
    }

    // ---- 失败路径 ----

    @Nested
    @DisplayName("模型调用失败")
    class ModelFailure {

        @Test
        @DisplayName("模型返回空：报错说清原因，不返回无出处的答案")
        void emptyAnswerThrows() {
            Mockito.when(clauseSearch.search(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(List.of(clause(1L, 7L, "1.1", "条款内容")));
            stubConfig();
            Mockito.when(chatClient.chat(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.ask("问题", 5))
                    .isInstanceOf(com.workpath.backend.common.BusinessException.class)
                    .hasMessageContaining("模型");
        }

        @Test
        @DisplayName("模型调用抛异常：原样上抛，不吞")
        void exceptionPropagates() {
            Mockito.when(clauseSearch.search(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(List.of(clause(1L, 7L, "1.1", "条款内容")));
            stubConfig();
            Mockito.when(chatClient.chat(Mockito.anyString(), Mockito.anyString()))
                    .thenThrow(new RuntimeException("连接超时"));

            assertThatThrownBy(() -> service.ask("问题", 5))
                    // ChatClient 已把 HTTP 类失败转成 empty；走到这里是未预期的异常，
                    // 必须上抛——静默返回空答案会把代码 bug 伪装成"模型拒答"
                    .isInstanceOf(com.workpath.backend.common.BusinessException.class)

                    .hasMessageContaining("连接超时");
        }

        @Test
        @DisplayName("未配置 CHAT 模型：明确报缺配置，不是泛泛的服务器错误")
        void missingChatConfig() {
            Mockito.when(clauseSearch.search(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(List.of(clause(1L, 7L, "1.1", "条款内容")));
            Mockito.when(modelProviderService.requireConfig(ModelProviderService.PURPOSE_CHAT))
                    .thenThrow(new com.workpath.backend.common.BusinessException(500, "尚未配置「CHAT」的模型接入"));

            assertThatThrownBy(() -> service.ask("问题", 5))
                    .isInstanceOf(com.workpath.backend.common.BusinessException.class)
                    .hasMessageContaining("CHAT");
        }
    }
}
