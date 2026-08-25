package com.summercamp.project.agent.execution;

import com.summercamp.project.agent.model.AgentRun;
import com.summercamp.project.agent.model.AgentStep;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TaskScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskScheduler.class);

    public <T> T execute(AgentRun run, String stepId, Supplier<T> action) {
        AgentStep step = run.plan().requireStep(stepId);
        if (!run.dependenciesSucceeded(step)) {
            throw new IllegalStateException("步骤依赖尚未完成：" + stepId);
        }
        Throwable lastError = null;
        for (int attempt = 1; attempt <= step.maxAttempts(); attempt++) {
            run.start(stepId);
            long started = System.nanoTime();
            try {
                T output = action.get();
                run.succeed(stepId, output);
                LOGGER.info("Agent 步骤成功：runId={} step={} attempt={} elapsedMs={}",
                        run.id(), stepId, attempt, elapsedMillis(started));
                return output;
            } catch (RuntimeException exception) {
                lastError = exception;
                run.fail(stepId, exception);
                LOGGER.warn("Agent 步骤失败：runId={} step={} attempt={} error={}",
                        run.id(), stepId, attempt, exception.getClass().getSimpleName());
            }
        }
        throw new AgentStepExecutionException("Agent 步骤执行失败：" + stepId, lastError);
    }

    public Map<String, StepResult<Object>> executeParallel(
            AgentRun run,
            Map<String, Supplier<?>> actions) {
        Map<String, Future<StepResult<Object>>> futures = new LinkedHashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            actions.forEach((stepId, action) -> futures.put(stepId, executor.submit(() -> {
                try {
                    return StepResult.success(execute(run, stepId, action::get));
                } catch (RuntimeException exception) {
                    return StepResult.failure(exception);
                }
            })));
            Map<String, StepResult<Object>> results = new LinkedHashMap<>();
            futures.forEach((stepId, future) -> results.put(stepId, await(future)));
            return Map.copyOf(results);
        }
    }

    private StepResult<Object> await(Future<StepResult<Object>> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return StepResult.failure(exception);
        } catch (ExecutionException exception) {
            return StepResult.failure(exception.getCause());
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public record StepResult<T>(T value, Throwable error) {

        public static <T> StepResult<T> success(T value) {
            return new StepResult<>(value, null);
        }

        public static <T> StepResult<T> failure(Throwable error) {
            return new StepResult<>(null, error);
        }

        public boolean succeeded() {
            return error == null;
        }
    }
}
