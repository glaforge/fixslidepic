package io.github.glaforge.fixslidepic.model;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record SlideSessionResult(
        String sessionId,
        String originalFileName,
        String originalImageUrl,
        List<IterationResult> iterations,
        IterationResult finalResult,
        boolean satisfied
) {
}
