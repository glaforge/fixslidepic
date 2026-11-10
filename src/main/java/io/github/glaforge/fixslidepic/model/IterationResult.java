package io.github.glaforge.fixslidepic.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record IterationResult(
        int runNumber,
        int iteration,
        String imageId,
        String generatedImageUrl,
        String promptUsed,
        JudgeVerdict verdict,
        long durationMs
) {
}
