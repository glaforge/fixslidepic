package io.github.glaforge.fixslidepic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Gemini;
import com.google.adk.plugins.LoggingPlugin;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.ImageConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.github.glaforge.fixslidepic.model.IterationResult;
import io.github.glaforge.fixslidepic.model.JudgeVerdict;
import io.github.glaforge.fixslidepic.model.ProgressEvent;
import io.github.glaforge.fixslidepic.model.SlideSessionResult;
import io.micronaut.context.annotation.Value;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Singleton
public class SlideFixerService {

    private static final Logger LOG = LoggerFactory.getLogger(SlideFixerService.class);

    private static final String APP_NAME = "fix_slide_pic_app";
    private static final String USER_ID = "slide_user";

    private static final String STATE_JUDGE_FEEDBACK = "judge_feedback";
    private static final String STATE_USER_FEEDBACK = "user_feedback";
    private static final String STATE_LATEST_IMAGE_ID = "latest_image_id";

    public static final String MASTER_SLIDE_RESTORATION_PROMPT = """
            Task: Extract and restore the presentation slide from this camera photo into a clean, flat, high-resolution 16:9 digital slide as it would appear directly on a computer screen.

            Follow these 5 restoration rules strictly:
            1. ISOLATE ONLY THE SLIDE CANVAS: Identify the exact boundaries of the presentation slide itself. Crop out and exclude everything outside the slide canvas: auditorium walls, stage, speakers, podiums, audience heads, OS menu bars, browser tabs/URL bars, conference video-wall split-screen feeds (such as live camera feeds of the speaker), and any live speech-to-text caption/subtitle bars projected above or below the slide.
            2. GEOMETRIC RECTIFICATION: Correct all 3D perspective distortion, keystone skew, tilt, and curved-screen warping so the slide is perfectly rectangular, flat, and fills the entire 16:9 frame edge-to-edge.
            3. COLOR & SURFACE PURIFICATION: Remove camera noise, DLP projector rainbow/color-wheel banding (horizontal pink, magenta, yellow, or green stripes across the screen), LED pixel-grid moire patterns, screen glare, and uneven lighting. Neutralize projector color bands completely so the slide background and table cells have uniform, flat digital colors. Preserve the slide's authentic background theme and color palette (keep dark/black slides dark, light slides light, and colored/tinted slides faithful to the original)—NEVER force a white background onto a dark-themed slide.
            4. INPAINT FOREGROUND OCCLUSIONS: If a speaker, lectern, microphone, plant/palm tree, stage light, or silhouette overlaps part of the slide canvas, cleanly remove the obstruction and seamlessly reconstruct the occluded slide background, shapes, and text from context.
            5. VERBATIM TEXT & DIAGRAM FIDELITY: Reproduce every word, letter, number, accent, URL, QR code, box, arrow, logo, and illustration with 100% accuracy. Do not invent, paraphrase, summarize, translate, or alter any text.
            """;

    public static final String JUDGE_SYSTEM_INSTRUCTION = """
            You are an expert LLM-as-a-Judge evaluating an AI presentation slide restorer.
            You are given two images:
            - IMAGE 1: The ORIGINAL camera photograph taken at a conference or university lecture.
            - IMAGE 2: The GENERATED restored slide produced by the image restoration model.

            Evaluate the generated slide across three criteria (1-10 each):
            1. textFidelity (1-10): Every visible title, bullet point, diagram label, number, and URL inside the slide canvas is preserved accurately without typos, gibberish, or hallucinated words.
            2. geometryAndCropping (1-10): Perspective skew and screen curvature are rectified so the slide fills the frame edge-to-edge. All non-slide surroundings (room, stage, audience, podium, speaker, browser tabs/URL bars, split-screen speaker camera feeds, and live speech-to-text subtitle/caption bars above or below the slide) are completely removed.
            3. themeAndVisualQuality (1-10): The slide's original background theme is preserved (dark slides remain dark, light slides remain light, colored backgrounds remain faithful). Projector rainbow banding, LED moire, glare, and foreground obstructions overlapping the slide (palm trees, speakers, lecterns, spotlights) are cleanly removed and inpainted.

            Calibration Rubric:
            - Set `satisfied = true` (and `overallScore` 8-10) if the slide is clean, flat, readable, preserves the original theme and text faithfully, and has no stage/browser/caption clutter or unremoved obstructions. Minor differences in font weight, icon rendering, or container padding are normal and SHOULD STILL PASS (`satisfied = true`).
            - Set `satisfied = false` (and `overallScore` <= 7) ONLY if there is a concrete defect that a retry can fix: inverted background theme (e.g. dark slide made white), uncropped live-caption/subtitle bar or browser chrome, remaining stage/speaker obstruction, remaining perspective skew, or corrupted/missing slide text.
            - When `satisfied = false`, provide `rewrittenPromptInstructions`: specific, direct corrective instructions to append to the restoration prompt on the next pass (starting fresh from the original photo) to fix the exact defect(s).

            Return ONLY valid JSON matching the schema.
            """;

