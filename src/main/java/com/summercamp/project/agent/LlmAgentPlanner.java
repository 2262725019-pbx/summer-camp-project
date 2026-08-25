package com.summercamp.project.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private static final int MAX_REPAIR_ERROR_CHARS = 1_200;

    private final AgentPlanningClient planningClient;
    private final AgentPlanJsonParser jsonParser;
    private final AgentPlanValidator validator;
    private final GoalCoverageValidator coverageValidator;

    @Autowired
    public LlmAgentPlanner(AgentPlanningClient planningClient, ObjectMapper objectMapper) {
        this(
                planningClient,
                new AgentPlanJsonParser(objectMapper),
                new AgentPlanValidator(),
                new GoalCoverageValidator()
        );
    }

    LlmAgentPlanner(
            AgentPlanningClient planningClient,
            AgentPlanJsonParser jsonParser,
            AgentPlanValidator validator,
            GoalCoverageValidator coverageValidator
    ) {
        this.planningClient = Objects.requireNonNull(planningClient, "planningClient must not be null");
        this.jsonParser = Objects.requireNonNull(jsonParser, "jsonParser must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.coverageValidator = Objects.requireNonNull(
                coverageValidator, "coverageValidator must not be null");
    }

    @Override
    public AgentPlan plan(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new AgentPlanningException("Agent goal must not be blank");
        }
        String requestedGoal = goal;
        LOGGER.info("Agent 规划开始");

        String instructions = INITIAL_INSTRUCTIONS;
        for (int attempt = 0; attempt <= MAX_REPAIR_ATTEMPTS; attempt++) {
            String rawPlan;
            try {
                rawPlan = planningClient.generatePlan(requestedGoal, instructions);
            } catch (RuntimeException exception) {
                LOGGER.error("Agent 规划失败");
                throw new AgentPlanningException("Agent planning client failed", exception);
            }

            AttemptResult result = parseAndValidate(rawPlan, requestedGoal);
            if (result.plan() != null) {
                LOGGER.info("Agent 规划完成：steps={}", result.plan().steps().size());
                return result.plan();
            }
            List<AgentPlanErrorCode> errorCodes = AgentPlanErrorClassifier.classify(result.errors());
            LOGGER.warn(
                    "Agent 计划无效：attempt={}, errorCount={}, errors={}",
                    attempt + 1,
                    result.errors().size(),
                    errorCodes
            );
            if (attempt < MAX_REPAIR_ATTEMPTS) {
                LOGGER.warn("Agent 计划第一次校验失败，尝试修复");
                instructions = repairInstructions(result.errors());
                continue;
            }

            LOGGER.error("Agent 规划失败");
            throw new AgentPlanningException(
                    "Agent plan is invalid after one repair attempt: " + summarizeErrors(result.errors()));
        }
        throw new AgentPlanningException("Agent planning failed");
    }

    private AttemptResult parseAndValidate(String rawPlan, String requestedGoal) {
        AgentPlan parsedPlan;
        try {
            parsedPlan = jsonParser.parse(rawPlan);
        } catch (AgentPlanParseException exception) {
            return AttemptResult.invalid(List.of(exception.getMessage()));
        }

        List<String> errors = new ArrayList<>(validator.validate(parsedPlan).errors());
        errors.addAll(coverageValidator.validate(requestedGoal, parsedPlan).errors());
        AgentPlan canonicalPlan = new AgentPlan(requestedGoal, parsedPlan.steps());
        return errors.isEmpty() ? AttemptResult.valid(canonicalPlan) : AttemptResult.invalid(errors);
    }

    private String repairInstructions(List<String> errors) {
        return INITIAL_INSTRUCTIONS + """

                上一次输出未通过结构解析或计划校验。请依据下面的结构化错误修复计划。
                goal 字段保持非空即可，应用会使用 user 消息中的原始 Goal。只返回修正后的 JSON object。
                错误：
                """ + summarizeErrors(errors);
    }

    private String summarizeErrors(List<String> errors) {
        String summary = String.join("; ", errors);
        return summary.length() <= MAX_REPAIR_ERROR_CHARS
                ? summary
                : summary.substring(0, MAX_REPAIR_ERROR_CHARS) + "…";
    }

    private record AttemptResult(AgentPlan plan, List<String> errors) {
        private AttemptResult {
            errors = List.copyOf(errors);
        }

        private static AttemptResult valid(AgentPlan plan) {
            return new AttemptResult(plan, List.of());
        }

        private static AttemptResult invalid(List<String> errors) {
            return new AttemptResult(null, errors);
        }
    }
}
