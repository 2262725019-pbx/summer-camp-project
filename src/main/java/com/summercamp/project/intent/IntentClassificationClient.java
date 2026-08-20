package com.summercamp.project.intent;

import java.util.Optional;

public interface IntentClassificationClient {

    Optional<IntentResult> classify(String text);
}
