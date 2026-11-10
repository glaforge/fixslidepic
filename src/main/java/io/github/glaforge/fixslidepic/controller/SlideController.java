package io.github.glaforge.fixslidepic.controller;

import io.github.glaforge.fixslidepic.model.ProgressEvent;
import io.github.glaforge.fixslidepic.model.SlideSessionResult;
import io.github.glaforge.fixslidepic.service.SlideFixerService;
import io.github.glaforge.fixslidepic.service.SlideFixerService.StoredImage;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller("/api/slides")
public class SlideController {

    private static final int MAX_CONCURRENT_RESTORATIONS = 8;
    private final ExecutorService restorationExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_RESTORATIONS);
    private final SlideFixerService slideFixerService;

    public SlideController(SlideFixerService slideFixerService) {
        this.slideFixerService = slideFixerService;
    }

    @Serdeable
    public record RegenerateRequest(@Nullable String instructions) {}

    @Serdeable
    public record PdfRequest(List<String> imageIds) {}

    @Get("/pdf")
    @Produces("application/pdf")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<byte[]> downloadPdfGet(@QueryValue("ids") String idsCsv) throws IOException {
        if (idsCsv == null || idsCsv.isBlank()) {
            return HttpResponse.badRequest();
        }
        List<String> imageIds = Arrays.stream(idsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (imageIds.isEmpty()) {
            return HttpResponse.badRequest();
        }
        byte[] pdfBytes = slideFixerService.createPdfFromImages(imageIds);
        return HttpResponse.ok(pdfBytes)
                .contentType(MediaType.APPLICATION_PDF_TYPE)
                .header("Content-Disposition", "attachment; filename=\"restored-slides.pdf\"");
    }

    @Post("/pdf")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/pdf")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<byte[]> downloadPdfPost(@Body PdfRequest request) throws IOException {
        if (request == null || request.imageIds() == null || request.imageIds().isEmpty()) {
            return HttpResponse.badRequest();
        }
        byte[] pdfBytes = slideFixerService.createPdfFromImages(request.imageIds());
        return HttpResponse.ok(pdfBytes)
                .contentType(MediaType.APPLICATION_PDF_TYPE)
                .header("Content-Disposition", "attachment; filename=\"restored-slides.pdf\"");
    }

    @Get("/test-pictures")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> listTestPictures() {
        return slideFixerService.listTestPictures();
    }

    @Get("/test-pictures/{filename}")
    public HttpResponse<byte[]> getTestPicture(@PathVariable String filename) {
        Optional<StoredImage> pic = slideFixerService.getTestPicture(filename);
        if (pic.isEmpty()) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(pic.get().bytes())
                .contentType(MediaType.of(pic.get().mimeType()));
    }

    @Get("/images/{imageId}")
    public HttpResponse<byte[]> getImage(@PathVariable String imageId) {
        Optional<StoredImage> img = slideFixerService.getStoredImage(imageId);
        if (img.isEmpty()) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(img.get().bytes())
                .contentType(MediaType.of(img.get().mimeType()));
    }

    @Get("/sessions/{sessionId}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<SlideSessionResult> getSession(@PathVariable String sessionId) {
        return slideFixerService.getSessionResult(sessionId)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    @Post("/fix")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<SlideSessionResult> fixSlideSync(
            @Nullable @Part("file") CompletedFileUpload file,
            @Nullable @Part("testPicture") String testPicture,
            @Nullable @Part("instructions") String instructions) throws IOException {

        ResolvedInput input = resolveInput(file, testPicture);
        if (input == null) {
            return HttpResponse.badRequest();
        }
        SlideSessionResult result = slideFixerService.startNewSession(
                input.fileName(),
                input.bytes(),
                input.mimeType(),
                instructions,
                null
        );
        return HttpResponse.ok(result);
    }

    @Post("/fix-stream")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_EVENT_STREAM)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public Publisher<Event<ProgressEvent>> fixSlideStream(
            @Nullable @Part("file") CompletedFileUpload file,
            @Nullable @Part("testPicture") String testPicture,
            @Nullable @Part("instructions") String instructions) throws IOException {

        ResolvedInput input = resolveInput(file, testPicture);
        if (input == null) {
            return Flux.just(Event.of(new ProgressEvent(
                    "ERROR", "", 0, 3, "Please provide an uploaded image file or select a test picture.", null, null
            )));
        }

        return Flux.create(sink -> CompletableFuture.runAsync(() -> {
            try {
                slideFixerService.startNewSession(
                        input.fileName(),
                        input.bytes(),
                        input.mimeType(),
                        instructions,
                        progress -> sink.next(Event.of(progress))
                );
                sink.complete();
            } catch (Exception e) {
                sink.next(Event.of(new ProgressEvent(
                        "ERROR", "", 0, 3, "Error restoring slide: " + e.getMessage(), null, null
                )));
                sink.complete();
            }
        }, restorationExecutor));
    }

    @Post("/sessions/{sessionId}/regenerate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<SlideSessionResult> regenerateSync(
            @PathVariable String sessionId,
            @Nullable @Body RegenerateRequest request) {

        String instructions = request != null ? request.instructions() : null;
        SlideSessionResult result = slideFixerService.regenerateSession(sessionId, instructions, null);
        return HttpResponse.ok(result);
    }

    @Post("/sessions/{sessionId}/regenerate-stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_EVENT_STREAM)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public Publisher<Event<ProgressEvent>> regenerateStream(
            @PathVariable String sessionId,
            @Nullable @Body RegenerateRequest request) {

        String instructions = request != null ? request.instructions() : null;
        return Flux.create(sink -> CompletableFuture.runAsync(() -> {
            try {
                slideFixerService.regenerateSession(
                        sessionId,
                        instructions,
                        progress -> sink.next(Event.of(progress))
                );
                sink.complete();
            } catch (Exception e) {
                sink.next(Event.of(new ProgressEvent(
                        "ERROR", sessionId, 0, 3, "Error regenerating slide: " + e.getMessage(), null, null
                )));
                sink.complete();
            }
        }, restorationExecutor));
    }

    private record ResolvedInput(String fileName, byte[] bytes, String mimeType) {}

    private ResolvedInput resolveInput(CompletedFileUpload file, String testPicture) throws IOException {
        if (file != null && file.getSize() > 0) {
            String filename = file.getFilename() != null ? file.getFilename() : "uploaded-slide.jpg";
            String mimeType = file.getContentType().map(MediaType::toString)
                    .orElseGet(() -> SlideFixerService.guessMimeType(filename));
            return new ResolvedInput(filename, file.getBytes(), mimeType);
        }
        if (testPicture != null && !testPicture.isBlank()) {
            Optional<StoredImage> stored = slideFixerService.getTestPicture(testPicture.trim());
            if (stored.isPresent()) {
                return new ResolvedInput(testPicture.trim(), stored.get().bytes(), stored.get().mimeType());
            }
        }
        return null;
    }
}
