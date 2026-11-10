package io.github.glaforge.fixslidepic;

import io.github.glaforge.fixslidepic.service.SlideFixerService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class SlideControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    SlideFixerService slideFixerService;

    @Test
    void testPicturesEndpointListsSlidePhotos() {
        List<String> pictures = client.toBlocking().retrieve(
                HttpRequest.GET("/api/slides/test-pictures"),
                Argument.listOf(String.class)
        );
        assertNotNull(pictures);
        assertFalse(pictures.isEmpty(), "Expected test-pictures directory to contain sample slide photos");
        assertTrue(pictures.contains("PXL_20261012_080834090.jpg"));
    }

    @Test
    void testServingSingleTestPicture() {
        HttpResponse<byte[]> response = client.toBlocking()
                .exchange("/api/slides/test-pictures/OTR_1_a3b1b85d8e.png", byte[].class);
        assertEquals(200, response.code());
        assertNotNull(response.body());
        assertTrue(response.body().length > 1000);
    }

    @Test
    void testIndexHtmlIsServed() {
        String html = client.toBlocking().retrieve("/", String.class);
        assertNotNull(html);
        assertTrue(html.contains("FixSlidePic"));
        assertTrue(html.contains("download-pdf-btn"));
        assertTrue(html.contains("manifest.webmanifest"));
        assertTrue(html.contains("strip-handle"));
        assertTrue(html.contains("reorder-status"));
        assertTrue(html.contains("moveBefore"));
    }

    @Test
    void testPwaAssetsAreServed() {
        HttpResponse<String> manifestRes = client.toBlocking()
                .exchange("/manifest.webmanifest", String.class);
        assertEquals(200, manifestRes.code());
        assertNotNull(manifestRes.body());
        assertTrue(manifestRes.body().contains("\"id\": \"/\""));

        HttpResponse<String> swRes = client.toBlocking()
                .exchange("/sw.js", String.class);
        assertEquals(200, swRes.code());
        assertNotNull(swRes.body());
        assertTrue(swRes.body().contains("CACHE_NAME"));
    }

    @Test
    void testMultiImagePdfGeneration() throws IOException {
        var pic1 = slideFixerService.getTestPicture("OTR_1_a3b1b85d8e.png").orElseThrow();
        var pic2 = slideFixerService.getTestPicture("PXL_20261012_080834090.jpg").orElseThrow();

        slideFixerService.storeImageForTest("test-slide-1", pic1.bytes(), pic1.mimeType());
        slideFixerService.storeImageForTest("test-slide-2", pic2.bytes(), pic2.mimeType());

        HttpResponse<byte[]> getResponse = client.toBlocking()
                .exchange("/api/slides/pdf?ids=test-slide-1,test-slide-2", byte[].class);
        assertEquals(200, getResponse.code());
        assertNotNull(getResponse.body());
        try (PDDocument doc = Loader.loadPDF(getResponse.body())) {
            assertEquals(2, doc.getNumberOfPages(), "Expected 2 pages in generated PDF");
        }

        HttpResponse<byte[]> postResponse = client.toBlocking()
                .exchange(HttpRequest.POST("/api/slides/pdf", Map.of("imageIds", List.of("test-slide-1", "test-slide-2"))), byte[].class);
        assertEquals(200, postResponse.code());
        assertNotNull(postResponse.body());
        try (PDDocument doc = Loader.loadPDF(postResponse.body())) {
            assertEquals(2, doc.getNumberOfPages(), "Expected 2 pages in POST generated PDF");
        }
    }

    @Test
    void testSessionRecoveryFromDisk() throws IOException {
        var pic1 = slideFixerService.getTestPicture("OTR_1_a3b1b85d8e.png").orElseThrow();
        java.nio.file.Path diskOrig = java.nio.file.Path.of("build/generated-slides/orig_disksess.png");
        java.nio.file.Files.createDirectories(diskOrig.getParent());
        java.nio.file.Files.write(diskOrig, pic1.bytes());

        var recovered = slideFixerService.getSessionResult("disksess");
        assertTrue(recovered.isPresent(), "Expected session 'disksess' to be recovered from orig_disksess.png on disk");
        assertEquals("disksess", recovered.get().sessionId());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_ADK_TESTS", matches = "true")
    void testAdkLoopAgentSlideRestorationEndToEnd() {
        var testPic = slideFixerService.getTestPicture("OTR_1_a3b1b85d8e.png").orElseThrow();
        var result = slideFixerService.startNewSession(
                "OTR_1_a3b1b85d8e.png",
                testPic.bytes(),
                testPic.mimeType(),
                null,
                evt -> System.out.println("[SSE Event] " + evt.type() + " -> " + evt.message())
        );
        assertNotNull(result);
        assertFalse(result.iterations().isEmpty());
        assertNotNull(result.finalResult());
        assertNotNull(result.finalResult().imageId());
        assertNotNull(result.finalResult().verdict());
        System.out.println("Final Verdict: " + result.finalResult().verdict());
    }
}
