package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import dev.icehunter.fornax.util.GpuFatalException;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** CPU queue-order regression: runs production admission with recorded, submitted and completed uploads. */
class AtlasTextureInitializationTest {
    private static final List<String> INPUTS = List.of("builtin.blockAtlas", "builtin.normalAtlas",
            "builtin.materialAtlas", "builtin.blockAtlasPages", "builtin.materialAtlasPages");

    @Test void sameFramePublicationCompletesBeforeRawComputeReadsTheAtlas() {
        Fixture unordered = new Fixture();
        unordered.publish(INPUTS.getFirst(), 15);
        assertEquals(-1, unordered.images.get(INPUTS.getFirst())[0], "publication only records the upload");
        unordered.graphics.submit();
        unordered.graphics.drain();

        Fixture ordered = new Fixture();
        ordered.publish(INPUTS.getFirst(), 15);
        ordered.prepare();
        assertEquals(15, ordered.read(INPUTS.getFirst()));
        assertEquals(List.of("fence", "submit", "await", "close", "compute"), ordered.graphics.events);
    }

    @Test void lazyNeutralViewsAreAllMaterializedBeforeOneCompletionAndUnchangedFramesDoNotWait() {
        Fixture f = new Fixture();
        f.gate.prepare(INPUTS, name -> {
            f.graphics.events.add("resolve:" + name);
            return f.images.computeIfAbsent(name, key -> f.upload(0));
        }, () -> f.graphics);
        for (String name : INPUTS) assertEquals(0, f.read(name));
        assertEquals(INPUTS.stream().map(name -> "resolve:" + name).toList(), f.graphics.events.subList(0, 5));
        assertEquals("fence", f.graphics.events.get(5));
        assertEquals(1, f.graphics.submits);
        f.gate.prepare(INPUTS, f.images::get, () -> { throw new AssertionError("unchanged identities must not wait"); });
        assertEquals(0, f.read(INPUTS.getFirst()));
    }

    @Test void unchangedResourceReloadStillOrdersNewAlbedoPagesWithReusedSidecars() {
        Fixture f = new Fixture();
        for (String name : INPUTS) f.publish(name, 1);
        f.prepare();
        int[] sameMaterial = f.images.get("builtin.materialAtlas");
        int[] sameMaterialPages = f.images.get("builtin.materialAtlasPages");
        // F3+T can reuse a sidecar content generation while vanilla albedo and its overflow rebuild.
        f.publish("builtin.blockAtlas", 2);
        f.publish("builtin.blockAtlasPages", 3);
        assertThrows(GpuFatalException.class, () -> f.read("builtin.blockAtlasPages"));
        f.prepare();
        assertSame(sameMaterial, f.images.get("builtin.materialAtlas"));
        assertSame(sameMaterialPages, f.images.get("builtin.materialAtlasPages"));
        assertEquals(2, f.read("builtin.blockAtlas"));
        assertEquals(3, f.read("builtin.blockAtlasPages"));
        assertEquals(2, f.graphics.submits, "one completion per changed resource set, not per texture");
    }

    @Test void retirementAndReplacementCannotBorrowThePreviousGenerationAdmission() {
        Fixture f = new Fixture();
        f.publish("builtin.materialAtlasPages", 15);
        f.prepare();
        f.publish("builtin.materialAtlasPages", 0);
        assertThrows(GpuFatalException.class, () -> f.read("builtin.materialAtlasPages"));
        f.prepare();
        assertEquals(0, f.read("builtin.materialAtlasPages"));
        f.publish("builtin.materialAtlasPages", 9);
        f.prepare();
        assertEquals(9, f.read("builtin.materialAtlasPages"));
        f.gate.clear();
        assertThrows(GpuFatalException.class, () -> f.read("builtin.materialAtlasPages"));
    }

    @Test void timeoutAndCompletionExceptionsNeverAdmitAnUnfinishedUpload() {
        for (String failure : List.of("timeout", "encoder", "fence", "submit", "await", "close")) {
            Fixture f = new Fixture();
            f.publish("builtin.blockAtlasPages", 15);
            f.graphics.failure = failure;
            assertThrows(RuntimeException.class, f::prepare, failure);
            assertThrows(GpuFatalException.class, () -> f.read("builtin.blockAtlasPages"), failure);
            assertEquals(0, f.reads);
            f.graphics.failure = null;
            f.prepare();
            assertEquals(15, f.read("builtin.blockAtlasPages"));
        }
    }

    @Test void onlyDeclaredAtlasInputsAreResolvedAndLateInputsAreRejected() {
        Fixture f = new Fixture();
        f.gate.prepare(List.of("globals", "terrainColor"), name -> {
            throw new AssertionError("non-atlas inputs must not be materialized here");
        }, () -> { throw new AssertionError("no atlas input means no wait"); });
        f.gate.requirePrepared("raster", "terrainColor", new int[]{0});
        f.publish("builtin.normalAtlas", 0);
        assertThrows(GpuFatalException.class, () -> f.read("builtin.normalAtlas"));
    }

    private static final class Fixture {
        final AtlasTextureInitialization<int[]> gate = new AtlasTextureInitialization<>();
        final QueueEncoder graphics = new QueueEncoder();
        final Map<String, int[]> images = new LinkedHashMap<>();
        int reads;
        int[] upload(int value) {
            int[] image = {-1};
            graphics.recorded.add(() -> image[0] = value);
            return image;
        }
        void publish(String name, int value) { images.put(name, upload(value)); }
        void prepare() { gate.prepare(images.keySet(), images::get, () -> { graphics.fail("encoder"); return graphics; }); }
        int read(String name) {
            int[] image = images.get(name);
            gate.requirePrepared("source", name, image);
            graphics.events.add("compute");
            reads++;
            return image[0];
        }
    }

    private static final class QueueEncoder extends CommandEncoder {
        final List<Runnable> recorded = new ArrayList<>(), submitted = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int submits;
        String failure;
        QueueEncoder() { super(null, null, null); }
        void fail(String stage) { if (stage.equals(failure)) throw new IllegalStateException(stage); }
        @Override public void submit() {
            events.add("submit"); fail("submit");
            submitted.addAll(recorded); recorded.clear(); submits++;
        }
        void drain() { submitted.forEach(Runnable::run); submitted.clear(); }
        @Override public GpuFence createFence() {
            events.add("fence"); fail("fence");
            int batch = submits;
            return new GpuFence() {
                @Override public boolean awaitCompletion(long timeout) {
                    events.add("await"); fail("await");
                    assertTrue(timeout > 0);
                    assertTrue(batch < submits, "the fence must name an already submitted upload batch");
                    if ("timeout".equals(failure)) return false;
                    drain(); return true;
                }
                @Override public void close() { events.add("close"); fail("close"); }
            };
        }
    }
}
