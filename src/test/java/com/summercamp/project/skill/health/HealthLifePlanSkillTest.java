package com.summercamp.project.skill.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.config.RagProperties;
import com.summercamp.project.llm.ChatModelClient;
import com.summercamp.project.llm.ChatOutcome;
import com.summercamp.project.llm.ChatRequest;
import com.summercamp.project.llm.LlmException;
import com.summercamp.project.rag.KeywordRagRetriever;
import com.summercamp.project.rag.RagRetriever;
import com.summercamp.project.schedule.ReminderSubscriptionManager;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.skill.nutrition.FoodCatalog;
import com.summercamp.project.tool.TodoService;
import com.summercamp.project.weather.CurrentWeather;
import com.summercamp.project.weather.WeatherClient;
import com.summercamp.project.weather.WeatherLocationNotFoundException;
import com.summercamp.project.weather.WeatherPeriod;
import com.summercamp.project.weather.WeatherReport;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HealthLifePlanSkillTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldMatchHealthPlanIntentButNotOtherSkills() {
        HealthLifePlanSkill skill = skill((request, context) -> ChatOutcome.text("回复"));

        assertTrue(skill.matchScore("帮我制定健康生活规划") > 0);
        assertTrue(skill.matchScore("我想做一个减脂计划") > 0);
        // 口语化一句话目标也应触发，避免被天气/计算意图拦截
        assertTrue(skill.matchScore("我是大学生 175cm 70kg，想一个月减 8 斤，我在北京，喜欢跑步") > 0);
        assertEquals(0, skill.matchScore("帮我制定增肌饮食计划"));
        assertEquals(0, skill.matchScore("今天天气怎么样"));
        assertEquals(0, skill.matchScore("我今天减了 2 斤体重")); // 无身体数据，不误伤
    }

    @Test
    void shouldAskOnceForMissingCriticalFields() {
        HealthLifePlanSkill skill = skill((request, context) -> ChatOutcome.text("回复"));

        SkillResult result = skill.execute(context("我想减肥"));

        assertEquals(SkillResult.Status.WAITING_INPUT, result.status());
        assertTrue(result.reply().contains("性别：男"));
        assertTrue(result.reply().contains("身高：175cm"));
    }

    @Test
    void shouldGenerateCompletePlanAfterFollowUp() {
        HealthLifePlanSkill skill = skill((request, context) -> ChatOutcome.text("""
                一、目标与身体指标
                BMI 22.9，每日消耗 2336 千卡，建议摄入 1869 千卡，蛋白质 126g
                二、参考餐单
                早餐：燕麦片 55g、全脂牛奶 195g、鸡蛋 80g
                三、运动方案
                每周 3 次有氧加 2 次力量训练，每次热身 5～10 分钟。
                四、作息与执行
                每晚睡眠 7～9 小时，每日饮水 1500～2500 毫升。
                五、安全提醒
                本计划为一般性估算，不替代医疗或个体化营养建议。
                """));

        assertEquals(SkillResult.Status.WAITING_INPUT,
                skill.execute(context("我想减肥")).status());

        SkillResult result = skill.execute(context("性别：男 身高：175cm 体重：70kg 想减脂"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("一、目标与身体指标"));
        assertTrue(result.reply().contains("参考餐单"));
        assertTrue(result.reply().contains("运动方案"));
        assertTrue(result.reply().contains("作息"));
        assertTrue(result.reply().contains("不替代医疗"));
        assertTrue(result.reply().contains("待办"));
    }

    @Test
    void shouldUseDefaultsWhenStillMissingAfterOneFollowUp() {
        HealthLifePlanSkill skill = skill((request, context) -> ChatOutcome.text("通用方案"));

        assertEquals(SkillResult.Status.WAITING_INPUT,
                skill.execute(context("我想减肥")).status());

        SkillResult result = skill.execute(context("直接生成"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("估算默认值"));
        assertTrue(result.reply().contains("参考餐单"));
    }

    @Test
    void shouldFallbackToLocalSectionsWhenLlmFails() {
        HealthLifePlanSkill skill = skill((request, context) -> {
            throw new LlmException("模型繁忙");
        });

        SkillResult result = skill.execute(context("性别：男 身高：175cm 体重：70kg 想减脂"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("热身 5～10 分钟"));
        assertTrue(result.reply().contains("饮水：每日 1500～2500 毫升"));
        assertTrue(result.reply().contains("五、安全提醒"));
    }

    @Test
    void shouldDiscardAnswerbackAndUseLocalSectionWhenModelAsksForProfile() {
        HealthLifePlanSkill skill = skill((request, context) -> ChatOutcome.text("先确认一个信息：你的性别和年龄？"));

        SkillResult result = skill.execute(context("性别：女 年龄：20 身高：165cm 体重：55kg 想减脂"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertFalse(result.reply().contains("性别和年龄"));
        assertTrue(result.reply().contains("热身 5～10 分钟"));
        assertTrue(result.reply().contains("饮水：每日 1500～2500 毫升"));
    }

    @Test
    void shouldRefuseUnsupportedHealthConditions() {
        HealthLifePlanSkill skill = skill((request, context) -> ChatOutcome.text("回复"));

        SkillResult result = skill.execute(context("我有糖尿病，想减肥"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("医生"));
    }

    @Test
    void shouldWriteActionItemsToPersonalTodo() {
        TodoService todos = new TodoService();
        HealthLifePlanSkill skill = new HealthLifePlanSkill(
                (request, context) -> ChatOutcome.text("方案"),
                rag(),
                new FoodCatalog(new ObjectMapper()),
                todos,
                weather(),
                subscriptions());

        skill.execute(context("性别：男 身高：175cm 体重：70kg 想减脂"));

        assertEquals(3, todos.list("user-a").size());
        assertTrue(todos.list("user-a").getFirst().contains("千卡"));
    }

    @Test
    void shouldEnrichSectionsWithRetrievedHealthKnowledge() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        HealthLifePlanSkill skill = new HealthLifePlanSkill(
                (request, context) -> {
                    captured.set(request);
                    return ChatOutcome.text("方案");
                },
                rag(),
                new FoodCatalog(new ObjectMapper()),
                new TodoService(),
                weather(),
                subscriptions());

        skill.execute(context("性别：男 身高：175cm 体重：70kg 想减脂"));

        String grounding = captured.get().groundingContext();
        assertTrue(grounding.contains("热量缺口"));
        assertTrue(grounding.contains("参考餐单"));
    }

    @Test
    void shouldInjectWeatherIntoGroundingWhenCityProvided() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        HealthLifePlanSkill skill = new HealthLifePlanSkill(
                (request, context) -> {
                    captured.set(request);
                    return ChatOutcome.text("方案");
                },
                rag(),
                new FoodCatalog(new ObjectMapper()),
                new TodoService(),
                weather(),
                subscriptions());

        skill.execute(context("性别：男 身高：175cm 体重：70kg 想减脂，我在北京"));

        String grounding = captured.get().groundingContext();
        assertTrue(grounding.contains("近期天气"));
        assertTrue(grounding.contains("小雨"));
    }

    @Test
    void shouldContinueWhenWeatherQueryFails() {
        HealthLifePlanSkill skill = new HealthLifePlanSkill(
                (request, context) -> ChatOutcome.text("""
                        一、目标与身体指标
                        BMI 22.9，每日消耗 2336 千卡，建议摄入 1869 千卡，蛋白质 126g
                        二、参考餐单
                        早餐：燕麦片 55g、全脂牛奶 195g、鸡蛋 80g
                        三、运动方案
                        每周 3 次有氧加 2 次力量训练。
                        四、作息与执行
                        每晚睡眠 7～9 小时。
                        五、安全提醒
                        本计划为一般性估算，不替代医疗或个体化营养建议。
                        """),
                rag(),
                new FoodCatalog(new ObjectMapper()),
                new TodoService(),
                (location, period) -> {
                    throw new WeatherLocationNotFoundException(location);
                },
                subscriptions());

        SkillResult result = skill.execute(context("性别：男 身高：175cm 体重：70kg 想减脂，我在北京"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("参考餐单"));
    }

    private HealthLifePlanSkill skill(ChatModelClient chat) {
        return new HealthLifePlanSkill(
                chat, rag(), new FoodCatalog(new ObjectMapper()), new TodoService(), weather(), subscriptions());
    }

    private ReminderSubscriptionManager subscriptions() {
        return new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString());
    }

    private WeatherClient weather() {
        return (location, period) -> new WeatherReport(
                location,
                "2026-08-26 08:00",
                period,
                new CurrentWeather("小雨", "24", "80%", "东南", "3"),
                List.of());
    }

    private RagRetriever rag() {
        return new KeywordRagRetriever(new RagProperties(true, 3, 2, 2_500), new ObjectMapper());
    }

    private SkillContext context(String text) {
        return new SkillContext("user-a", text, List.of(), false);
    }
}
