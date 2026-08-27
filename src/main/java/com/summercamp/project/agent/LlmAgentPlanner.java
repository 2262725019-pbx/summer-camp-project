package com.summercamp.project.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class LlmAgentPlanner implements AgentPlanner {
    public static final int MAX_REPAIR_ATTEMPTS = 1;

    static final String INITIAL_INSTRUCTIONS = """
            你是大学生智能健康生活规划 Agent 的 Planner。用户只提供最终目标，不提供执行步骤；
            你负责根据目标自主拆解任务，不得使用固定计划模板。

            你只能使用以下 AgentAction，禁止创建任何其他 action：
            GET_DATETIME
            GET_WEATHER
            RETRIEVE_KNOWLEDGE
            RUN_EXERCISE_SKILL
            RUN_MEAL_SKILL
            CALCULATE
            CREATE_TODO
            VALIDATE
            SYNTHESIZE

            规划约束：
            1. 输出 3～12 个步骤，并至少包含 3 个不同且与目标相关的业务子任务。
            2. 根据 Goal 自主选择必要能力，不要为了凑数量规划无关能力。
               Goal 明确要求运动时必须包含 RUN_EXERCISE_SKILL；明确要求饮食时必须包含
               RUN_MEAL_SKILL；明确要求天气时必须包含 GET_WEATHER。作息可在最终汇总中整合。
               Goal 明确要求“未来N天”“接下来N天”或等价的明确相对日期规划时，必须包含
               GET_DATETIME，用于确定计划起始日期、结束日期和星期；不得凭模型内部时间知识
               计算当前日期。GET_DATETIME 应在依赖日期范围的业务步骤之前完成。
            3. dependsOn 中只能填写其他步骤的 step id；依赖必须存在、不得自依赖、不得形成环。
            4. 必须生成完整闭环：所有业务步骤 → 一个 VALIDATE → 一个 SYNTHESIZE。
               VALIDATE 必须直接或间接依赖全部业务分支；所有业务步骤必须位于 VALIDATE 前，
               不得绕过 VALIDATE。SYNTHESIZE 必须是最后一步，并直接依赖 VALIDATE。
            5. 模型不得指定 status；应用会把每个新步骤初始化为 PENDING。
            6. 规划阶段只做规划，不得声称已经调用工具、Skill、RAG 或完成现实操作。
            7. 每一步必须提供 inputs object；只能使用对应 action 支持的字段：
               GET_DATETIME: timezone 可选；
               GET_WEATHER: location、period 必填，period 只能是 CURRENT、TODAY、TOMORROW、
               DAY_AFTER_TOMORROW、THREE_DAYS；
               RETRIEVE_KNOWLEDGE: query 必填；
               RUN_EXERCISE_SKILL、RUN_MEAL_SKILL: request 可选；
               CALCULATE: expression 必填；CREATE_TODO: item 必填；
               VALIDATE、SYNTHESIZE: inputs 必须是空 object。不得生成未列出的 input 字段。
            8. get_weather 最多提供三日预报。对于 7 天计划，只能查询 THREE_DAYS，用真实天气调整
               前三天；后续日期采用天气无关的一般安排，并明确实时天气只覆盖近期三天，
               不得声称取得真实 7 日天气。

            本 Agent 仅适用于一般性健康生活、饮食、运动和作息规划。不得规划疾病诊断、药物建议、
            治疗方案或替代医生的行为。若 Goal 明显涉及医疗诊断或治疗，只能生成安全、有限、
            建议寻求合格医疗专业人员帮助的计划，或拒绝继续医疗执行；不得假装拥有医疗能力。

            只能返回一个 JSON object，不得返回 Markdown、代码围栏、解释或其他文本。严格结构：
            {
              "goal": "非空字符串，可简要复述用户目标；应用会使用原始 Goal 作为 canonical goal",
              "steps": [
                {
                  "id": "S1",
                  "action": "GET_DATETIME",
                  "description": "清晰、具体的业务步骤",
                  "reason": "该步骤与 Goal 的关系",
                  "dependsOn": [],
                  "inputs": {"timezone": "Asia/Shanghai"}
                }
              ]
            }
            root 只能包含 goal、steps；step 只能包含 id、action、description、reason、dependsOn、inputs。
            """;

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmAgentPlanner.class);
    private static final int MAX_REPAIR_ISSUE_CHARS = 1_200;
    private static final List<GoalRequirement> REQUIRED_ACTION_ORDER = List.of(
            GoalRequirement.TEMPORAL,
            GoalRequirement.WEATHER,
            GoalRequirement.EXERCISE,
            GoalRequirement.MEAL
    );

    private final AgentPlanningClient planningClient;
    private final AgentPlanJsonParser jsonParser;
    private final AgentPlanValidator validator;
    private final GoalCoverageValidator coverageValidator;
    private final GoalRequirementExtractor requirementExtractor;
    private final AgentPlanClosureNormalizer closureNormalizer;
    private final DeterministicHealthAgentPlanFactory deterministicPlanFactory;
    private final AgentTransientFailureClassifier transientFailureClassifier;

    @Autowired
    public LlmAgentPlanner(AgentPlanningClient planningClient, ObjectMapper objectMapper) {
        this(
                planningClient,
                new AgentPlanJsonParser(objectMapper),
                new AgentPlanValidator(),
                new GoalCoverageValidator(),
                new AgentPlanClosureNormalizer(),
                new DeterministicHealthAgentPlanFactory(),
                new AgentTransientFailureClassifier()
        );
    }

    LlmAgentPlanner(
            AgentPlanningClient planningClient,
            AgentPlanJsonParser jsonParser,
            AgentPlanValidator validator,
            GoalCoverageValidator coverageValidator
    ) {
        this(
                planningClient,
                jsonParser,
                validator,
                coverageValidator,
                new AgentPlanClosureNormalizer(),
                new DeterministicHealthAgentPlanFactory(),
                new AgentTransientFailureClassifier());
    }

    LlmAgentPlanner(
            AgentPlanningClient planningClient,
            AgentPlanJsonParser jsonParser,
            AgentPlanValidator validator,
            GoalCoverageValidator coverageValidator,
            AgentPlanClosureNormalizer closureNormalizer,
            DeterministicHealthAgentPlanFactory deterministicPlanFactory,
            AgentTransientFailureClassifier transientFailureClassifier
    ) {
        this.planningClient = Objects.requireNonNull(planningClient, "planningClient must not be null");
        this.jsonParser = Objects.requireNonNull(jsonParser, "jsonParser must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.coverageValidator = Objects.requireNonNull(
                coverageValidator, "coverageValidator must not be null");
        this.closureNormalizer = Objects.requireNonNull(
                closureNormalizer, "closureNormalizer must not be null");
        this.deterministicPlanFactory = Objects.requireNonNull(
                deterministicPlanFactory, "deterministicPlanFactory must not be null");
        this.transientFailureClassifier = Objects.requireNonNull(
                transientFailureClassifier, "transientFailureClassifier must not be null");
        this.requirementExtractor = new GoalRequirementExtractor();
    }

    @Override
    public AgentPlan plan(String goal) {
        return plan(goal, AgentRunMetrics.unobserved());
    }

    @Override
    public AgentPlan plan(String goal, AgentRunMetrics metrics) {
        if (goal == null || goal.isBlank()) {
            throw new AgentPlanningException("Agent goal must not be blank");
        }
        Objects.requireNonNull(metrics, "metrics must not be null");
        String requestedGoal = goal;
        LOGGER.info("Agent 规划开始");

        String instructions = INITIAL_INSTRUCTIONS;
        for (int attempt = 0; attempt <= MAX_REPAIR_ATTEMPTS; attempt++) {
            String rawPlan;
            try {
                metrics.recordPlannerInputChars(requestedGoal.length(), instructions.length());
                rawPlan = planningClient.generatePlan(
                        requestedGoal,
                        instructions,
                        metrics.withLlmPhase(AgentRunMetrics.LlmPhase.PLANNING));
            } catch (RuntimeException exception) {
                AgentFallbackReason reason = transientFailureClassifier.classify(exception)
                        .orElse(null);
                if (reason != null) {
                    AgentPlan fallback = deterministicPlan(requestedGoal, metrics, reason);
                    if (fallback != null) {
                        return fallback;
                    }
                }
                LOGGER.error("Agent 规划失败：deterministicFallback=false");
                throw new AgentPlanningException("Agent planning client failed", exception);
            }

            AttemptResult result = parseAndValidate(rawPlan, requestedGoal);
            if (result.plan() != null) {
                LOGGER.info("Agent 规划完成：steps={}", result.plan().steps().size());
                return result.plan();
            }
            AgentPlan normalized = normalizeClosure(result, requestedGoal, metrics);
            if (normalized != null) {
                return normalized;
            }
            List<AgentPlanErrorCode> errorCodes = result.issues().stream()
                    .map(AgentPlanValidationIssue::code)
                    .toList();
            LOGGER.warn(
                    "Agent 计划无效：attempt={}, errorCount={}, errors={}",
                    attempt + 1,
                    result.issues().size(),
                    errorCodes
            );
            if (attempt < MAX_REPAIR_ATTEMPTS) {
                LOGGER.warn("Agent 计划第一次校验失败，尝试修复");
                instructions = repairInstructions(result.issues(), requestedGoal);
                continue;
            }

            AgentPlan fallback = deterministicPlan(
                    requestedGoal, metrics, AgentFallbackReason.INVALID_PLAN_AFTER_REPAIR);
            if (fallback != null) {
                return fallback;
            }
            LOGGER.error("Agent 规划失败：deterministicFallback=false");
            throw new AgentPlanningException("Agent plan is invalid after one repair attempt: "
                    + summarizeIssues(result.issues()));
        }
        throw new AgentPlanningException("Agent planning failed");
    }

    private AttemptResult parseAndValidate(String rawPlan, String requestedGoal) {
        AgentPlan parsedPlan;
        try {
            parsedPlan = jsonParser.parse(rawPlan);
        } catch (AgentPlanParseException exception) {
            return AttemptResult.invalid(null, List.of(issue(
                    AgentPlanValidationSource.JSON_PARSER,
                    exception.getMessage())));
        }

        AgentPlan canonicalPlan = new AgentPlan(requestedGoal, parsedPlan.steps());
        return validateCandidate(canonicalPlan, requestedGoal);
    }

    private AttemptResult validateCandidate(AgentPlan candidate, String requestedGoal) {
        List<AgentPlanValidationIssue> issues = new ArrayList<>();
        issues.addAll(issues(
                AgentPlanValidationSource.PLAN_VALIDATOR,
                validator.validate(candidate).errors()));
        issues.addAll(issues(
                AgentPlanValidationSource.GOAL_COVERAGE_VALIDATOR,
                coverageValidator.validate(requestedGoal, candidate).errors()));
        return issues.isEmpty()
                ? AttemptResult.valid(candidate)
                : AttemptResult.invalid(candidate, issues);
    }

    private AgentPlan normalizeClosure(
            AttemptResult result,
            String requestedGoal,
            AgentRunMetrics metrics
    ) {
        return closureNormalizer.normalize(result.candidatePlan(), result.issues())
                .map(candidate -> validateCandidate(candidate, requestedGoal))
                .filter(normalized -> normalized.plan() != null)
                .map(normalized -> {
                    metrics.recordPlannerClosureNormalized();
                    LOGGER.info("Agent planner closure normalized");
                    return normalized.plan();
                })
                .orElse(null);
    }

    private AgentPlan deterministicPlan(
            String requestedGoal,
            AgentRunMetrics metrics,
            AgentFallbackReason reason
    ) {
        AgentPlan candidate = deterministicPlanFactory.create(requestedGoal).orElse(null);
        if (candidate == null) {
            return null;
        }
        AttemptResult validation = validateCandidate(candidate, requestedGoal);
        if (validation.plan() == null) {
            throw new AgentPlanningException(
                    "Deterministic Agent plan failed validation: "
                            + summarizeIssues(validation.issues()));
        }
        metrics.recordDeterministicPlannerFallback(reason);
        LOGGER.warn("Agent planner deterministic fallback: reason={}", reason);
        return validation.plan();
    }

    private List<AgentPlanValidationIssue> issues(
            AgentPlanValidationSource source,
            List<String> errors
    ) {
        return errors.stream().map(error -> issue(source, error)).toList();
    }

    private AgentPlanValidationIssue issue(AgentPlanValidationSource source, String error) {
        return new AgentPlanValidationIssue(source, AgentPlanErrorClassifier.classify(error));
    }

    private String repairInstructions(
            List<AgentPlanValidationIssue> issues,
            String requestedGoal
    ) {
        return INITIAL_INSTRUCTIONS + """

                上一次输出未通过结构解析或计划校验。请依据下面的结构化错误修复计划。
                goal 字段保持非空即可，应用会使用 user 消息中的原始 Goal。只返回修正后的 JSON object。
                REQUIRED_ACTIONS_FOR_THIS_GOAL:
                """ + requiredActionSummary(requestedGoal) + """
                CLOSED_LOOP_REQUIREMENTS:
                EXACTLY_ONE_VALIDATE
                EXACTLY_ONE_FINAL_SYNTHESIZE
                VALIDATE_COVERS_ALL_BUSINESS_BRANCHES
                VALIDATION_ISSUES:
                """ + summarizeIssues(issues);
    }

    private String requiredActionSummary(String requestedGoal) {
        Set<GoalRequirement> requirements = requirementExtractor.extract(requestedGoal);
        LinkedHashSet<AgentAction> requiredActions = new LinkedHashSet<>();
        REQUIRED_ACTION_ORDER.stream()
                .filter(requirements::contains)
                .map(GoalRequirement::requiredAction)
                .filter(Objects::nonNull)
                .forEach(requiredActions::add);
        if (requiredActions.isEmpty()) {
            return "NONE\n";
        }
        return requiredActions.stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
    }

    private String summarizeIssues(List<AgentPlanValidationIssue> issues) {
        String summary = issues.stream()
                .map(AgentPlanValidationIssue::safeLabel)
                .collect(java.util.stream.Collectors.joining("; "));
        return summary.length() <= MAX_REPAIR_ISSUE_CHARS
                ? summary
                : summary.substring(0, MAX_REPAIR_ISSUE_CHARS) + "…";
    }

    private record AttemptResult(
            AgentPlan plan,
            AgentPlan candidatePlan,
            List<AgentPlanValidationIssue> issues
    ) {
        private AttemptResult {
            issues = List.copyOf(issues);
        }

        private static AttemptResult valid(AgentPlan plan) {
            return new AttemptResult(plan, plan, List.of());
        }

        private static AttemptResult invalid(
                AgentPlan candidatePlan,
                List<AgentPlanValidationIssue> issues
        ) {
            return new AttemptResult(null, candidatePlan, issues);
        }
    }
}
