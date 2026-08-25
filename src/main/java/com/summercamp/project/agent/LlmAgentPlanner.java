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
            3. dependsOn 中只能填写其他步骤的 step id；依赖必须存在、不得自依赖、不得形成环。
            4. 最终必须有且只有一个 SYNTHESIZE；它必须位于业务任务之后，并依赖实际执行结果。
            5. 模型不得指定 status；应用会把每个新步骤初始化为 PENDING。
            6. 规划阶段只做规划，不得声称已经调用工具、Skill、RAG 或完成现实操作。

            本 Agent 仅适用于一般性健康生活、饮食、运动和作息规划。不得规划疾病诊断、药物建议、
            治疗方案或替代医生的行为。若 Goal 明显涉及医疗诊断或治疗，只能生成安全、有限、
            建议寻求合格医疗专业人员帮助的计划，或拒绝继续医疗执行；不得假装拥有医疗能力。

            只能返回一个 JSON object，不得返回 Markdown、代码围栏、解释或其他文本。严格结构：
            {
              "goal": "必须与用户原始 Goal 完全一致",
              "steps": [
                {
                  "id": "S1",
                  "action": "GET_DATETIME",
                  "description": "清晰、具体的业务步骤",
                  "reason": "该步骤与 Goal 的关系",
                  "dependsOn": []
                }
              ]
            }
            root 只能包含 goal、steps；step 只能包含 id、action、description、reason、dependsOn。
            """;

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmAgentPlanner.class);
    private static final int MAX_REPAIR_ERROR_CHARS = 1_200;

    private final AgentPlanningClient planningClient;
    private final AgentPlanJsonParser jsonParser;
    private final AgentPlanValidator validator;

    @Autowired
    public LlmAgentPlanner(AgentPlanningClient planningClient, ObjectMapper objectMapper) {
        this(planningClient, new AgentPlanJsonParser(objectMapper), new AgentPlanValidator());
    }

    LlmAgentPlanner(
            AgentPlanningClient planningClient,
            AgentPlanJsonParser jsonParser,
            AgentPlanValidator validator
    ) {
        this.planningClient = Objects.requireNonNull(planningClient, "planningClient must not be null");
        this.jsonParser = Objects.requireNonNull(jsonParser, "jsonParser must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    @Override
    public AgentPlan plan(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new AgentPlanningException("Agent goal must not be blank");
        }
        String requestedGoal = goal.strip();
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
        AgentPlan plan;
        try {
            plan = jsonParser.parse(rawPlan);
        } catch (AgentPlanParseException exception) {
            return AttemptResult.invalid(List.of(exception.getMessage()));
        }

        List<String> errors = new ArrayList<>(validator.validate(plan).errors());
        if (!requestedGoal.equals(plan.goal())) {
            errors.add("Plan goal must exactly match the requested goal");
        }
        return errors.isEmpty() ? AttemptResult.valid(plan) : AttemptResult.invalid(errors);
    }

    private String repairInstructions(List<String> errors) {
        return INITIAL_INSTRUCTIONS + """

                上一次输出未通过结构解析或计划校验。请依据下面的结构化错误修复计划。
                原始 Goal 仍由 user 消息提供，必须原样保留。只返回修正后的 JSON object。
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
