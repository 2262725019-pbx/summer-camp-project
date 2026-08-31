package com.summercamp.project.agent.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.agent.artifact.HealthPlanArtifact;
import com.summercamp.project.agent.model.HealthGoal;
import com.summercamp.project.config.HealthReminderProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CompletedHealthPlanStore {

    private final ConcurrentHashMap<String, CompletedHealthPlan> plans = new ConcurrentHashMap<>();
    private final HealthReminderProperties properties;
    private final Clock clock;
    private final AgentStateDatabase database;
    private final ObjectMapper objectMapper;

    @Autowired
    public CompletedHealthPlanStore(
            HealthReminderProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<AgentStateDatabase> databaseProvider) {
        this(properties, Clock.systemUTC(), databaseProvider.getIfAvailable(), objectMapper);
    }

    public CompletedHealthPlanStore(HealthReminderProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public CompletedHealthPlanStore(HealthReminderProperties properties, Clock clock) {
        this(properties, clock, null, new ObjectMapper());
    }

    CompletedHealthPlanStore(
            HealthReminderProperties properties,
            Clock clock,
            AgentStateDatabase database,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.clock = clock;
        this.database = database;
        this.objectMapper = objectMapper.copy();
    }

    public void save(String userId, HealthGoal goal, HealthPlanArtifact artifact) {
        properties.validate();
        Instant now = clock.instant();
        CompletedHealthPlan plan = new CompletedHealthPlan(
                withoutRawSource(goal), artifact, now, now.plus(properties.planTtl()));
        plans.put(userId, plan);
        if (database != null) {
            try {
                StoredCompletedPlan stored = new StoredCompletedPlan(
                        plan.goal(), plan.artifact(), plan.createdAt().toEpochMilli(), plan.expiresAt().toEpochMilli());
                database.saveCompletedPlan(
                        userId, objectMapper.writeValueAsString(stored), plan.expiresAt());
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("无法保存完成的健康计划", exception);
            }
        }
    }

    public Optional<CompletedHealthPlan> latest(String userId) {
        CompletedHealthPlan plan = plans.get(userId);
        if (plan == null && database != null) {
            plan = database.loadCompletedPlan(userId, clock.instant())
                    .map(this::deserialize)
                    .orElse(null);
            if (plan != null) {
                plans.put(userId, plan);
            }
        }
        if (plan == null) {
            return Optional.empty();
        }
        if (!plan.expiresAt().isAfter(clock.instant())) {
            plans.remove(userId, plan);
            if (database != null) {
                database.deleteCompletedPlan(userId);
            }
            return Optional.empty();
        }
        return Optional.of(plan);
    }

    public void clear(String userId) {
        plans.remove(userId);
        if (database != null) {
            database.deleteCompletedPlan(userId);
        }
    }

    private CompletedHealthPlan deserialize(String payload) {
        try {
            StoredCompletedPlan stored = objectMapper.readValue(payload, StoredCompletedPlan.class);
            return new CompletedHealthPlan(
                    stored.goal(),
                    stored.artifact(),
                    Instant.ofEpochMilli(stored.createdAtEpochMillis()),
                    Instant.ofEpochMilli(stored.expiresAtEpochMillis()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取完成的健康计划", exception);
        }
    }

    private HealthGoal withoutRawSource(HealthGoal goal) {
        return new HealthGoal(
                goal.goalType(), goal.days(), goal.gender(), goal.age(), goal.heightCm(), goal.weightKg(),
                goal.location(), goal.trainingDaysPerWeek(), goal.minutesPerSession(), goal.mealsPerDay(),
                goal.activityLevel(), goal.healthConfirmed(), goal.noFoodAllergies(), goal.safetyFlags(), "");
    }

    public record CompletedHealthPlan(
            HealthGoal goal,
            HealthPlanArtifact artifact,
            Instant createdAt,
            Instant expiresAt) {
    }

    private record StoredCompletedPlan(
            HealthGoal goal,
            HealthPlanArtifact artifact,
            long createdAtEpochMillis,
            long expiresAtEpochMillis) {
    }
}
