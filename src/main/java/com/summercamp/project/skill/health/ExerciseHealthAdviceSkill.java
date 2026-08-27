package com.summercamp.project.skill.health;

import com.summercamp.project.agent.AgentFallbackReason;
import com.summercamp.project.agent.AgentTransientFailureClassifier;
import com.summercamp.project.llm.ChatOutcome;
import com.summercamp.project.llm.ChatProviderPolicy;
import com.summercamp.project.llm.ChatRequest;
import com.summercamp.project.llm.ChatModelClient;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillExecutionMode;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.tool.ToolAccessPolicy;
import com.summercamp.project.tool.ToolContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ExerciseHealthAdviceSkill implements BotSkill {

    public static final String SKILL_NAME = "exercise-health-advice";
    private static final String INSTRUCTIONS_RESOURCE = "skills/exercise-health-advice/SKILL.md";
    private static final String END_MARKER = "【会话结束】";
    private static final int MIN_AGENT_FALLBACK_REPLY_CODE_POINTS = 20;
    private static final Logger LOGGER = LoggerFactory.getLogger(ExerciseHealthAdviceSkill.class);
    private static final List<String> TRIGGER_TERMS = List.of(
            "运动建议", "锻炼建议", "健身计划", "运动计划", "跑步计划", "减肥运动", "减脂运动",
            "怎么运动", "适合什么运动", "练什么", "制定运动", "安排锻炼");

    private final ChatModelClient chatClient;
    private final String instructions;
    private final ExerciseSkillInputCompleteness inputCompleteness =
            new ExerciseSkillInputCompleteness();
    private final DeterministicExerciseFallback deterministicFallback =
            new DeterministicExerciseFallback();
    private final AgentTransientFailureClassifier transientFailureClassifier =
            new AgentTransientFailureClassifier();

    public ExerciseHealthAdviceSkill(ChatModelClient chatClient) {
        this.chatClient = chatClient;
        this.instructions = loadInstructions();
    }

    @Override
    public String name() {
        return SKILL_NAME;
    }

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public int matchScore(String text) {
        String normalized = normalize(text);
        int longest = TRIGGER_TERMS.stream()
                .filter(normalized::contains)
                .mapToInt(String::length)
                .max()
                .orElse(0);
        return longest == 0 ? 0 : 60 + longest;
    }

    @Override
    public SkillResult execute(SkillContext context) {
        String groundingContext = context.trustedContext().weatherObservation()
                .map(observation -> instructions + "\n\n" + observation.systemGroundingContext())
                .orElse(instructions);
        boolean agentExecution = context.executionMode() == SkillExecutionMode.AGENT;
        boolean hasTrustedWeather = context.trustedContext().weatherObservation().isPresent();
        ToolAccessPolicy toolAccessPolicy;
        if (agentExecution) {
            toolAccessPolicy = ToolAccessPolicy.allowOnly(
                    hasTrustedWeather ? Set.of() : Set.of("get_weather"));
        } else {
            toolAccessPolicy = hasTrustedWeather
                    ? ToolAccessPolicy.allExcept(Set.of("get_weather"))
                    : ToolAccessPolicy.unrestricted();
        }
        ChatOutcome outcome;
        try {
            outcome = chatClient.chat(
                    new ChatRequest(
                            context.history(),
                            context.text(),
                            List.of(),
                            groundingContext,
                            toolAccessPolicy,
                            agentExecution
                                    ? ChatProviderPolicy.AGENT_EXERCISE_SKILL_BOUNDED
                                    : ChatProviderPolicy.STANDARD),
                    new ToolContext(
                            context.userId(), context.text(), context.history(), context.metrics()));
        } catch (RuntimeException failure) {
            AgentFallbackReason reason = agentExecution
                    ? transientFailureClassifier.classify(failure).orElse(null)
                    : null;
            if (reason == null) {
                throw failure;
            }
            if (!inputCompleteness.isComplete(context.text())) {
                return SkillResult.waitingInput(
                        "请补充所在地、运动目标、每周训练次数、每次训练时长和健康确认后继续。");
            }
            context.metrics().recordDeterministicExerciseFallback(reason);
            LOGGER.warn("Agent exercise deterministic fallback: reason={}", reason);
            return SkillResult.completed(deterministicFallback.render(context));
        }
        String reply = outcome.text().strip();
        if (reply.isBlank()) {
            return SkillResult.waitingInput("暂时没有生成运动建议，请补充你的运动目标和每周可训练时间。");
        }
        boolean explicitCompletion = reply.contains(END_MARKER);
        reply = reply.replace(END_MARKER, "").strip();
        boolean agentFallbackCompletion = context.executionMode() == SkillExecutionMode.AGENT
                && inputCompleteness.isComplete(context.text())
                && isSubstantive(reply);
        return explicitCompletion || agentFallbackCompletion
                ? SkillResult.completed(reply)
                : SkillResult.waitingInput(reply);
    }

    private boolean isSubstantive(String reply) {
        return reply.codePointCount(0, reply.length()) >= MIN_AGENT_FALLBACK_REPLY_CODE_POINTS;
    }

    private String loadInstructions() {
        ClassPathResource resource = new ClassPathResource(INSTRUCTIONS_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            byte[] content = input.readAllBytes();
            return content.length == 0 ? "" : new String(content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取运动健康 Skill 说明：" + INSTRUCTIONS_RESOURCE, exception);
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