    private static final Schema JUDGE_SCHEMA = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(Map.of(
                    "satisfied", Schema.builder().type(Type.Known.BOOLEAN).description("True if overallScore >= 8 and the slide restoration is faithful and clean").build(),
                    "overallScore", Schema.builder().type(Type.Known.INTEGER).description("Overall score from 1 to 10").build(),
                    "textFidelity", Schema.builder().type(Type.Known.INTEGER).description("Text accuracy score from 1 to 10").build(),
                    "geometryAndCropping", Schema.builder().type(Type.Known.INTEGER).description("Perspective rectification and non-slide cropping score from 1 to 10").build(),
                    "themeAndVisualQuality", Schema.builder().type(Type.Known.INTEGER).description("Theme preservation and visual cleanup score from 1 to 10").build(),
                    "critique", Schema.builder().type(Type.Known.STRING).description("Concise explanation of the evaluation and any defects found").build(),
                    "rewrittenPromptInstructions", Schema.builder().type(Type.Known.STRING).description("Specific corrective instructions to append to the base prompt on the next retry if satisfied is false").build()
            ))
            .required(List.of(
                    "satisfied",
                    "overallScore",
                    "textFidelity",
                    "geometryAndCropping",
                    "themeAndVisualQuality",
                    "critique",
                    "rewrittenPromptInstructions"
            ))
            .build();

    @Value("${slide-fixer.image-model:gemini-nano-banana-2.1}")
    String imageModel;

    @Value("${slide-fixer.judge-model:gemini-3.8-flash}")
    String judgeModel;

    @Value("${slide-fixer.max-iterations:3}")
    int maxIterations;

    @Value("${slide-fixer.test-pictures-dir:test-pictures}")
    String testPicturesDirPath;

    @Value("${slide-fixer.output-dir:build/generated-slides}")
    String outputDirPath;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path outputDir;
    private Path testPicturesDir;

    public record StoredImage(byte[] bytes, String mimeType, Path filePath) {}

    public static class SessionStateRecord {
        public final String sessionId;
        public final String originalFileName;
        public final String originalImageId;
        public final byte[] originalBytes;
        public final String originalMimeType;
        public final List<IterationResult> iterations = Collections.synchronizedList(new ArrayList<>());
        public int runCount = 0;
        public String latestJudgeFeedback = "";

        public SessionStateRecord(String sessionId, String originalFileName, String originalImageId, byte[] originalBytes, String originalMimeType) {
            this.sessionId = sessionId;
            this.originalFileName = originalFileName;
            this.originalImageId = originalImageId;
            this.originalBytes = originalBytes;
            this.originalMimeType = originalMimeType;
        }
    }

    private final Map<String, StoredImage> imageStore = new ConcurrentHashMap<>();
    private final Map<String, SessionStateRecord> sessions = new ConcurrentHashMap<>();

    public SlideFixerService() {
    }

