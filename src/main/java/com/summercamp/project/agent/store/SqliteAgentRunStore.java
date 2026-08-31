package com.summercamp.project.agent.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.agent.artifact.HealthPlanArtifact;
import com.summercamp.project.agent.evaluation.EvaluationReport;
import com.summercamp.project.agent.model.AgentPlan;
import com.summercamp.project.agent.model.AgentRun;
import com.summercamp.project.agent.model.HealthGoal;
import com.summercamp.project.rag.RagContext;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent.persistence.enabled", havingValue = "true", matchIfMissing = true)
public class SqliteAgentRunStore implements AgentRunStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteAgentRunStore.class);
    private static final Map<String, Class<?>> OUTPUT_TYPES = Map.of(
            "retrieve-health-knowledge", RagContext.class,
            "query-weather", String.class,
            "calculate-nutrition", String.class,
            "generate-exercise-plan", String.class,
            "generate-meal-schedule", String.class,
            "assemble-daily-schedule", HealthPlanArtifact.class,
            "evaluate-plan", EvaluationReport.class);

    private final AgentStateDatabase database;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SqliteAgentRunStore(AgentStateDatabase database, ObjectMapper objectMapper) {
        this(database, objectMapper, Clock.systemUTC());
    }

    SqliteAgentRunStore(AgentStateDatabase database, ObjectMapper objectMapper, Clock clock) {
        this.database = database;
        this.objectMapper = objectMapper.copy();
        this.clock = clock;
    }

    @Override
    public void save(AgentRun run) {
        run.attachChangeListener(() -> persistQuietly(run));
        persist(run);
    }

    @Override
    public Optional<AgentRun> latest(String userId) {
        return database.loadRun(userId, clock.instant()).flatMap(payload -> {
            try {
                StoredRun stored = objectMapper.readValue(payload, StoredRun.class);
                AgentRun run = new AgentRun(
                        stored.id(),
                        stored.userId(),
                        stored.goal(),
                        stored.plan(),
                        Instant.ofEpochMilli(stored.createdAtEpochMillis()),
                        Instant.ofEpochMilli(stored.expiresAtEpochMillis()));
                Map<String, Object> outputs = new LinkedHashMap<>();
                stored.outputs().forEach((stepId, value) -> {
                    Class<?> type = OUTPUT_TYPES.get(stepId);
                    if (type != null) {
                        outputs.put(stepId, objectMapper.convertValue(value, type));
                    }
                });
                run.restore(stored.states(), outputs);
                run.attachChangeListener(() -> persistQuietly(run));
                return Optional.of(run);
            } catch (RuntimeException | JsonProcessingException exception) {
                LOGGER.warn("忽略损坏的 Agent 断点：error={}", exception.getClass().getSimpleName());
                database.deleteRun(userId);
                return Optional.empty();
            }
        });
    }

    @Override
    public void clear(String userId) {
        database.deleteRun(userId);
    }

    private void persistQuietly(AgentRun run) {
        try {
            persist(run);
        } catch (RuntimeException exception) {
            LOGGER.warn("Agent 断点写入失败：runId={} error={}",
                    run.id(), exception.getClass().getSimpleName());
        }
    }

    private void persist(AgentRun run) {
        try {
            Map<String, JsonNode> outputs = new LinkedHashMap<>();
            run.outputs().forEach((stepId, value) -> {
                if (OUTPUT_TYPES.containsKey(stepId)) {
                    outputs.put(stepId, objectMapper.valueToTree(value));
                }
            });
            StoredRun stored = new StoredRun(
                    run.id(),
                    run.userId(),
                    withoutRawSource(run.goal()),
                    run.plan(),
                    run.createdAt().toEpochMilli(),
                    run.expiresAt().toEpochMilli(),
                    run.states(),
                    Map.copyOf(outputs));
            database.saveRun(run.userId(), objectMapper.writeValueAsString(stored), run.expiresAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 Agent 断点", exception);
        }
    }

    private HealthGoal withoutRawSource(HealthGoal goal) {
        return new HealthGoal(
                goal.goalType(), goal.days(), goal.gender(), goal.age(), goal.heightCm(), goal.weightKg(),
                goal.location(), goal.trainingDaysPerWeek(), goal.minutesPerSession(), goal.mealsPerDay(),
                goal.activityLevel(), goal.healthConfirmed(), goal.noFoodAllergies(), goal.safetyFlags(), "");
    }

    private record StoredRun(
            String id,
            String userId,
            HealthGoal goal,
            AgentPlan plan,
            long createdAtEpochMillis,
            long expiresAtEpochMillis,
            Map<String, AgentRun.StepState> states,
            Map<String, JsonNode> outputs) {
    }
}
