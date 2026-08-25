package com.summercamp.project.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;

/** Classifies Agent-only provider failures without inspecting sensitive exception messages. */
final class AgentProviderFailureClassifier {

    private AgentProviderFailureClassifier() {
    }

    static Failure classify(Throwable failure) {
        if (hasCause(failure, InterruptedException.class)) {
            return new Failure(Category.INTERRUPTED, false);
        }
        if (hasCause(failure, HttpTimeoutException.class)) {
            return new Failure(Category.TIMEOUT, true);
        }
        ZhipuAiClient.ZhipuHttpException httpFailure = findCause(
                failure, ZhipuAiClient.ZhipuHttpException.class);
        if (httpFailure != null) {
            if (httpFailure.statusCode() == 429) {
                return new Failure(Category.RATE_LIMIT, true);
            }
            if (httpFailure.statusCode() >= 500) {
                return new Failure(Category.SERVER_ERROR, true);
            }
            return new Failure(Category.NON_RETRYABLE, false);
        }
        if (hasCause(failure, ZhipuAiClient.AgentRequestSerializationException.class)) {
            return new Failure(Category.NON_RETRYABLE, false);
        }
        if (hasCause(failure, ZhipuAiClient.InvalidProviderResponseException.class)
                || hasCause(failure, JsonProcessingException.class)) {
            return new Failure(Category.INVALID_PROVIDER_RESPONSE, false);
        }
        if (hasCause(failure, IOException.class)) {
            return new Failure(Category.CONNECTIVITY, true);
        }
        if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException) {
            return new Failure(Category.NON_RETRYABLE, false);
        }
        return new Failure(Category.UNKNOWN_PROVIDER_FAILURE, false);
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        return findCause(failure, type) != null;
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    enum Category {
        TIMEOUT,
        CONNECTIVITY,
        RATE_LIMIT,
        SERVER_ERROR,
        INVALID_PROVIDER_RESPONSE,
        INTERRUPTED,
        NON_RETRYABLE,
        UNKNOWN_PROVIDER_FAILURE
    }

    record Failure(Category category, boolean fallbackEligible) {

        String code(String operation) {
            return operation + "_" + category.name();
        }
    }
}
