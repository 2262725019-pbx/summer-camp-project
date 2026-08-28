package com.summercamp.project.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.config.RagProperties;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RagRetrievalEvaluationTest {

    private KeywordRagRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new KeywordRagRetriever(
                new RagProperties(true, 3, 2, 2_500), new ObjectMapper());
    }

    @ParameterizedTest(name = "exact query: {0} -> {1}")
    @MethodSource("exactQueries")
    void shouldRankExpectedDocumentFirstForExactQueries(String query, String expectedId) {
        assertTopOne(query, expectedId);
    }

    @ParameterizedTest(name = "paraphrase: {0} -> {1}")
    @MethodSource("paraphraseQueries")
    void shouldRankExpectedDocumentFirstForParaphrases(String query, String expectedId) {
        assertTopOne(query, expectedId);
    }

    @ParameterizedTest(name = "negative query: {0}")
    @MethodSource("negativeQueries")
    void shouldRejectUnrelatedOrToolOwnedQueries(String query) {
        assertFalse(retriever.retrieve(query).matched());
    }

    @org.junit.jupiter.api.Test
    void shouldRankBothSpecificAndOverviewDocumentsForFunctionCallingVersusSkill() {
        RagContext context = retriever.retrieve("函数调用和技能到底有什么区别");

        assertEquals(List.of("function-calling", "tool-skill-rag"),
                context.documentIds().subList(0, 2));
    }

    private void assertTopOne(String query, String expectedId) {
        RagContext context = retriever.retrieve(query);
        assertTrue(context.matched(), () -> "Expected a match for: " + query);
        assertEquals(expectedId, context.documentIds().getFirst(),
                () -> "Unexpected ranking for: " + query + "; got " + context.hits());
    }

    static Stream<Arguments> exactQueries() {
        return Stream.of(
                Arguments.of("怎么配置智谱 API Key", "local-api-keys"),
                Arguments.of("IDEA 启动与微信二维码登录", "startup-login"),
                Arguments.of("Tool、Skill 与 RAG 的区别", "tool-skill-rag"),
                Arguments.of("二维码结果页面与局域网访问", "result-page-network"),
                Arguments.of("Git 提交与密钥安全", "git-secret-safety"),
                Arguments.of("河南师范大学校区与地址", "hnnu-campuses"),
                Arguments.of("项目技术栈和 Java 版本", "project-tech-stack"));
    }

    static Stream<Arguments> paraphraseQueries() {
        return Stream.of(
                Arguments.of("手机和电脑不在同一个网络，计算结果页面为什么打不开", "result-page-network"),
                Arguments.of("我想知道之前聊天内容能保存多久", "conversation-memory"),
                Arguments.of("函数调用和技能到底有什么区别", "function-calling"),
                Arguments.of("机器人用的是什么框架和Java版本", "project-tech-stack"),
                Arguments.of("扫码成功以后登录图片突然没了正常吗", "startup-login"),
                Arguments.of("我不小心把 key 提交到仓库怎么办", "git-secret-safety"),
                Arguments.of("学校现在有多少学院和本科专业", "hnnu-academics"),
                Arguments.of("程序重启以后之前的聊天记录还在吗", "conversation-memory"));
    }

    static Stream<String> negativeQueries() {
        return Stream.of(
                "推荐一部科幻电影",
                "今天镇江下雨吗",
                "125*36",
                "写一首关于秋天的诗",
                "今晚吃什么比较好");
    }
}
