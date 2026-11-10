package io.github.glaforge.fixslidepic.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ProgressEvent(
        String type,
        String sessionId,
        int iteration,
        int maxIterations,
        String message,
        IterationResult iterationResult,
        SlideSessionResult sessionResult
) {
}
