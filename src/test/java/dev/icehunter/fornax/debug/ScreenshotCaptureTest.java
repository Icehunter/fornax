package dev.icehunter.fornax.debug;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.GpuFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenshotCaptureTest {
    private static final String VALID = """
            {"targets":["colour.history","colour","builtin.gMotion"],
             "crop":{"x":700,"y":386,"width":512,"height":448}}
            """;

    @Test
    void exactHistoryAndCurrentReferencesKeepTheirOrderAndMeaning() {
        var request = ScreenshotCaptureRequest.parse(VALID);
        assertEquals(List.of("colour.history", "colour", "builtin.gMotion"), request.targets());
        assertEquals(new ScreenshotCaptureRequest.Region(700, 386, 512, 448), request.crop());
        assertThrows(UnsupportedOperationException.class, () -> request.targets().add("extra"));
    }

    @Test
    void invalidAndMisspelledRequestsFailBeforeAnyCaptureIsScheduled() {
        for (String source : List.of(
                VALID.replace("\"targets\"", "\"target\""),
                VALID.replace("\"colour.history\",\"colour\"", "\"colour\",\"colour\""),
                VALID.replace("\"colour.history\"", "\"../texture\""),
                VALID.replace("\"colour.history\"", "3"),
                VALID.replace("700", "700.1"),
                VALID.replace("700", "\"700\""),
                VALID.replace("700", "-1"),
                VALID.replace("512", "0"),
                VALID.replace("448", "2147483648"),
                VALID.replace("\"crop\":", "\"other\":false,\"crop\":"))) {
            assertThrows(RuntimeException.class, () -> ScreenshotCaptureRequest.parse(source), source);
        }
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotCaptureRequest(List.of(),
                new ScreenshotCaptureRequest.Region(0, 0, 1, 1)));
        var many = new ArrayList<String>();
        for (int i = 0; i < 17; i++) many.add("target" + i);
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotCaptureRequest(many,
                new ScreenshotCaptureRequest.Region(0, 0, 1, 1)));
    }

    @Test
    void rawCropScalingRoundsOutwardAndNeverResamplesOrFlips() {
        var crop = new ScreenshotCaptureRequest.Region(3, 5, 4, 6);
        assertEquals(crop, crop.scaled(17, 19, 17, 19));
        // [3,7) x [5,11) becomes floor(start)..ceil(end) in an 8x9 target.
        assertEquals(new ScreenshotCaptureRequest.Region(1, 2, 3, 4), crop.scaled(17, 19, 8, 9));
        assertEquals(new ScreenshotCaptureRequest.Region(6, 10, 8, 12), crop.scaled(17, 19, 34, 38));
        assertThrows(IllegalArgumentException.class, () -> crop.scaled(6, 19, 8, 9));
        assertThrows(IllegalArgumentException.class, () -> crop.scaled(17, 0, 8, 9));
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotCaptureRequest.Region(Integer.MAX_VALUE,
                0, Integer.MAX_VALUE, 1).scaled(1728, 1084, 1728, 1084));
    }

    @Test
    void byteCountsUseFormatStrideAndRejectUnsupportedOrOversizedTextures() {
        var crop = new ScreenshotCaptureRequest.Region(0, 0, 3, 2);
        assertEquals(48, crop.bytes(GpuFormat.RGBA16_FLOAT, 1));
        assertEquals(24, crop.bytes(GpuFormat.D32_FLOAT, 1));
        assertThrows(IllegalArgumentException.class, () -> crop.bytes(GpuFormat.RGBA8_UNORM, 2));
        assertThrows(IllegalArgumentException.class, () -> crop.bytes(GpuFormat.D32_FLOAT_S8_UINT, 1));
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotCaptureRequest.Region(0, 0, 2048, 2048)
                .bytes(GpuFormat.RGBA16_FLOAT, 1));
    }

    @Test
    void eightMeasuredRequestsFitButTheNinthWaitsForRetirement() {
        var budget = new ScreenshotCaptureRequest.Budget();
        // A 512x448 native crop with 72 bytes/pixel across all selected targets is 15.75 MiB.
        long bytes = 512L * 448 * 72;
        assertEquals(16_515_072L, bytes);
        List<ScreenshotCaptureRequest.Reservation> reservations = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            var reservation = budget.reserve(bytes);
            assertNotNull(reservation);
            reservations.add(reservation);
        }
        assertEquals(8, budget.requests());
        assertEquals(126L * 1024 * 1024, budget.bytes());
        assertNull(budget.reserve(1));
        reservations.getFirst().close();
        reservations.getFirst().close();
        assertEquals(7, budget.requests(), "a repeated close cannot release another request's reservation");
        assertNotNull(budget.reserve(bytes));
        assertNull(budget.reserve(ScreenshotCaptureRequest.MAX_BYTES + 1));
        assertNull(budget.reserve(-1));
    }

    @Test
    void completedFrameKeepsImmutableOptionsAndRejectsMismatchedPromotion() {
        Map<String, Integer> options = new LinkedHashMap<>();
        options.put("EXAMPLE", 1);
        var frame = new ScreenshotCapture.Frame(23, .1f, .2f, .3f, .4f, 864, 542, 8, options, 1, 2, 3);
        options.put("EXAMPLE", 2);
        assertEquals(1, frame.compileOptions().get("EXAMPLE"));
        assertThrows(UnsupportedOperationException.class, () -> frame.compileOptions().put("OTHER", 3));
        var promoted = ScreenshotCapture.promote(frame, 23, 1728, 1084);
        assertNotNull(promoted);
        assertEquals(23, promoted.graph().frameCounter());
        assertEquals(.3f, promoted.graph().previousJitterX());
        assertEquals(1728, promoted.nativeWidth());
        assertNull(ScreenshotCapture.promote(frame, 24, 1728, 1084));
        assertNull(ScreenshotCapture.promote(null, 23, 1728, 1084));
        assertNull(ScreenshotCapture.promote(frame, 23, 0, 1084));
    }

    @Test
    void inlineCallbacksCannotFinishBeforeAllCopiesHaveBeenQueued(@TempDir Path directory) throws Exception {
        var session = new ScreenshotCapture.Session(directory, null);
        var budget = new ScreenshotCaptureRequest.Budget();
        session.reservation = budget.reserve(80);
        session.pending = 2;
        var first = entry("pending");
        var second = entry("pending");
        session.entries.add(first);
        session.entries.add(second);
        session.retired(first, null);
        session.retired(second, null);
        assertEquals("pending", status(directory));
        assertEquals(1, budget.requests());
        session.queued();
        assertEquals("complete", status(directory));
        assertEquals(0, budget.requests());
        assertEquals(0, budget.bytes());
    }

    @Test
    void unavailableTargetsAndFailedCallbacksCannotClaimComplete(@TempDir Path directory) throws Exception {
        var session = new ScreenshotCapture.Session(directory, null);
        var budget = new ScreenshotCaptureRequest.Budget();
        session.reservation = budget.reserve(80);
        session.pending = 1;
        session.entries.add(entry("unavailable"));
        var pending = entry("pending");
        session.entries.add(pending);
        session.queued();
        assertEquals("pending", status(directory));
        assertEquals(1, budget.requests());
        session.retired(pending, "mapping failed");
        assertEquals("incomplete", status(directory));
        assertEquals("failed", pending.get("status"));
        assertEquals(0, budget.requests());
    }

    @Test
    void rejectedRequestsLeaveAnExplicitManifestWithoutPendingFiles(@TempDir Path directory) throws Exception {
        var session = new ScreenshotCapture.Session(directory, null);
        session.entries.add(entry("pending"));
        session.reject("pending request limit");
        assertEquals("rejected", status(directory));
        assertEquals("unavailable", session.entries.getFirst().get("status"));
        assertEquals(0, session.pending);
        var manifest = JsonParser.parseString(Files.readString(directory.resolve("manifest.json"))).getAsJsonObject();
        assertTrue(manifest.get("coordinateSpace").getAsString().contains("GPU"));
        assertTrue(manifest.get("pngComparison").getAsString().contains("compare RGB"));
        assertFalse(manifest.get("error").getAsString().isEmpty());
    }

    private static Map<String, Object> entry(String status) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("status", status);
        return entry;
    }

    private static String status(Path directory) throws Exception {
        return JsonParser.parseString(Files.readString(directory.resolve("manifest.json")))
                .getAsJsonObject().get("status").getAsString();
    }
}
