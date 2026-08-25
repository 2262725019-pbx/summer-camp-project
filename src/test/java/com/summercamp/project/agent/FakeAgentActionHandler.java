package com.summercamp.project.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

final class FakeAgentActionHandler implements AgentActionHandler {
    private final AgentAction action;
    private final BiFunction<AgentStep, AgentExecutionContext, AgentObservation> execution;
    private final List<String> invocations = new ArrayList<>();

    FakeAgentActionHandler(
            AgentAction action,
            BiFunction<AgentStep, AgentExecutionContext, AgentObservation> execution
    ) {
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.execution = Objects.requireNonNull(execution, "execution must not be null");
    }

    static FakeAgentActionHandler succeeding(AgentAction action, List<String> executionOrder) {
        return new FakeAgentActionHandler(action, (step, context) -> {
            executionOrder.add(step.id());
            return new AgentObservation(step.id(), true, "completed " + step.id());
        });
    }

    @Override
    public AgentAction action() {
        return action;
    }

    @Override
    public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
        invocations.add(step.id());
        return execution.apply(step, context);
    }

    int invocationCount(String stepId) {
        return (int) invocations.stream().filter(stepId::equals).count();
    }

    int totalInvocationCount() {
        return invocations.size();
    }
}