    @PostConstruct
    void init() throws IOException {
        this.outputDir = Path.of(outputDirPath).toAbsolutePath().normalize();
        this.testPicturesDir = Path.of(testPicturesDirPath).toAbsolutePath().normalize();
        Files.createDirectories(this.outputDir);
        LOG.info("SlideFixerService initialized with imageModel={}, judgeModel={}, maxIterations={}, outputDir={}",
                imageModel, judgeModel, maxIterations, outputDir);
    }

    public List<String> listTestPictures() {
        if (!Files.isDirectory(testPicturesDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(testPicturesDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> {
                        String lower = name.toLowerCase();
                        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
                    })
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            LOG.error("Failed to list test pictures", e);
            return List.of();
        }
    }

    public Optional<StoredImage> getTestPicture(String filename) {
        Path candidate = testPicturesDir.resolve(filename).normalize();
        if (!candidate.startsWith(testPicturesDir) || !Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(candidate);
            String mimeType = guessMimeType(filename);
            return Optional.of(new StoredImage(bytes, mimeType, candidate));
        } catch (IOException e) {
            LOG.error("Failed to read test picture: {}", filename, e);
            return Optional.empty();
        }
    }

    public Optional<StoredImage> getStoredImage(String imageId) {
        if (imageId == null || imageId.isBlank()) {
            return Optional.empty();
        }
        String cleanId = imageId.trim();
        StoredImage cached = imageStore.get(cleanId);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (outputDir != null && Files.isDirectory(outputDir)) {
            for (String ext : List.of(".png", ".jpg", ".jpeg", ".webp")) {
                Path candidate = outputDir.resolve(cleanId + ext).normalize();
                if (candidate.startsWith(outputDir) && Files.isRegularFile(candidate)) {
                    try {
                        byte[] bytes = Files.readAllBytes(candidate);
                        String mime = guessMimeType(candidate.getFileName().toString());
                        StoredImage loaded = new StoredImage(bytes, mime, candidate);
                        imageStore.put(cleanId, loaded);
                        return Optional.of(loaded);
                    } catch (IOException e) {
                        LOG.warn("Failed to reload image {} from disk", cleanId, e);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private SessionStateRecord resolveSessionRecord(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String cleanId = sessionId.trim();
        SessionStateRecord existing = sessions.get(cleanId);
        if (existing != null) {
            return existing;
        }
        String origImageId = "orig_" + cleanId;
        Optional<StoredImage> diskOrig = getStoredImage(origImageId);
        if (diskOrig.isPresent()) {
            StoredImage orig = diskOrig.get();
            String fileName = orig.filePath() != null ? orig.filePath().getFileName().toString() : (origImageId + ".jpg");
            SessionStateRecord recovered = new SessionStateRecord(cleanId, fileName, origImageId, orig.bytes(), orig.mimeType());
            if (outputDir != null && Files.isDirectory(outputDir)) {
                String slidePrefix = "slide_" + cleanId + "_r";
                try (Stream<Path> stream = Files.list(outputDir)) {
                    stream.filter(Files::isRegularFile).forEach(p -> {
                        String name = p.getFileName().toString();
                        if (name.startsWith(slidePrefix)) {
                            int dot = name.lastIndexOf('.');
                            String imgId = dot > 0 ? name.substring(0, dot) : name;
                            getStoredImage(imgId);
                            int rIdx = name.indexOf("_r") + 2;
                            int iIdx = name.indexOf("_i", rIdx);
                            if (rIdx >= 2 && iIdx > rIdx) {
                                try {
                                    int rNum = Integer.parseInt(name.substring(rIdx, iIdx));
                                    if (rNum > recovered.runCount) {
                                        recovered.runCount = rNum;
                                    }
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    });
                } catch (IOException e) {
                    LOG.debug("Could not scan existing iterations for recovered session {}", cleanId, e);
                }
            }
            sessions.put(cleanId, recovered);
            LOG.info("Recovered session {} from disk ({})", cleanId, orig.filePath());
            return recovered;
        }
        return null;
    }

    public void storeImageForTest(String imageId, byte[] bytes, String mimeType) {
        Path p = saveImageToDisk(imageId, bytes, mimeType);
        imageStore.put(imageId, new StoredImage(bytes, mimeType, p));
    }

    public byte[] createPdfFromImages(List<String> imageIds) throws IOException {
        if (imageIds == null || imageIds.isEmpty()) {
            throw new IllegalArgumentException("At least one imageId is required to generate a PDF.");
        }
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            int addedPages = 0;
            for (String imageId : imageIds) {
                if (imageId == null || imageId.isBlank()) {
                    continue;
                }
                StoredImage stored = getStoredImage(imageId.trim()).orElse(null);
                if (stored == null) {
                    LOG.warn("Skipping unknown imageId when building PDF: {}", imageId);
                    continue;
                }
                PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, stored.bytes(), imageId.trim());
                float pageWidth = 960f;
                float aspect = pdImage.getWidth() > 0
                        ? (float) pdImage.getHeight() / (float) pdImage.getWidth()
                        : (9f / 16f);
                float pageHeight = pageWidth * aspect;

                PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
                document.addPage(page);

                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.drawImage(pdImage, 0, 0, pageWidth, pageHeight);
                }
                addedPages++;
            }

            if (addedPages == 0) {
                throw new IllegalArgumentException("None of the provided imageIds were found.");
            }

            document.save(out);
            byte[] pdfBytes = out.toByteArray();
            Path pdfPath = outputDir.resolve("slides_" + System.currentTimeMillis() + ".pdf");
            Files.write(pdfPath, pdfBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return pdfBytes;
        }
    }

    public Optional<SlideSessionResult> getSessionResult(String sessionId) {
        SessionStateRecord record = resolveSessionRecord(sessionId);
        if (record == null) {
            return Optional.empty();
        }
        return Optional.of(toSessionResult(record));
    }

    public SlideSessionResult startNewSession(
            String fileName,
            byte[] imageBytes,
            String mimeType,
            String customInstructions,
            Consumer<ProgressEvent> progressListener) {

        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String originalImageId = "orig_" + sessionId;
        Path origPath = saveImageToDisk(originalImageId, imageBytes, mimeType);
        imageStore.put(originalImageId, new StoredImage(imageBytes, mimeType, origPath));

        SessionStateRecord record = new SessionStateRecord(sessionId, fileName, originalImageId, imageBytes, mimeType);
        sessions.put(sessionId, record);

        return executeLoopRun(record, customInstructions, progressListener);
    }

    public SlideSessionResult regenerateSession(
            String sessionId,
            String userInstructions,
            Consumer<ProgressEvent> progressListener) {

        SessionStateRecord record = resolveSessionRecord(sessionId);
        if (record == null) {
            throw new IllegalArgumentException("Unknown sessionId: " + sessionId);
        }
        return executeLoopRun(record, userInstructions, progressListener);
    }

    private SlideSessionResult executeLoopRun(
            SessionStateRecord record,
            String userInstructions,
            Consumer<ProgressEvent> progressListener) {

        int currentRunNumber = ++record.runCount;
        int[] currentIterationHolder = new int[]{0};
        long[] iterationStartMsHolder = new long[]{System.currentTimeMillis()};
        String[] lastPromptUsedHolder = new String[]{MASTER_SLIDE_RESTORATION_PROMPT};

        emit(progressListener, new ProgressEvent(
                "SESSION_STARTED",
                record.sessionId,
                0,
                maxIterations,
                "Starting ADK LoopAgent (up to " + maxIterations + " iterations) for " + record.originalFileName,
                null,
                toSessionResult(record)
        ));

        // 1. Sub-agent 1: NanoBanana 2.1 Slide Fixer LlmAgent
        LlmAgent slideFixerAgent = LlmAgent.builder()
                .name("slide_fixer_agent")
                .model(createGeminiModel(imageModel))
                .instruction(MASTER_SLIDE_RESTORATION_PROMPT)
                .generateContentConfig(GenerateContentConfig.builder()
                        .responseModalities("IMAGE")
                        .imageConfig(ImageConfig.builder()
                                .aspectRatio("16:9")
                                .build())
                        .build())
                .outputKey(STATE_LATEST_IMAGE_ID)
                .beforeModelCallbackSync((callbackContext, llmRequestBuilder) -> {
                    int iter = ++currentIterationHolder[0];
                    iterationStartMsHolder[0] = System.currentTimeMillis();

                    String judgeFeedback = (String) callbackContext.state().get(STATE_JUDGE_FEEDBACK);
                    String userFeedback = (String) callbackContext.state().get(STATE_USER_FEEDBACK);

                    StringBuilder promptBuilder = new StringBuilder(MASTER_SLIDE_RESTORATION_PROMPT);
                    if (userFeedback != null && !userFeedback.isBlank()) {
                        promptBuilder.append("\n\nUSER REFINEMENT INSTRUCTIONS (HIGH PRIORITY):\n")
                                .append(userFeedback.trim());
                    }
                    if (judgeFeedback != null && !judgeFeedback.isBlank()) {
                        promptBuilder.append("\n\nCORRECTIVE INSTRUCTIONS FROM PREVIOUS JUDGE AUDIT (MUST FIX):\n")
                                .append(judgeFeedback.trim());
                    }

                    String effectivePrompt = promptBuilder.toString();
                    lastPromptUsedHolder[0] = effectivePrompt;

                    emit(progressListener, new ProgressEvent(
                            "FIXER_STARTED",
                            record.sessionId,
                            iter,
                            maxIterations,
                            "Iteration " + iter + "/" + maxIterations + ": Generating clean slide with " + imageModel + "...",
                            null,
                            null
                    ));

                    // Always start fresh from the original photo + updated prompt (no previous generated image)
                    Content freshContent = Content.fromParts(
                            Part.fromBytes(record.originalBytes, record.originalMimeType),
                            Part.fromText(effectivePrompt)
                    );
                    llmRequestBuilder.contents(List.of(freshContent));
                    return Optional.empty();
                })
                .afterModelCallbackSync((callbackContext, llmResponse) -> {
                    int iter = currentIterationHolder[0];
                    Optional<Blob> inlineBlobOpt = llmResponse.content()
                            .flatMap(Content::parts)
                            .stream()
                            .flatMap(List::stream)
                            .map(Part::inlineData)
                            .flatMap(Optional::stream)
                            .filter(blob -> blob.data().isPresent())
                            .findFirst();

                    if (inlineBlobOpt.isEmpty()) {
                        LOG.warn("No inline image returned by {} on iteration {}", imageModel, iter);
                        return Optional.empty();
                    }

                    Blob blob = inlineBlobOpt.get();
                    byte[] generatedBytes = blob.data().get();
                    String genMimeType = blob.mimeType().orElse("image/png");
                    String imageId = "slide_" + record.sessionId + "_r" + currentRunNumber + "_i" + iter;

                    Path savedPath = saveImageToDisk(imageId, generatedBytes, genMimeType);
                    imageStore.put(imageId, new StoredImage(generatedBytes, genMimeType, savedPath));

                    Part imagePart = Part.fromBytes(generatedBytes, genMimeType);
                    callbackContext.saveArtifact(imageId, imagePart).blockingAwait();
                    callbackContext.state().put(STATE_LATEST_IMAGE_ID, imageId);

                    long elapsed = System.currentTimeMillis() - iterationStartMsHolder[0];
                    IterationResult partialResult = new IterationResult(
                            currentRunNumber,
                            iter,
                            imageId,
                            "/api/slides/images/" + imageId,
                            lastPromptUsedHolder[0],
                            null,
                            elapsed
                    );

                    emit(progressListener, new ProgressEvent(
                            "FIXER_COMPLETED",
                            record.sessionId,
                            iter,
                            maxIterations,
                            "Iteration " + iter + "/" + maxIterations + ": Slide generated (" + (elapsed / 1000.0) + "s). Running LLM-as-Judge (" + judgeModel + ")...",
                            partialResult,
                            null
                    ));

                    return Optional.of(llmResponse.toBuilder()
                            .content(Content.fromParts(Part.fromText(imageId)))
                            .build());
                })
                .build();

        // 2. Sub-agent 2: Gemini 3.8 Flash LLM-as-Judge LlmAgent
        LlmAgent slideJudgeAgent = LlmAgent.builder()
                .name("slide_judge_agent")
                .model(createGeminiModel(judgeModel))
                .instruction(JUDGE_SYSTEM_INSTRUCTION)
                .generateContentConfig(GenerateContentConfig.builder()
                        .responseMimeType("application/json")
                        .responseSchema(JUDGE_SCHEMA)
                        .build())
                .outputKey("judge_verdict_json")
                .beforeModelCallbackSync((callbackContext, llmRequestBuilder) -> {
                    int iter = currentIterationHolder[0];
                    String latestImageId = (String) callbackContext.state().get(STATE_LATEST_IMAGE_ID);
                    StoredImage latestImage = latestImageId != null ? imageStore.get(latestImageId) : null;

                    emit(progressListener, new ProgressEvent(
                            "JUDGE_STARTED",
                            record.sessionId,
                            iter,
                            maxIterations,
                            "Iteration " + iter + "/" + maxIterations + ": Judging fidelity against original slide with " + judgeModel + "...",
                            null,
                            null
                    ));

                    if (latestImage != null) {
                        Content compareContent = Content.fromParts(
                                Part.fromText("IMAGE 1 — ORIGINAL CAMERA PHOTOGRAPH:"),
                                Part.fromBytes(record.originalBytes, record.originalMimeType),
                                Part.fromText("IMAGE 2 — GENERATED RESTORED SLIDE (ITERATION " + iter + "):"),
                                Part.fromBytes(latestImage.bytes(), latestImage.mimeType()),
                                Part.fromText("Evaluate IMAGE 2 against IMAGE 1 according to your rubric and return JSON.")
                        );
                        llmRequestBuilder.contents(List.of(compareContent));
                    }
                    return Optional.empty();
                })
                .afterModelCallbackSync((callbackContext, llmResponse) -> {
                    int iter = currentIterationHolder[0];
                    String latestImageId = (String) callbackContext.state().get(STATE_LATEST_IMAGE_ID);
                    String jsonText = llmResponse.content()
                            .flatMap(Content::parts)
                            .stream()
                            .flatMap(List::stream)
                            .map(Part::text)
                            .flatMap(Optional::stream)
                            .findFirst()
                            .orElse("{}");

                    JudgeVerdict verdict = parseVerdict(jsonText);
                    long totalIterMs = System.currentTimeMillis() - iterationStartMsHolder[0];

                    IterationResult completedIteration = new IterationResult(
                            currentRunNumber,
                            iter,
                            latestImageId,
                            latestImageId != null ? "/api/slides/images/" + latestImageId : null,
                            lastPromptUsedHolder[0],
                            verdict,
                            totalIterMs
                    );
                    record.iterations.add(completedIteration);

                    if (!verdict.rewrittenPromptInstructions().isBlank()) {
                        record.latestJudgeFeedback = verdict.rewrittenPromptInstructions();
                        callbackContext.state().put(STATE_JUDGE_FEEDBACK, verdict.rewrittenPromptInstructions());
                    } else if (!verdict.critique().isBlank()) {
                        record.latestJudgeFeedback = verdict.critique();
                        callbackContext.state().put(STATE_JUDGE_FEEDBACK, verdict.critique());
                    }

                    String statusMsg = verdict.satisfied()
                            ? "Iteration " + iter + " PASSED (Score " + verdict.overallScore() + "/10)! Exiting LoopAgent."
                            : "Iteration " + iter + " scored " + verdict.overallScore() + "/10 — Judge requested refinement: " + verdict.critique();

                    emit(progressListener, new ProgressEvent(
                            "JUDGE_COMPLETED",
                            record.sessionId,
                            iter,
                            maxIterations,
                            statusMsg,
                            completedIteration,
                            toSessionResult(record)
                    ));

                    if (verdict.satisfied()) {
                        // Signal ADK LoopAgent to exit early!
                        callbackContext.eventActions().setEscalate(true);
                    }

                    return Optional.empty();
                })
                .build();

        // 3. ADK LoopAgent looping up to maxIterations (3) times
        LoopAgent slideRestorationLoop = LoopAgent.builder()
                .name("slide_restoration_loop")
                .description("Iteratively restores a conference slide photo with NanoBanana 2.1 and audits fidelity with Gemini 3.8 Flash")
                .subAgents(slideFixerAgent, slideJudgeAgent)
                .maxIterations(maxIterations)
                .build();

        App adkApp = App.builder()
                .name(APP_NAME)
                .plugins(List.of(new LoggingPlugin(APP_NAME)))
                .rootAgent(slideRestorationLoop)
                .build();

        Runner runner = InMemoryRunner.builder()
                .app(adkApp)
                .sessionService(new InMemorySessionService())
                .artifactService(new InMemoryArtifactService())
                .build();

        String adkSessionId = record.sessionId + "_run_" + currentRunNumber;
        SessionKey sessionKey = new SessionKey(APP_NAME, USER_ID, adkSessionId);
        Session session = runner.sessionService().createSession(sessionKey).blockingGet();

        if (userInstructions != null && !userInstructions.isBlank()) {
            session.state().put(STATE_USER_FEEDBACK, userInstructions.trim());
        }
        if (currentRunNumber > 1 && !record.latestJudgeFeedback.isBlank()) {
            session.state().put(STATE_JUDGE_FEEDBACK, record.latestJudgeFeedback);
        }

        Flowable<Event> events = runner.runAsync(
                session.userId(),
                session.id(),
                Content.fromParts(
                        Part.fromBytes(record.originalBytes, record.originalMimeType),
                        Part.fromText("Restore this presentation slide photo.")
                )
        );

        events.ignoreElements().blockingAwait();

        SlideSessionResult finalSessionResult = toSessionResult(record);
        emit(progressListener, new ProgressEvent(
                "LOOP_FINISHED",
                record.sessionId,
                currentIterationHolder[0],
                maxIterations,
                finalSessionResult.satisfied()
                        ? "Slide restoration verified by Judge!"
                        : "Completed " + currentIterationHolder[0] + " iterations. You can click Regenerate to refine further.",
                finalSessionResult.finalResult(),
                finalSessionResult
        ));

        return finalSessionResult;
    }

    private BaseLlm createGeminiModel(String modelName) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GOOGLE_API_KEY");
        }
        Gemini.Builder builder = Gemini.builder().modelName(modelName);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
        }
        return builder.build();
    }

    private JudgeVerdict parseVerdict(String jsonText) {
        try {
            return objectMapper.readValue(jsonText, JudgeVerdict.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse JudgeVerdict JSON: {}", jsonText, e);
            return new JudgeVerdict(false, 5, 5, 5, 5, "Could not parse structured judge output: " + jsonText, "");
        }
    }

    private SlideSessionResult toSessionResult(SessionStateRecord record) {
        List<IterationResult> copy;
        synchronized (record.iterations) {
            copy = List.copyOf(record.iterations);
        }
        IterationResult bestOrLast = null;
        if (!copy.isEmpty()) {
            // Prefer the latest satisfied iteration, or the highest-scoring iteration from the latest run
            bestOrLast = copy.getLast();
        }
        boolean satisfied = bestOrLast != null && bestOrLast.verdict() != null && bestOrLast.verdict().satisfied();
        return new SlideSessionResult(
                record.sessionId,
                record.originalFileName,
                "/api/slides/images/" + record.originalImageId,
                copy,
                bestOrLast,
                satisfied
        );
    }

    private Path saveImageToDisk(String imageId, byte[] bytes, String mimeType) {
        String ext = mimeType != null && mimeType.contains("png") ? ".png" : ".jpg";
        Path outPath = outputDir.resolve(imageId + ext);
        try {
            Files.write(outPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.error("Failed to save image to disk: {}", outPath, e);
        }
        return outPath;
    }

    public static String guessMimeType(String filename) {
        if (filename == null) {
            return "image/jpeg";
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        } else if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static void emit(Consumer<ProgressEvent> listener, ProgressEvent event) {
        if (listener != null) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                LOG.debug("Progress listener exception", e);
            }
        }
    }
}
