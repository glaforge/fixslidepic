package io.github.glaforge.fixslidepic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgeVerdict(
        boolean satisfied,
        int overallScore,
        int textFidelity,
        int geometryAndCropping,
        int themeAndVisualQuality,
        String critique,
        String rewrittenPromptInstructions
) {
    public JudgeVerdict {
        critique = critique == null ? "" : critique.trim();
        rewrittenPromptInstructions = rewrittenPromptInstructions == null ? "" : rewrittenPromptInstructions.trim();
    }
}
