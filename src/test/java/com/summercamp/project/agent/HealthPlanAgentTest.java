package com.summercamp.project.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.agent.artifact.HealthPlanAssembler;
import com.summercamp.project.agent.artifact.HealthPlanPageService;
import com.summercamp.project.agent.evaluation.HealthPlanEvaluator;
import com.summercamp.project.agent.execution.TaskScheduler;
import com.summercamp.project.agent.model.StepStatus;
import com.summercamp.project.agent.planning.HealthGoalParser;
import com.summercamp.project.agent.planning.HealthGoalValidator;
import com.summercamp.project.agent.planning.TaskPlanner;
import com.summercamp.project.agent.store.InMemoryAgentRunStore;
import com.summercamp.project.agent.store.CompletedHealthPlanStore;
import com.summercamp.project.agent.store.PendingHealthGoalStore;
import com.summercamp.project.config.HealthAgentProperties;
import com.summercamp.project.config.AgentOptimizationProperties;
import com.summercamp.project.config.ResultPageProperties;
import com.summercamp.project.config.HealthReminderProperties;
import com.summercamp.project.llm.GeneratedImage;
import com.summercamp.project.llm.ImageGenerationClient;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagDocument;
import com.summercamp.project.rag.RagRetriever;
import com.summercamp.project.result.ResultPageService;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.skill.health.ExerciseHealthAdviceSkill;
import com.summercamp.project.skill.nutrition.MuscleGainMealPlanSkill;
import com.summercamp.project.tool.QrCodeTool;
import com.summercamp.project.weather.ForecastDay;
import com.summercamp.project.weather.WeatherPeriod;
import com.summercamp.project.weather.WeatherReport;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealthPlanAgentTest {

    private HealthPlanAgent agent;
    private InMemoryAgentRunStore runStore;

    @BeforeEach
    void setUp() {
        HealthAgentProperties properties = new HealthAgentProperties(true, Duration.ofMinutes(30), false);
        ObjectMapper objectMapper = new ObjectMapper();
        runStore = new InMemoryAgentRunStore();
        SkillRegistry skills = new SkillRegistry(List.of(
                skill(MuscleGainMealPlanSkill.SKILL_NAME, "训练日和休息日营养计划"),
                skill(ExerciseHealthAdviceSkill.SKILL_NAME, "力量训练、恢复和室内替代方案")));
        ResultPageProperties pageProperties = new ResultPageProperties(
                "http://192.168.1.8:8080", 8080, Duration.ofHours(2));
        ResultPageService resultPages = new ResultPageService(pageProperties);
        HealthPlanPageService healthPages = new HealthPlanPageService(pageProperties, resultPages);
        Clock clock = Clock.systemUTC();
        agent = new HealthPlanAgent(
                properties,
                new AgentRouter(properties),
                new HealthGoalParser(),
                new HealthGoalValidator(),
                new PendingHealthGoalStore(properties),
                new TaskPlanner(),
                new TaskScheduler(),
                runStore,
                new CompletedHealthPlanStore(new HealthReminderProperties(
                        true, Duration.ofSeconds(30), Duration.ofDays(14), "Asia/Shanghai")),
                query -> new RagContext(List.of(new RagContext.Hit(
                        new RagDocument("healthy", "健康生活", List.of("健康生活"), "参考"), 4)), "参考资料"),
                (location, period) -> weather(location, period),
                skills,
                new DisabledImageClient(),
                new HealthPlanAssembler(),
                new HealthPlanEvaluator(),
                healthPages,
                new QrCodeTool(objectMapper),
                new AgentPromptOptimizer(new AgentOptimizationProperties(
                        true, Duration.ofMinutes(10), Duration.ofMinutes(30), 20, 1200, 6, 1600)),
                objectMapper,
                clock);
    }

    @Test
    void completesAFullGoalWithAResultPageAndQrCode() {
        HealthAgentResult result = agent.execute("user-1", completeGoal(), List.of());

        assertThat(result.status()).isEqualTo(HealthAgentResult.Status.COMPLETED);
        assertThat(result.reply()).contains("完整计划页面：http://192.168.1.8:8080/health-plans/");
        assertThat(result.media()).singleElement().satisfies(media -> {
            assertThat(media.fileName()).isEqualTo("health-plan-qr.png");
            assertThat(media.data()).isNotEmpty();
        });
        assertThat(runStore.latest("user-1")).isPresent();
        assertThat(runStore.latest("user-1").orElseThrow().state("generate-result-qr").status())
                .isEqualTo(StepStatus.SUCCEEDED);
        assertThat(agent.progress("user-1")).contains("100%", "任务已完成");
    }

    @Test
    void cancelsStoredRunAndCompletedPlan() {
        agent.execute("cancel-user", completeGoal(), List.of());

        assertThat(agent.cancel("cancel-user")).isTrue();
        assertThat(runStore.latest("cancel-user")).isEmpty();
        assertThat(agent.progress("cancel-user")).isEqualTo("当前没有健康规划任务。");
    }

    @Test
    void asksForAllMissingFieldsAndContinuesWithTheNextMessage() {
        HealthAgentResult first = agent.execute(
                "user-2", "帮我制定未来7天的完整增肌健康生活方案", List.of());

        assertThat(first.status()).isEqualTo(HealthAgentResult.Status.WAITING_INPUT);
        assertThat(first.reply()).contains("性别", "年龄", "所在城市", "健康确认");

        HealthAgentResult second = agent.execute("user-2", """
                性别：男，年龄：20岁，身高：175cm，体重：70kg，所在城市：上海，
                每周训练：4次，每次训练：60分钟，每日餐数：4餐，健康确认：健康成人、无食物过敏
                """, List.of());

        assertThat(second.status()).isEqualTo(HealthAgentResult.Status.COMPLETED);
    }

    @Test
    void blocksMedicalRiskInsteadOfGeneratingAPlan() {
        HealthAgentResult result = agent.execute("user-3", completeGoal() + "，但是我有高血压", List.of());

        assertThat(result.status()).isEqualTo(HealthAgentResult.Status.BLOCKED);
        assertThat(result.reply()).contains("请先咨询医生");
        assertThat(runStore.latest("user-3")).isEmpty();
    }

    @Test
    void completesAllFourSupportedGoalsWithEveryDailySection() {
        List<String> goals = List.of("增肌", "减脂", "提升体能", "规律作息");

        for (int index = 0; index < goals.size(); index++) {
            HealthAgentResult result = agent.execute(
                    "goal-user-" + index, completeGoal().replace("增肌", goals.get(index)), List.of());

            assertThat(result.status()).as(goals.get(index)).isEqualTo(HealthAgentResult.Status.COMPLETED);
            com.summercamp.project.agent.artifact.HealthPlanArtifact artifact =
                    (com.summercamp.project.agent.artifact.HealthPlanArtifact) runStore
                            .latest("goal-user-" + index).orElseThrow().output("assemble-daily-schedule");
            assertThat(artifact.content()).contains(
                    "第1天：", "第2天：", "第3天：", "第4天：", "第5天：", "第6天：", "第7天：",
                    "饮食与营养建议", "训练建议", "恢复与安全");
        }
    }

    @Test
    void resumesFromTheFailedStepWithoutRepeatingWeatherOrRag() {
        AtomicInteger evaluationCalls = new AtomicInteger();
        AtomicInteger weatherCalls = new AtomicInteger();
        HealthPlanEvaluator transientEvaluator = new HealthPlanEvaluator() {
            @Override
            public com.summercamp.project.agent.evaluation.EvaluationReport evaluate(
                    com.summercamp.project.agent.model.HealthGoal goal,
                    com.summercamp.project.agent.artifact.HealthPlanArtifact artifact) {
                if (evaluationCalls.incrementAndGet() <= 2) {
                    throw new IllegalStateException("temporary evaluation failure");
                }
                return super.evaluate(goal, artifact);
            }
        };
        agent = createAgent(transientEvaluator, weatherCalls);

        HealthAgentResult first = agent.execute("resume-user", completeGoal(), List.of());
        HealthAgentResult resumed = agent.resume("resume-user", List.of());

        assertThat(first.status()).isEqualTo(HealthAgentResult.Status.INTERRUPTED);
        assertThat(first.reply()).contains("继续刚才的健康计划");
        assertThat(resumed.status()).isEqualTo(HealthAgentResult.Status.COMPLETED);
        assertThat(weatherCalls).hasValue(1);
        assertThat(evaluationCalls).hasValue(3);
    }

    @Test
    void reportsLiveProgressAndCooperativelyCancelsAnActiveRun() throws Exception {
        CountDownLatch ragStarted = new CountDownLatch(1);
        CountDownLatch releaseRag = new CountDownLatch(1);
        RagRetriever blockingRag = query -> {
            ragStarted.countDown();
            try {
                if (!releaseRag.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timeout");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new RagContext(List.of(), "");
        };
        agent = createAgent(new HealthPlanEvaluator(), null, blockingRag);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> agent.execute("active-user", completeGoal(), List.of()));
            assertThat(ragStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(agent.progress("active-user")).contains("健康规划任务进度", "检索健康知识");
            assertThat(agent.cancel("active-user")).isTrue();
            releaseRag.countDown();

            assertThat(result.get(5, TimeUnit.SECONDS).status()).isEqualTo(HealthAgentResult.Status.CANCELLED);
            assertThat(agent.progress("active-user")).isEqualTo("当前没有健康规划任务。");
        } finally {
            releaseRag.countDown();
        }
    }

    private String completeGoal() {
        return """
                帮我制定未来7天的完整增肌健康生活方案。
                性别：男，年龄：20岁，身高：175cm，体重：70kg，所在城市：上海，
                每周训练：4次，每次训练：60分钟，每日餐数：4餐，健康确认：健康成人、无食物过敏。
                """;
    }

    private BotSkill skill(String name, String reply) {
        return new BotSkill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return 1;
            }

            @Override
            public int matchScore(String text) {
                return 0;
            }

            @Override
            public SkillResult execute(SkillContext context) {
                return SkillResult.completed(reply);
            }
        };
    }

    private HealthPlanAgent createAgent(HealthPlanEvaluator evaluator, AtomicInteger weatherCalls) {
        return createAgent(evaluator, weatherCalls, query -> new RagContext(List.of(new RagContext.Hit(
                new RagDocument("healthy", "健康生活", List.of("健康生活"), "参考"), 4)), "参考资料"));
    }

    private HealthPlanAgent createAgent(
            HealthPlanEvaluator evaluator,
            AtomicInteger weatherCalls,
            RagRetriever ragRetriever) {
        HealthAgentProperties properties = new HealthAgentProperties(true, Duration.ofMinutes(30), false);
        ObjectMapper objectMapper = new ObjectMapper();
        runStore = new InMemoryAgentRunStore();
        SkillRegistry skills = new SkillRegistry(List.of(
                skill(MuscleGainMealPlanSkill.SKILL_NAME, "训练日和休息日营养计划"),
                skill(ExerciseHealthAdviceSkill.SKILL_NAME, "力量训练、恢复和室内替代方案")));
        ResultPageProperties pageProperties = new ResultPageProperties(
                "http://192.168.1.8:8080", 8080, Duration.ofHours(2));
        ResultPageService resultPages = new ResultPageService(pageProperties);
        HealthPlanPageService healthPages = new HealthPlanPageService(pageProperties, resultPages);
        return new HealthPlanAgent(
                properties,
                new AgentRouter(properties),
                new HealthGoalParser(),
                new HealthGoalValidator(),
                new PendingHealthGoalStore(properties),
                new TaskPlanner(),
                new TaskScheduler(),
                runStore,
                new CompletedHealthPlanStore(new HealthReminderProperties(
                        true, Duration.ofSeconds(30), Duration.ofDays(14), "Asia/Shanghai")),
                ragRetriever,
                (location, period) -> {
                    if (weatherCalls != null) {
                        weatherCalls.incrementAndGet();
                    }
                    return weather(location, period);
                },
                skills,
                new DisabledImageClient(),
                new HealthPlanAssembler(),
                evaluator,
                healthPages,
                new QrCodeTool(objectMapper),
                new AgentPromptOptimizer(new AgentOptimizationProperties(
                        true, Duration.ofMinutes(10), Duration.ofMinutes(30), 20, 1200, 6, 1600)),
                objectMapper,
                Clock.systemUTC());
    }

    private WeatherReport weather(String location, WeatherPeriod period) {
        return new WeatherReport(location, "2026-08-25 08:00:00", period, null, List.of(
                new ForecastDay("2026-08-25", "2", "晴", "晴", "31", "23", "东", "东", "1-3", "1-3"),
                new ForecastDay("2026-08-26", "3", "多云", "多云", "30", "22", "东", "东", "1-3", "1-3"),
                new ForecastDay("2026-08-27", "4", "小雨", "阴", "28", "21", "北", "北", "1-3", "1-3")));
    }

    private static final class DisabledImageClient implements ImageGenerationClient {
        @Override
        public GeneratedImage generate(List<com.summercamp.project.llm.ChatMessage> history, String prompt) {
            throw new AssertionError("封面生成已在测试配置中关闭");
        }
    }
}
