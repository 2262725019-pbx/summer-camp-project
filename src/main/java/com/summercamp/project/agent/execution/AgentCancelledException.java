package com.summercamp.project.agent.execution;

public class AgentCancelledException extends RuntimeException {

    public AgentCancelledException() {
        super("Agent task was cancelled");
    }
}
