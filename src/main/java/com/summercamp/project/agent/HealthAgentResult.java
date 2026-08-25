package com.summercamp.project.agent;

import java.util.List;
import java.util.Objects;

public record HealthAgentResult(Status status, String reply, List<Media> media) {

    public HealthAgentResult {
        status = Objects.requireNonNull(status, "status");
        reply = reply == null ? "" : reply.strip();
        media = media == null ? List.of() : List.copyOf(media);
    }

    public static HealthAgentResult waiting(String reply) {
        return new HealthAgentResult(Status.WAITING_INPUT, reply, List.of());
    }

    public static HealthAgentResult blocked(String reply) {
        return new HealthAgentResult(Status.BLOCKED, reply, List.of());
    }

    public static HealthAgentResult completed(String reply, List<Media> media) {
        return new HealthAgentResult(Status.COMPLETED, reply, media);
    }

    public enum Status {
        WAITING_INPUT,
        BLOCKED,
        COMPLETED
    }

    public record Media(byte[] data, String fileName, String caption) {

        public Media {
            data = Objects.requireNonNull(data, "data").clone();
            fileName = Objects.requireNonNull(fileName, "fileName");
            caption = caption == null ? "" : caption;
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }
}
