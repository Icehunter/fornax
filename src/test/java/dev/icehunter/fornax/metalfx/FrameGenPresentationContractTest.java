package dev.icehunter.fornax.metalfx;

import com.mojang.blaze3d.systems.GpuSurface;
import dev.icehunter.fornax.pass.FrameGenPresenter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure state/policy checks plus source ordering where execution needs a live Vulkan surface. */
class FrameGenPresentationContractTest {
    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax/");

    @Test
    void everySurfaceModeHasTheSameEligibilityForGenerationAndPresentation() throws Exception {
        Method reason = requiredMethod(FrameGenPresenter.class, "blockReasonFor");
        for (GpuSurface.PresentMode mode : GpuSurface.PresentMode.values()) {
            Object blocked = reason.invoke(null, mode);
            if (mode == GpuSurface.PresentMode.FIFO || mode == GpuSurface.PresentMode.FIFO_RELAXED) {
                assertNull(blocked, "FIFO modes can display generated frames");
            } else {
                assertEquals("VSync required", blocked, "uncapped modes must state their blocker");
            }
        }
        assertEquals("surface unavailable", reason.invoke(null, new Object[] {null}));
    }

    @Test
    void unavailablePresentationSkipsTheProducerBeforeCopiesAndPreservesDebugView() throws Exception {
        String source = read("metalfx/FrameGenPass.java");
        String method = method(source, "public static void runIfEnabled(");
        int gate = method.indexOf("!DEBUG_VIEW && FrameGenPresenter.presentationBlockReason() != null");
        assertTrue(gate >= 0, "producer must consult the presenter's shared surface policy");
        int run = method.indexOf("run(nativeDest,");
        assertTrue(gate < run, "the surface check must precede the first copy/interpolation");
        String blocked = method.substring(gate, method.indexOf("}", gate));
        assertTrue(blocked.contains("skipGeneratedFrame();"));
        assertTrue(blocked.contains("return;"));
    }

    @Test
    void suspendingGenerationDropsReadinessAndRequiresFreshHistoryOnResume() throws Exception {
        Method skip = requiredMethod(FrameGenPass.class, "skipGeneratedFrame");
        Field ready = field("generatedReady");
        Field history = field("hasHistory");
        Field reset = field("pendingReset");
        Object oldReady = ready.get(null), oldHistory = history.get(null), oldReset = reset.get(null);
        try {
            ready.setBoolean(null, true);
            history.setBoolean(null, true);
            reset.setBoolean(null, false);
            skip.invoke(null);
            assertFalse(ready.getBoolean(null));
            assertFalse(history.getBoolean(null));
            assertTrue(reset.getBoolean(null));
            skip.invoke(null);
            assertFalse(ready.getBoolean(null));
            assertFalse(history.getBoolean(null));
            assertTrue(reset.getBoolean(null), "repeated blocked frames retain the pending reset");
        } finally {
            ready.set(null, oldReady); history.set(null, oldHistory); reset.set(null, oldReset);
        }
        String run = method(read("metalfx/FrameGenPass.java"), "private static void run(");
        int historyGate = run.indexOf("if (hasHistory && CLOCK.ready())");
        int interpolation = run.indexOf("interpolator.encode(");
        int seedHistory = run.indexOf("hasHistory = true;");
        assertTrue(historyGate >= 0 && interpolation > historyGate && seedHistory > interpolation,
                "first resumed frame seeds history; only the following frame can interpolate");
    }

    @Test
    void overlayShowsThePresentationBlockerAheadOfPacerEngagement() throws Exception {
        String overlay = method(read("pass/FrameGenPresenter.java"), "public static String overlayLine(");
        assertTrue(overlay.contains("presentationBlockReason()"));
        assertTrue(overlay.contains("blocked != null ? blocked"), "engaged cannot hide VSync blockage");
        String presenter = read("pass/FrameGenPresenter.java");
        for (String signature : new String[] {"public static void prepareGeneratedFrame(",
                "public static void presentGeneratedIfReady("}) {
            assertTrue(method(presenter, signature).contains("presentationBlockReason("),
                    "staging and presentation must use the producer's eligibility policy");
        }
    }

    @Test
    void cpuMeasurementsCoverInteropCallsAndThePerFrameMeshPathNeverHostWaits() throws Exception {
        assertTimed(read("metalfx/FrameGenPass.java"), "public static void runIfEnabled(", "frame generation CPU");
        assertTimed(read("metalfx/MetalFxUpscalePass.java"), "public static boolean runIfEnabled(", "MetalFX upscale CPU");
        String terrain = read("metalfx/rt/MeshMetalProvider.java");
        assertTimed(terrain, "public void fillCelestialVisibility(", "RT shadows CPU");
        assertTrue(terrain.contains("recordValue(\"rt_shadow_dirty_meshes\", dirty.size())"));
        // The per-frame path never host-waits on a mesh change: a build is traced only once its
        // own command buffer status confirms it is complete, a fact checked without waiting. The
        // host wait survives only outside the frame path: a format/resolution resize in
        // ensureImages, and teardown in close.
        assertFalse(terrain.contains("awaitMeshChange"), "the per-frame path must not host-wait on a mesh change");
        assertEquals(3, terrain.split("await\\(device\\)", -1).length - 1,
                "await(device) must appear only in ensureImages (resize, twice) and close (teardown)");
    }

    private static void assertTimed(String source, String signature, String label) {
        String body = method(source, signature);
        assertTrue(body.contains("System.nanoTime()"));
        assertTrue(body.contains("finally {"));
        assertTrue(body.contains("record(\"" + label + "\""), label + " must use the existing profiler");
    }

    private static String read(String path) throws Exception { return Files.readString(SOURCE.resolve(path)); }
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing production scope " + signature);
        return source.substring(start, source.indexOf("\n    }", start));
    }
    private static Method requiredMethod(Class<?> owner, String name) {
        Method method = Arrays.stream(owner.getDeclaredMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElse(null);
        assertNotNull(method, "missing production policy/state operation " + name);
        method.setAccessible(true);
        return method;
    }
    private static Field field(String name) throws Exception {
        Field field = FrameGenPass.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
