package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.summercamp.project.llm.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunContractTest {
    @Test
    void requestDefensivelyCopiesHistory() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("上一问"));

        AgentRunRequest request = new AgentRunRequest("user", "健康目标", history, false);
        history.clear();

        assertFalse(request.history().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> request.history().add(ChatMessage.user("篡改")));
    }

    @Test
    void resultExposesSnapshotInsteadOfWritableState() {
        AgentPlan plan = new AgentPlan("健康目标", List.of(
                new AgentStep("one", AgentAction.GET_DATETIME, "执行", "原因", List.of())
        ));
        AgentState mutable = new AgentState(plan);
        AgentRunResult result = new AgentRunResult(
                AgentRunResult.Status.FAILED, "安全失败", plan, mutable);

        assertFalse(result.state() instanceof AgentState);
        assertThrows(UnsupportedOperationException.class,
                () -> result.state().statuses().put("one", AgentStepStatus.COMPLETED));
    }
}
