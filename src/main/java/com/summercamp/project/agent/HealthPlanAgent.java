package com.summercamp.project.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.agent.artifact.HealthPlanArtifact;
import com.summercamp.project.agent.artifact.HealthPlanAssembler;
import com.summercamp.project.agent.artifact.HealthPlanPage;
import com.summercamp.project.agent.artifact.HealthPlanPageService;
import com.summercamp.project.agent.evaluation.EvaluationReport;
import com.summercamp.project.agent.evaluation.HealthPlanEvaluator;
import com.summercamp.project.agent.execution.AgentStepExecutionException;
import com.summercamp.project.agent.execution.AgentCancelledException;
import com.summercamp.project.agent.execution.TaskScheduler;
import com.summercamp.project.agent.model.AgentPlan;
import com.summercamp.project.agent.model.AgentRun;
import com.summercamp.project.agent.model.HealthGoal;
import com.summercamp.project.agent.model.HealthGoalType;
import com.summercamp.project.agent.model.StepStatus;
import com.summercamp.project.agent.planning.HealthGoalParser;
import com.summercamp.project.agent.planning.HealthGoalValidator;
import com.summercamp.project.agent.planning.TaskPlanner;
import com.summercamp.project.agent.store.AgentRunStore;
import com.summercamp.project.agent.store.CompletedHealthPlanStore;
import com.summercamp.project.agent.store.PendingHealthGoalStore;
import com.summercamp.project.config.HealthAgentProperties;
import com.summercamp.project.llm.ChatMessage;
import com.summercamp.project.llm.GeneratedImage;
import com.summercamp.project.llm.ImageGenerationClient;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagRetriever;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.skill.health.ExerciseHealthAdviceSkill;
import com.summercamp.project.skill.nutrition.MuscleGainMealPlanSkill;
import com.summercamp.project.tool.QrCodeTool;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.tool.ToolResult;
import com.summercamp.project.weather.WeatherClient;
import com.summercamp.project.weather.WeatherPeriod;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HealthPlanAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthPlanAgent.class);
    private static final String INPUT_EXAMPLE = """
            例如：我想要一份未来7天的增肌健康生活方案。性别：男，年龄：20岁，身高：175cm，体重：70kg，
            所在城市：上海，每周训练：4次，每次训练：60分钟，每日餐数：4餐，健康确认：健康成人、无食物过敏。
            """;

    private final HealthAgentProperties properties;
    private final AgentRouter router;
    private final HealthGoalParser goalParser;
    private final HealthGoalValidator goalValidator;
    private final PendingHealthGoalStore pendingGoalStore;
    private final TaskPlanner taskPlanner;
    private final TaskScheduler scheduler;
    private final AgentRunStore runStore;
    private final CompletedHealthPlanStore completedPlanStore;
    private final RagRetriever ragRetriever;
    private final WeatherClient weatherClient;
    private final SkillRegistry skillRegistry;
    private final ImageGenerationClient imageClient;
    private final HealthPlanAssembler assembler;
    private final HealthPlanEvaluator evaluator;
    private final HealthPlanPageService pageService;
    private final QrCodeTool qrCodeTool;
    private final AgentPromptOptimizer promptOptimizer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, AgentRun> activeRuns = new ConcurrentHashMap<>();

    @Autowired
    public HealthPlanAgent(
            HealthAgentProperties properties,
            AgentRouter router,
            HealthGoalParser goalParser,
            HealthGoalValidator goalValidator,
            PendingHealthGoalStore pendingGoalStore,
            TaskPlanner taskPlanner,
            TaskScheduler scheduler,
            AgentRunStore runStore,
            CompletedHealthPlanStore completedPlanStore,
            RagRetriever ragRetriever,
            WeatherClient weatherClient,
            SkillRegistry skillRegistry,
            ImageGenerationClient imageClient,
            HealthPlanAssembler assembler,
            HealthPlanEvaluator evaluator,
            HealthPlanPageService pageService,
            QrCodeTool qrCodeTool,
            AgentPromptOptimizer promptOptimizer,
            ObjectMapper objectMapper) {
        this(properties, router, goalParser, goalValidator, pendingGoalStore, taskPlanner, scheduler,
                runStore, completedPlanStore, ragRetriever, weatherClient, skillRegistry, imageClient, assembler,
                evaluator, pageService, qrCodeTool, promptOptimizer, objectMapper, Clock.systemUTC());
    }

    HealthPlanAgent(
            HealthAgentProperties properties,
            AgentRouter router,
            HealthGoalParser goalParser,
            HealthGoalValidator goalValidator,
            PendingHealthGoalStore pendingGoalStore,
            TaskPlanner taskPlanner,
            TaskScheduler scheduler,
            AgentRunStore runStore,
            CompletedHealthPlanStore completedPlanStore,
            RagRetriever ragRetriever,
            WeatherClient weatherClient,
            SkillRegistry skillRegistry,
            ImageGenerationClient imageClient,
            HealthPlanAssembler assembler,
            HealthPlanEvaluator evaluator,
            HealthPlanPageService pageService,
            QrCodeTool qrCodeTool,
            AgentPromptOptimizer promptOptimizer,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.router = router;
        this.goalParser = goalParser;
        this.goalValidator = goalValidator;
        this.pendingGoalStore = pendingGoalStore;
        this.taskPlanner = taskPlanner;
        this.scheduler = scheduler;
        this.runStore = runStore;
        this.completedPlanStore = completedPlanStore;
        this.ragRetriever = ragRetriever;
        this.weatherClient = weatherClient;
        this.skillRegistry = skillRegistry;
        this.imageClient = imageClient;
        this.assembler = assembler;
        this.evaluator = evaluator;
        this.pageService = pageService;
        this.qrCodeTool = qrCodeTool;
        this.promptOptimizer = promptOptimizer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public boolean supports(String text) {
        return router.supports(text);
    }

    public boolean hasPending(String userId) {
        return pendingGoalStore.get(userId).isPresent();
    }

    public boolean canResume(String userId) {
        return runStore.latest(userId).filter(AgentRun::resumable).isPresent();
    }

    public String progress(String userId) {
        if (pendingGoalStore.get(userId).isPresent()) {
            return "健康规划任务正在等待你补充资料。请继续发送缺少的信息，或发送“取消健康计划”。";
        }
        AgentRun run = activeRuns.get(userId);
        if (run == null) {
            run = runStore.latest(userId).orElse(null);
        }
        if (run == null) {
            return completedPlanStore.latest(userId).isPresent()
                    ? "最近的健康计划已经完成。你可以查看之前收到的结果页，或发送新的规划目标。"
                    : "当前没有健康规划任务。";
        }
        Map<String, AgentRun.StepState> states = run.states();
        long succeeded = count(states, StepStatus.SUCCEEDED);
        long skipped = count(states, StepStatus.SKIPPED);
        long failed = count(states, StepStatus.FAILED);
        long running = count(states, StepStatus.RUNNING);
        int percent = (int) ((succeeded + skipped) * 100 / states.size());
        String current = states.entrySet().stream()
                .filter(entry -> entry.getValue().status() == StepStatus.RUNNING)
                .findFirst()
                .or(() -> states.entrySet().stream()
                        .filter(entry -> entry.getValue().status() == StepStatus.FAILED)
                        .findFirst())
                .or(() -> states.entrySet().stream()
                        .filter(entry -> entry.getValue().status() == StepStatus.PENDING)
                        .findFirst())
                .map(entry -> stepName(entry.getKey()))
                .orElse("任务已完成");
        StringBuilder reply = new StringBuilder()
                .append("健康规划任务进度：").append(percent).append("%（")
                .append(succeeded).append(" 个步骤成功，")
                .append(skipped).append(" 个步骤降级跳过）\n")
                .append("当前阶段：").append(current).append('。');
        if (failed > 0) {
            reply.append("\n有 ").append(failed)
                    .append(" 个步骤执行失败，可以发送“继续刚才的健康计划”从断点恢复。");
        } else if (running > 0) {
            reply.append("\n任务仍在执行，请稍候查看结果。");
        } else if (run.resumable()) {
            reply.append("\n可以发送“继续刚才的健康计划”继续执行。");
        }
        return reply.toString();
    }

    public boolean cancel(String userId) {
        AgentRun active = activeRuns.get(userId);
        boolean existed = pendingGoalStore.get(userId).isPresent()
                || active != null
                || runStore.latest(userId).isPresent()
                || completedPlanStore.latest(userId).isPresent();
        if (active != null) {
            active.cancel();
        }
        clear(userId);
        return existed;
    }

    public void clear(String userId) {
        AgentRun active = activeRuns.get(userId);
        if (active != null) {
            active.cancel();
        }
        pendingGoalStore.clear(userId);
        runStore.clear(userId);
        completedPlanStore.clear(userId);
    }

    public HealthAgentResult execute(String userId, String text, List<ChatMessage> history) {
        if (activeRuns.containsKey(userId)) {
            return HealthAgentResult.waiting("健康规划任务正在执行，请发送“查看任务进度”了解当前阶段。");
        }
        String accumulated = pendingGoalStore.get(userId)
                .map(previous -> previous + "\n" + text)
                .orElse(text == null ? "" : text);
        HealthGoal goal = goalParser.parse(accumulated);
        HealthGoalValidator.ValidationResult validation = goalValidator.validate(goal);
        if (safetyBlocked(goal)) {
            clear(userId);
            return HealthAgentResult.blocked("""
                    为了安全，当前健康规划 Agent 只为无食物过敏的健康成年人提供一般建议。
                    你提供的信息涉及疾病、伤病、孕期、进食障碍、药物影响或食物过敏，请先咨询医生、注册营养师或康复师后再制定个体计划。
                    """);
        }
        if (!validation.valid()) {
            if (!validation.errors().isEmpty()) {
                pendingGoalStore.clear(userId);
                return HealthAgentResult.waiting("资料中存在以下问题："
                        + String.join("；", validation.errors()) + "。请修正后完整重发。\n\n" + INPUT_EXAMPLE);
            }
            pendingGoalStore.remember(userId, accumulated);
            return HealthAgentResult.waiting("还需要一次性补充这些信息："
                    + String.join("、", validation.missingFields()) + "。\n\n" + INPUT_EXAMPLE);
        }
        pendingGoalStore.clear(userId);
        return runPlan(userId, goal, history == null ? List.of() : history);
    }

    public HealthAgentResult resume(String userId, List<ChatMessage> history) {
        if (activeRuns.containsKey(userId)) {
            return HealthAgentResult.waiting("健康规划任务正在执行，无需重复继续。可以发送“查看任务进度”。");
        }
        AgentRun run = runStore.latest(userId).orElse(null);
        if (run == null || !run.resumable()) {
            return HealthAgentResult.waiting("没有可以继续的健康计划任务，请先发送一个新的健康规划目标。");
        }
        run.prepareForResume();
        return runTracked(run, history == null ? List.of() : history);
    }

    private HealthAgentResult runPlan(String userId, HealthGoal goal, List<ChatMessage> history) {
        AgentPlan plan = taskPlanner.createHealthPlan();
        Instant now = clock.instant();
        AgentRun run = new AgentRun(
                UUID.randomUUID().toString(), userId, goal, plan, now, now.plus(properties.pendingTtl()));
        runStore.save(run);
        run.start("parse-goal");
        run.succeed("parse-goal", goal);
        run.start("validate-goal");
        run.succeed("validate-goal", "valid");

        return runTracked(run, history);
    }

    private HealthAgentResult runTracked(AgentRun run, List<ChatMessage> history) {
        AgentRun existing = activeRuns.putIfAbsent(run.userId(), run);
        if (existing != null && existing != run) {
            return HealthAgentResult.waiting("健康规划任务正在执行，请发送“查看任务进度”了解当前阶段。");
        }
        try {
            return continueSafely(run, history);
        } finally {
            activeRuns.remove(run.userId(), run);
        }
    }

    private HealthAgentResult continueSafely(AgentRun run, List<ChatMessage> history) {
        try {
            return continueRun(run, promptOptimizer.relevantHistory(history));
        } catch (AgentCancelledException exception) {
            runStore.clear(run.userId());
            LOGGER.info("健康 Agent 已取消：runId={}", run.id());
            return HealthAgentResult.cancelled();
        } catch (RuntimeException exception) {
            LOGGER.warn("健康 Agent 已保存断点：runId={} error={}",
                    run.id(), exception.getClass().getSimpleName());
            return HealthAgentResult.interrupted(
                    "健康计划执行到一半时遇到临时问题，已保存成功步骤。请稍后发送“继续刚才的健康计划”，"
                            + "系统会从失败步骤继续，不会重复查询已完成的数据。");
        }
    }

    private HealthAgentResult continueRun(AgentRun run, List<ChatMessage> history) {
        HealthGoal goal = run.goal();

        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        Map<String, Supplier<?>> independent = new LinkedHashMap<>();
        addIfPending(run, independent, "retrieve-health-knowledge",
                () -> promptOptimizer.compact(ragRetriever.retrieve(ragQuery(goal))));
        addIfPending(run, independent, "query-weather", () -> queryWeather(goal, warnings));
        addIfPending(run, independent, "calculate-nutrition",
                () -> nutritionPlan(run.userId(), goal, history, warnings));
        Map<String, TaskScheduler.StepResult<Object>> parallel = scheduler.executeParallel(run, independent);

        if (parallel.containsKey("retrieve-health-knowledge")
                && !parallel.get("retrieve-health-knowledge").succeeded()) {
            run.skip("retrieve-health-knowledge", "RAG_UNAVAILABLE");
            warnings.add("健康知识库暂时不可用");
        }
        RagContext rag = output(run, "retrieve-health-knowledge", RagContext.class, RagContext.empty());
        String weather = output(run, "query-weather", String.class, weatherFallback(goal));
        String nutrition = output(run, "calculate-nutrition", String.class, nutritionFallback(goal));

        String exercise = executeOrReuse(run, "generate-exercise-plan", String.class,
                () -> exercisePlan(run.userId(), goal, weather, rag, history, warnings));
        String mealSchedule = executeOrReuse(run, "generate-meal-schedule", String.class, () -> nutrition);
        HealthPlanArtifact artifact = executeOrReuse(run, "assemble-daily-schedule", HealthPlanArtifact.class,
                () -> assembler.assemble(goal, weather, mealSchedule, exercise, rag, warnings));
        executeOrReuse(run, "evaluate-plan", EvaluationReport.class, () -> {
            EvaluationReport report = evaluator.evaluate(goal, artifact);
            if (!report.valid()) {
                throw new AgentStepExecutionException(
                        "健康计划完整性检查失败：" + String.join("；", report.issues()), null);
            }
            return report;
        });

        List<HealthAgentResult.Media> media = new ArrayList<>();
        generateCover(run, goal, history, media, warnings);
        String pageUrl = createPageAndQr(run, artifact, run.userId(), history, media, warnings);
        run.ensureActive();
        completedPlanStore.save(run.userId(), goal, artifact);
        String reply = finalReply(run, artifact, pageUrl, warnings);
        LOGGER.info("健康 Agent 完成：runId={} succeededSteps={} totalSteps={}",
                run.id(), succeededSteps(run), run.plan().steps().size());
        return HealthAgentResult.completed(reply, media);
    }

    private String queryWeather(HealthGoal goal, List<String> warnings) {
        try {
            return weatherClient.query(goal.location(), WeatherPeriod.THREE_DAYS).formatChinese();
        } catch (RuntimeException exception) {
            warnings.add("天气查询失败，已采用室内保守训练方案；出发前请重新确认当地天气");
            return weatherFallback(goal);
        }
    }

    private String nutritionPlan(
            String userId,
            HealthGoal goal,
            List<ChatMessage> history,
            List<String> warnings) {
        if (goal.goalType() != HealthGoalType.MUSCLE_GAIN) {
            return nutritionFallback(goal);
        }
        try {
            BotSkill skill = skillRegistry.findByName(MuscleGainMealPlanSkill.SKILL_NAME)
                    .orElseThrow(() -> new IllegalStateException("缺少增肌饮食 Skill"));
            SkillResult result = skill.execute(new SkillContext(
                    userId, nutritionSkillInput(goal), history, false));
            if (result.status() != SkillResult.Status.COMPLETED) {
                throw new IllegalStateException("增肌饮食 Skill 要求补充信息");
            }
            return result.reply();
        } catch (RuntimeException exception) {
            warnings.add("营养 Skill 暂时不可用，已采用本地保守估算");
            return nutritionFallback(goal);
        }
    }

    private String exercisePlan(
            String userId,
            HealthGoal goal,
            String weather,
            RagContext rag,
            List<ChatMessage> history,
            List<String> warnings) {
        try {
            BotSkill skill = skillRegistry.findByName(ExerciseHealthAdviceSkill.SKILL_NAME)
                    .orElseThrow(() -> new IllegalStateException("缺少运动健康 Skill"));
            String prompt = "请根据完整资料直接给出一周训练框架，不要继续追问。"
                    + "目标：" + goal.goalType().chineseName()
                    + "；每周训练：" + goal.trainingDaysPerWeek() + "次"
                    + "；每次：" + goal.minutesPerSession() + "分钟"
                    + "；健康成人、无食物过敏。天气：" + promptOptimizer.compactWeather(weather)
                    + "\n参考资料：" + rag.promptContext();
            return skill.execute(new SkillContext(userId, prompt, history, false)).reply();
        } catch (RuntimeException exception) {
            warnings.add("运动健康 Skill 暂时不可用，已采用本地保守训练框架");
            return exerciseFallback(goal);
        }
    }

    private void generateCover(
            AgentRun run,
            HealthGoal goal,
            List<ChatMessage> history,
            List<HealthAgentResult.Media> media,
            List<String> warnings) {
        GeneratedImage existing = output(run, "generate-cover", GeneratedImage.class, null);
        if (existing != null) {
            media.add(new HealthAgentResult.Media(existing.data(), existing.fileName(), "健康计划封面"));
            return;
        }
        if (run.state("generate-cover").status() == com.summercamp.project.agent.model.StepStatus.SKIPPED) {
            return;
        }
        if (!properties.generateCover()) {
            run.skip("generate-cover", "DISABLED");
            return;
        }
        try {
            GeneratedImage image = scheduler.execute(run, "generate-cover", () -> imageClient.generate(
                    List.of(),
                    "简洁清新的大学生健康生活计划封面，包含运动、均衡饮食、睡眠和"
                            + goal.location() + "城市元素，不要出现文字和品牌标志"));
            media.add(new HealthAgentResult.Media(image.data(), image.fileName(), "健康计划封面"));
        } catch (AgentCancelledException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            run.skip("generate-cover", "IMAGE_UNAVAILABLE");
            warnings.add("封面图片生成失败，不影响文字计划和结果页面");
        }
    }

    private String createPageAndQr(
            AgentRun run,
            HealthPlanArtifact artifact,
            String userId,
            List<ChatMessage> history,
            List<HealthAgentResult.Media> media,
            List<String> warnings) {
        HealthPlanPage page;
        try {
            page = executeOrReuse(run, "create-result-page", HealthPlanPage.class, () -> pageService.create(artifact));
        } catch (AgentCancelledException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            run.skip("create-result-page", "PAGE_UNAVAILABLE");
            run.skip("generate-result-qr", "PAGE_UNAVAILABLE");
            warnings.add("结果页面生成失败，已直接发送文字计划");
            return "";
        }
        String url = pageService.publicUrl(page);
        ToolResult.Image existingQr = output(run, "generate-result-qr", ToolResult.Image.class, null);
        if (existingQr != null) {
            media.add(new HealthAgentResult.Media(
                    existingQr.data(), "health-plan-qr.png", "扫码查看完整健康计划"));
            return url;
        }
        try {
            ObjectNode arguments = objectMapper.createObjectNode().put("text", url).put("size", 420);
            ToolResult result = scheduler.execute(run, "generate-result-qr", () -> qrCodeTool.execute(
                    arguments, new ToolContext(userId, "为健康计划结果页生成二维码", history)));
            if (result instanceof ToolResult.Image image) {
                media.add(new HealthAgentResult.Media(image.data(), "health-plan-qr.png", "扫码查看完整健康计划"));
            } else {
                throw new IllegalStateException("二维码工具未返回图片");
            }
        } catch (AgentCancelledException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            run.skip("generate-result-qr", "QR_UNAVAILABLE");
            warnings.add("二维码生成失败，请直接打开结果页链接");
        }
        return url;
    }

    private String finalReply(
            AgentRun run,
            HealthPlanArtifact artifact,
            String pageUrl,
            List<String> warnings) {
        if (pageUrl.isBlank()) {
            return artifact.content();
        }
        StringBuilder reply = new StringBuilder()
                .append("健康生活规划 Agent 已完成 ")
                .append(succeededSteps(run)).append(" 个步骤。\n")
                .append("成品：").append(artifact.title()).append("\n")
                .append("完整计划页面：").append(pageUrl).append("\n")
                .append("已附二维码，可用同一局域网中的手机扫码查看。\n")
                .append("天气仅能提供未来三日预报，第 4 天起请每天重新确认。\n")
                .append("本计划仅供健康生活参考，不替代医生或营养师建议。");
        if (!warnings.isEmpty()) {
            reply.append("\n执行提醒：").append(String.join("；", warnings.stream().distinct().toList())).append('。');
        }
        return reply.toString();
    }

    private int succeededSteps(AgentRun run) {
        return (int) run.states().values().stream()
                .filter(state -> state.status() == com.summercamp.project.agent.model.StepStatus.SUCCEEDED)
                .count();
    }

    private long count(Map<String, AgentRun.StepState> states, StepStatus status) {
        return states.values().stream().filter(state -> state.status() == status).count();
    }

    private String stepName(String stepId) {
        return switch (stepId) {
            case "parse-goal" -> "解析健康目标";
            case "validate-goal" -> "校验身体资料";
            case "retrieve-health-knowledge" -> "检索健康知识";
            case "query-weather" -> "查询天气";
            case "calculate-nutrition" -> "计算营养目标";
            case "generate-exercise-plan" -> "生成训练安排";
            case "generate-meal-schedule" -> "生成饮食安排";
            case "assemble-daily-schedule" -> "汇总每日计划";
            case "evaluate-plan" -> "检查计划完整性";
            case "generate-cover" -> "生成封面";
            case "create-result-page" -> "创建结果页";
            case "generate-result-qr" -> "生成二维码";
            default -> "执行任务";
        };
    }

    private boolean safetyBlocked(HealthGoal goal) {
        return Boolean.FALSE.equals(goal.healthConfirmed())
                || Boolean.FALSE.equals(goal.noFoodAllergies())
                || !goal.safetyFlags().isEmpty();
    }

    private String ragQuery(HealthGoal goal) {
        return goal.goalType().chineseName() + "健康生活 身体活动 运动计划 饮食计划 平衡膳食 体重管理 安全提示";
    }

    private String nutritionSkillInput(HealthGoal goal) {
        return """
                帮我制定增肌饮食计划
                性别：%s
                年龄：%d
                身高：%scm
                体重：%skg
                日常活动：%s
                每周训练：%d次
                每次训练：%d分钟
                每日餐数：%d餐
                健康确认：健康成人、无食物过敏
                """.formatted(
                goal.gender(), goal.age(), format(goal.heightCm()), format(goal.weightKg()),
                goal.activityLevel(), goal.trainingDaysPerWeek(), goal.minutesPerSession(), goal.mealsPerDay());
    }

    private String nutritionFallback(HealthGoal goal) {
        double bmr = 10 * goal.weightKg() + 6.25 * goal.heightCm() - 5 * goal.age()
                + ("男".equals(goal.gender()) ? 5 : -161);
        double factor = switch (goal.activityLevel()) {
            case "高度" -> 1.725;
            case "中度" -> 1.55;
            case "轻度" -> 1.375;
            default -> 1.2;
        };
        double maintenance = bmr * factor;
        double calories = switch (goal.goalType()) {
            case MUSCLE_GAIN -> maintenance * 1.08;
            case FAT_LOSS -> maintenance * 0.85;
            case FITNESS, HEALTHY_ROUTINE -> maintenance;
        };
        double protein = goal.weightKg() * (goal.goalType() == HealthGoalType.MUSCLE_GAIN ? 1.8 : 1.6);
        return "估算维持热量约 %d kcal/天，当前目标起始值约 %d kcal/天，蛋白质约 %.0f g/天。"
                .formatted(Math.round(maintenance), Math.round(calories), protein)
                + "每日 " + goal.mealsPerDay() + " 餐，优先选择全谷物、蔬果、奶类、豆类、鱼禽蛋和瘦肉；"
                + "这只是一般估算，应根据连续 2～3 周体重、训练表现和饥饿感小幅调整。";
    }

    private String exerciseFallback(HealthGoal goal) {
        return "每周安排 " + goal.trainingDaysPerWeek() + " 次训练，每次约 "
                + goal.minutesPerSession() + " 分钟；力量训练日之间穿插恢复日，先热身再训练，"
                + "动作以可稳定完成且无疼痛为准。恶劣天气改为室内徒手或弹力带训练。";
    }

    private String weatherFallback(HealthGoal goal) {
        return goal.location() + "天气暂未获取。所有室外训练都应在当天重新查看天气；"
                + "若遇高温、暴雨、雷电或空气质量不佳，改用室内保守方案。";
    }

    private void addIfPending(
            AgentRun run,
            Map<String, Supplier<?>> actions,
            String stepId,
            Supplier<?> action) {
        if (run.state(stepId).status() == com.summercamp.project.agent.model.StepStatus.PENDING) {
            actions.put(stepId, action);
        }
    }

    private <T> T executeOrReuse(AgentRun run, String stepId, Class<T> type, Supplier<T> action) {
        T existing = output(run, stepId, type, null);
        return existing != null ? existing : scheduler.execute(run, stepId, action);
    }

    private <T> T output(AgentRun run, String stepId, Class<T> type, T fallback) {
        Object value = run.output(stepId);
        return type.isInstance(value) ? type.cast(value) : fallback;
    }

    private String format(double value) {
        return value == Math.rint(value)
                ? Long.toString(Math.round(value))
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
