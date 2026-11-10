# FixSlidePic

A Micronaut + ADK Java application that transforms skewed, badly lit, or obstructed photographs of conference and university lecture slides into clean, flat 16:9 digital slides as they would appear on a computer screen.

## Architecture

- **Framework**: Micronaut 5 (`io.micronaut.application` Gradle Groovy DSL, Java 25)
- **Agent Orchestration**: Google Agent Development Kit (ADK) for Java (`com.google.adk:google-adk:1.12.0`)
- **Slide Restorer Sub-Agent (`slide_fixer_agent`)**: Uses **NanoBanana 2.1** (`gemini-nano-banana-2.1`) to isolate the slide canvas, rectify 3D perspective skew and screen curvature, remove stage/browser/live-caption clutter, neutralize projector color-wheel banding and LED moiré, and inpaint minor foreground occlusions.
- **LLM-as-a-Judge Sub-Agent (`slide_judge_agent`)**: Uses **Gemini 3.8 Flash** (`gemini-3.8-flash`) with a structured JSON verdict schema (`textFidelity`, `geometryAndCropping`, `themeAndVisualQuality`, `overallScore`, `satisfied`, `critique`, `rewrittenPromptInstructions`).
- **Iterative Refinement (`LoopAgent`)**: Loops up to **3 times**. On each retry, `slide_fixer_agent` starts fresh from the **original camera photograph** combined with the Judge's rewritten prompt instructions (and any user feedback), avoiding multi-generation image degradation. When the Judge sets `satisfied = true`, the agent signals `escalate(true)` to exit the `LoopAgent` early.

## Running the Application

Ensure `GEMINI_API_KEY` (or `GOOGLE_API_KEY`) is exported in your environment, then run:

```bash
./gradlew run
```

Open [http://localhost:8080](http://localhost:8080) in your browser to:
1. Drag-and-drop a slide photo or pick any of the 29 conference/lecture photos from `test-pictures/`.
2. Watch real-time Server-Sent Events (SSE) progress as the ADK `LoopAgent` runs `gemini-nano-banana-2.1` and `gemini-3.8-flash`.
3. Compare the original camera photo and the restored slide using Side-by-Side view or the interactive Before/After split slider.
4. Click **Regenerate Slide (Loop Again)** with optional custom instructions to re-enter the `LoopAgent` for another refinement cycle.

## License

This project is licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.

## Disclaimer

This is not an officially supported Google product. This project is not eligible for the [Google Open Source Software Vulnerability Rewards Program](https://bughunters.google.com/open-source-security).

