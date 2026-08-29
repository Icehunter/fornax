package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.option.OptionScanner;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphValidatorTest {
    @Test
    void validGraphProducesVramReportWithPositiveBytes() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.gAlbedo]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "terrain"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"
                outputs = ["gAlbedo"]
                """), "graph.toml");
        VramReport report = GraphValidator.validate(g, Map.of(), 1920, 1080);
        assertTrue(report.totalBytes() > 0);
        // One declared target plus the engine-injected sceneHistory row: the injected pair is real
        // VRAM the session holds (GraphRunner.rebuild adds it to every pack), so the report must
        // say so rather than silently understate every pack by two full-size color textures.
        assertEquals(2, report.lines().size());
        String engineRow = report.lines().get(1);
        assertTrue(engineRow.contains("sceneHistory"), "expected the engine-injected row, got: " + engineRow);
        assertTrue(engineRow.contains("x2(history)"), "sceneHistory must be costed as a ping-pong pair: " + engineRow);
        assertTrue(engineRow.contains("(engine-injected)"), "engine row must be labeled as such: " + engineRow);
    }

    @Test
    void passOutputUndeclaredTargetFixtureThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(resource("missing_target/graph.toml"), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.terrain.outputs", e.key());
    }

    @Test
    void passInputUndeclaredTargetThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.gAlbedo]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "post"
                type = "fullscreen"
                shader = "shaders/post/x.fsh"
                inputs = ["ghost"]
                outputs = ["gAlbedo"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.post.inputs", e.key());
    }

    @Test
    void historyReadWithoutHistoryDeclaredThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.ssr]
                format = "rgba16f"
                scale = 1.0

                [[pass]]
                name = "trace"
                type = "fullscreen"
                shader = "shaders/post/trace.fsh"
                inputs = ["ssr.history"]
                outputs = ["ssr"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.trace.inputs", e.key());
        assertTrue(e.reason().contains("history"));
    }

    @Test
    void historyPingPongPairValidatesClean() {
        // Canonical temporal ping-pong: one pass writes X while reading X.history,
        // a second pass consumes X same-frame. The history read is a previous-frame
        // read and must form neither a cycle edge nor a self-feedback.
        assertDoesNotThrow(() -> GraphValidator.validate(pingPongGraph(true), Map.of(), 1920, 1080));
    }

    @Test
    void sameFrameSelfFeedbackThrows() {
        // The same pair minus ".history": now the temporal pass reads the very
        // target it writes this frame, a GPU read-write feedback hazard.
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(pingPongGraph(false), Map.of(), 1920, 1080));
        assertEquals("graph.toml", e.file());
        assertEquals("pass.temporal", e.key());
        assertTrue(e.reason().contains("taa"));
        assertTrue(e.reason().contains("same frame"));
    }

    // --- temporal pass type ---------------------------------------------------------------------

    private static String temporalGraph(String targetsToml, String passToml) {
        return """
                [targets.sceneHdr]
                format = "rgba16f"
                scale = 1.0

                """ + targetsToml + """

                [[pass]]
                name = "terrain"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"
                outputs = ["sceneHdr"]

                """ + passToml + """

                [[pass]]
                name = "tonemap"
                type = "fullscreen"
                shader = "shaders/post/tonemap.fsh"
                inputs = ["sceneAcc"]
                outputs = ["builtin.output"]
                """;
    }

    private static final String ACC_TARGET = """
            [targets.sceneAcc]
            format = "rgba16f"
            scale = 1.0
            history = true
            """;

    @Test
    void validTemporalPassValidatesClean() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader(temporalGraph(ACC_TARGET, """
                [[pass]]
                name = "temporal_accumulate"
                type = "temporal"
                inputs = ["sceneHdr"]
                outputs = ["sceneAcc"]
                """)), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, Map.of(), 1920, 1080));
    }

    @Test
    void temporalOutputWithoutHistoryThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader(temporalGraph("""
                [targets.sceneAcc]
                format = "rgba16f"
                scale = 1.0
                """, """
                [[pass]]
                name = "temporal_accumulate"
                type = "temporal"
                inputs = ["sceneHdr"]
                outputs = ["sceneAcc"]
                """)), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.temporal_accumulate.outputs", e.key());
        assertTrue(e.reason().contains("history = true"));
    }

    @Test
    void temporalShapeMismatchThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader(temporalGraph("""
                [targets.sceneAcc]
                format = "rgba8"
                scale = 1.0
                history = true
                """, """
                [[pass]]
                name = "temporal_accumulate"
                type = "temporal"
                inputs = ["sceneHdr"]
                outputs = ["sceneAcc"]
                """)), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.temporal_accumulate", e.key());
        assertTrue(e.reason().contains("format"));
    }

    @Test
    void temporalFixedExtentPairValidatesOnlyWhenBothExtentsMatch() {
        GraphSpec matching = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.source]
                format = "rgba16f"
                width = 512
                height = 256

                [targets.accum]
                format = "rgba16f"
                width = 512
                height = 256
                history = true

                [[pass]]
                name = "source_write"
                type = "fullscreen"
                shader = "shaders/post/source.fsh"
                outputs = ["source"]

                [[pass]]
                name = "temporal_accumulate"
                type = "temporal"
                inputs = ["source"]
                outputs = ["accum"]
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(matching, Map.of(), 1920, 1080));

        GraphSpec mismatched = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.source]
                format = "rgba16f"
                width = 512
                height = 256

                [targets.accum]
                format = "rgba16f"
                width = 256
                height = 256
                history = true

                [[pass]]
                name = "source_write"
                type = "fullscreen"
                shader = "shaders/post/source.fsh"
                outputs = ["source"]

                [[pass]]
                name = "temporal_accumulate"
                type = "temporal"
                inputs = ["source"]
                outputs = ["accum"]
                """), "graph.toml");
        assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(mismatched, Map.of(), 1920, 1080));
    }

    @Test
    void temporalWithPackShaderThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader(temporalGraph(ACC_TARGET, """
                [[pass]]
                name = "temporal_accumulate"
                type = "temporal"
                shader = "shaders/post/my_taa.fsh"
                inputs = ["sceneHdr"]
                outputs = ["sceneAcc"]
                """)), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.temporal_accumulate.shader", e.key());
        assertTrue(e.reason().contains("engine-owned"));
    }

    @Test
    void temporalWrongArityThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader(temporalGraph(ACC_TARGET, """
                [[pass]]
                name = "temporal_accumulate"
                type = "temporal"
                inputs = ["sceneHdr", "sceneAcc.history"]
                outputs = ["sceneAcc"]
                """)), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.temporal_accumulate", e.key());
        assertTrue(e.reason().contains("exactly one input"));
    }

    @Test
    void temporalBuiltinInputThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader(temporalGraph(ACC_TARGET, """
                [[pass]]
                name = "temporal_accumulate"
                type = "temporal"
                inputs = ["builtin.depth"]
                outputs = ["sceneAcc"]
                """)), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.temporal_accumulate", e.key());
        assertTrue(e.reason().contains("pack targets"));
    }

    @Test
    void chainedBuiltinOutputRewritesValidateClean() {
        // A straight-line post-process chain where two different passes each write
        // builtin.output in turn (mirrors dev_graph's resolve -> ... -> taa_copy_out):
        // this is a normal sequential handoff to the pipeline's terminal sink, not a
        // same-frame producer/consumer cycle, and must not be flagged as one.
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.mid]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "first"
                type = "fullscreen"
                shader = "shaders/post/first.fsh"
                inputs = ["builtin.depth"]
                outputs = ["builtin.output"]

                [[pass]]
                name = "second"
                type = "fullscreen"
                shader = "shaders/post/second.fsh"
                inputs = ["builtin.output"]
                outputs = ["mid"]

                [[pass]]
                name = "third"
                type = "fullscreen"
                shader = "shaders/post/third.fsh"
                inputs = ["mid"]
                outputs = ["builtin.output"]
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, Map.of(), 1920, 1080));
    }

    private static GraphSpec pingPongGraph(boolean historyRead) {
        return PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.taa]
                format = "rgba16f"
                scale = 1.0
                history = true

                [[pass]]
                name = "temporal"
                type = "fullscreen"
                shader = "shaders/post/temporal.fsh"
                inputs = ["%s", "builtin.depth"]
                outputs = ["taa"]

                [[pass]]
                name = "consume"
                type = "fullscreen"
                shader = "shaders/post/consume.fsh"
                inputs = ["taa"]
                outputs = ["builtin.output"]
                """.formatted(historyRead ? "taa.history" : "taa")), "graph.toml");
    }

    // --- Gate-consistency: a pass must never be enabled while a target it references is
    // unallocated. An enabled pass referencing a disabled (never-allocated) target fails at
    // runner build, which ensureRunnersBuilt()'s retry swallows, taking the ENTIRE post chain
    // (resolve included) down silently: terrain draws into the G-buffer but nothing composites it.
    // These pin the load-loud refusal instead. -------------------------------------------------

    private static Map<String, PackOption> ssrOptions() {
        Map<String, String> shaderSrc = new LinkedHashMap<>();
        shaderSrc.put("shaders/post/opts.fsh", """
                #define SSAO_ENABLED //[] compile "Ambient Occlusion"
                #define SSR_QUALITY 1 //[0 1 2] compile "Reflections" {0="Off" 1="Fancy" 2="Fast"}
                """);
        return OptionScanner.scan(shaderSrc);
    }

    @Test
    void ungatedPassReadingGatedTargetThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.ssrRaw]
                format = "rgba16f"
                scale = 0.5
                enabled_if = "SSR_QUALITY == 2"

                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                inputs = ["ssrRaw"]
                outputs = ["builtin.output"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, ssrOptions(), 1920, 1080));
        assertEquals("pass.resolve.inputs", e.key());
        assertTrue(e.reason().contains("ssrRaw"), e.reason());
    }

    @Test
    void passGateImplyingTargetGateValidatesClean() {
        // SSR_QUALITY == 2 implies SSR_QUALITY != 0 at every point of the option's {0,1,2} domain.
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.hiz]
                format = "r32f"
                scale = 1.0
                enabled_if = "SSR_QUALITY != 0"

                [targets.ssrRaw]
                format = "rgba16f"
                scale = 0.5
                enabled_if = "SSR_QUALITY == 2"

                [[pass]]
                name = "ssr_trace_fast"
                type = "fullscreen"
                shader = "shaders/post/ssr_trace.fsh"
                enabled_if = "SSR_QUALITY == 2"
                inputs = ["hiz", "builtin.depth"]
                outputs = ["ssrRaw"]
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, ssrOptions(), 1920, 1080));
    }

    @Test
    void passGateNotImplyingTargetGateThrows() {
        // Enabled at SSR_QUALITY == 1, but the target only exists at == 2: refused with the
        // counterexample named, instead of a silent runner-build failure at render time.
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.ssrRaw]
                format = "rgba16f"
                scale = 0.5
                enabled_if = "SSR_QUALITY == 2"

                [[pass]]
                name = "ssr_trace_fancy"
                type = "fullscreen"
                shader = "shaders/post/ssr_trace.fsh"
                enabled_if = "SSR_QUALITY == 1"
                inputs = ["builtin.depth"]
                outputs = ["ssrRaw"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, ssrOptions(), 1920, 1080));
        assertEquals("pass.ssr_trace_fancy.outputs", e.key());
        assertTrue(e.reason().contains("SSR_QUALITY"), e.reason());
    }

    @Test
    void gatedPassAcrossIndependentOptionsThrows() {
        // Gated on a DIFFERENT option than the target's: SSAO_ENABLED = 1, SSR_QUALITY = 0 is the
        // counterexample point the domain enumeration must find.
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.hiz]
                format = "r32f"
                scale = 1.0
                enabled_if = "SSR_QUALITY != 0"

                [[pass]]
                name = "blur"
                type = "fullscreen"
                shader = "shaders/post/blur.fsh"
                enabled_if = "SSAO_ENABLED"
                inputs = ["hiz"]
                outputs = ["builtin.output"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, ssrOptions(), 1920, 1080));
        assertEquals("pass.blur.inputs", e.key());
    }

    @Test
    void identicalGateStringsValidateClean() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.hiz]
                format = "r32f"
                scale = 1.0
                enabled_if = "SSR_QUALITY != 0"

                [[pass]]
                name = "hiz_build"
                type = "mipchain"
                target = "hiz"
                enabled_if = "SSR_QUALITY != 0"
                inputs = ["builtin.depth"]
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, ssrOptions(), 1920, 1080));
    }

    @Test
    void enabledIfUnknownOptionThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.bloom]
                format = "rgba16f"
                scale = 0.5
                enabled_if = "BLOOM_ENABLED"
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("targets.bloom.enabled_if", e.key());
        assertTrue(e.reason().contains("unknown option"));
        assertTrue(e.reason().contains("BLOOM_ENABLED"));
    }

    // --- Per-pass blend state: fullscreen-only, legal values only. -----------------------------

    @Test
    void unknownBlendValueThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                inputs = ["builtin.depth"]
                outputs = ["builtin.output"]
                blend = "screen"
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.resolve.blend", e.key());
        assertTrue(e.reason().contains("screen"), e.reason());
    }

    @Test
    void blendOnNonFullscreenPassThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "depth_copyback"
                type = "copy"
                inputs = ["builtin.depth"]
                outputs = ["builtin.sceneDepth"]
                blend = "translucent"
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.depth_copyback.blend", e.key());
        assertTrue(e.reason().contains("fullscreen"), e.reason());
    }

    @Test
    void blendOnFullscreenPassValidatesClean() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                inputs = ["builtin.depth"]
                outputs = ["builtin.output"]
                blend = "translucent"
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, Map.of(), 1920, 1080));
    }

    @Test
    void multiplyBlendOnFullscreenPassValidatesClean() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                inputs = ["builtin.depth"]
                outputs = ["builtin.output"]
                blend = "multiply"
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, Map.of(), 1920, 1080));
    }

    @Test
    void sceneDepthCopyPassValidatesClean() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "depth_copyback"
                type = "copy"
                inputs = ["builtin.depth"]
                outputs = ["builtin.sceneDepth"]
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, Map.of(), 1920, 1080));
    }

    @Test
    void sceneDepthOutputOnNonCopyPassThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                inputs = ["builtin.depth"]
                outputs = ["builtin.sceneDepth"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.resolve.outputs", e.key());
    }

    @Test
    void sceneDepthCopyPassWithWrongInputsThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "depth_copyback"
                type = "copy"
                inputs = ["builtin.gAlbedo"]
                outputs = ["builtin.sceneDepth"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.depth_copyback.inputs", e.key());
    }

    @Test
    void mipchainWithoutTargetThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.bloom]
                format = "rgba16f"
                scale = 0.5

                [[pass]]
                name = "bloom_mips"
                type = "mipchain"
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.bloom_mips.target", e.key());
    }

    @Test
    void mipchainUndeclaredTargetThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.bloom]
                format = "rgba16f"
                scale = 0.5

                [[pass]]
                name = "bloom_mips"
                type = "mipchain"
                target = "ghost"
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.bloom_mips.target", e.key());
    }

    // --- Geometry-pass uniqueness is PER SLOT: each slot's declared inputs resolve into that slot's
    // own u_GeomInput0..N bind group, so two passes claiming one slot would leave the second's inputs
    // silently dead. Distinct slots are independent and legal. ---

    @Test
    void twoGeometryPassesClaimingTheSameSlotThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "terrain_opaque"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"

                [[pass]]
                name = "terrain_again"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain2"
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.terrain_again.slot", e.key());
        assertTrue(e.reason().contains("terrain_opaque"), e.reason());
        assertTrue(e.reason().contains("terrain_again"), e.reason());
        assertTrue(e.reason().contains("terrain"), e.reason());
    }

    @Test
    void twoGeometryPassesInDifferentSlotsValidateClean() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "terrain_opaque"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"

                [[pass]]
                name = "terrain_shadow"
                type = "geometry"
                slot = "shadow"
                program = "shaders/shadow"
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, Map.of(), 1920, 1080));
    }

    @Test
    void geometryPassOmittingSlotDefaultsToTerrainAndCollidesWithAnExplicitOne() {
        // An omitted slot is not "unset": it is terrain, so it collides with an explicit terrain
        // pass exactly as two explicit ones would. Guards against the default silently creating a
        // second, invisible claim on the same bind group.
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "implicit_terrain"
                type = "geometry"
                program = "shaders/terrain"

                [[pass]]
                name = "explicit_terrain"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain2"
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.explicit_terrain.slot", e.key());
    }

    @Test
    void unknownGeometrySlotIsRejectedAtParse() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(new java.io.StringReader("""
                        [[pass]]
                        name = "mystery"
                        type = "geometry"
                        slot = "not_a_real_slot"
                        program = "shaders/mystery"
                        """), "graph.toml"));
        assertEquals("pass.mystery.slot", e.key());
        assertTrue(e.reason().contains("not_a_real_slot"), e.reason());
        assertTrue(e.reason().contains("terrain"), "error should list the accepted tokens: " + e.reason());
    }

    @Test
    void slotOnANonGeometryPassIsRejected() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(new java.io.StringReader("""
                        [[pass]]
                        name = "tonemap"
                        type = "fullscreen"
                        slot = "terrain"
                        shader = "shaders/post/tonemap.fsh"
                        outputs = ["builtin.output"]
                        """), "graph.toml"));
        assertEquals("pass.tonemap.slot", e.key());
    }

    @Test
    void singleGeometryPassValidatesClean() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "terrain_opaque"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, Map.of(), 1920, 1080));
    }

    @Test
    void cyclicPassesFixtureThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(resource("cycle/graph.toml"), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertTrue(e.reason().toLowerCase().contains("cycle"));
    }

    @Test
    void enabledIfRuntimeOptionFixtureThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(resource("runtime_in_enabledif/graph.toml"), "graph.toml");
        Map<String, String> shaderSrc = new LinkedHashMap<>();
        shaderSrc.put("shaders/post/ssr_trace.fsh", readResource("runtime_in_enabledif/shaders/post/ssr_trace.fsh"));
        Map<String, PackOption> options = OptionScanner.scan(shaderSrc);
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, options, 1920, 1080));
        assertTrue(e.reason().contains("runtime option"));
    }

    @Test
    void passShaderReferencingVanillaOverridePathThrows() {
        // shaders/vanilla/* files are vanilla core-shader overrides (VanillaShaderOverrides), never
        // a valid pass shader. A pack graph referencing one directly would silently skip the
        // fullscreen-pass preamble splices GraphRunner.rebuild applies to every other pass shader.
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.gAlbedo]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "bogus"
                type = "fullscreen"
                shader = "shaders/vanilla/lightmap.fsh"
                inputs = ["builtin.depth"]
                outputs = ["gAlbedo"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.bogus.shader", e.key());
        assertTrue(e.reason().contains("shaders/vanilla/"), e.reason());
    }

    @Test
    void unknownTargetFormatThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.bad]
                format = "bogus"
                scale = 1.0
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("targets.bad.format", e.key());
    }

    @Test
    void vramReportLabelsEachRowWithItsSizingBasis() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.gAlbedo]
                format = "rgba8"
                scale = 1.0

                [targets.native]
                format = "rgba8"
                scale = 1.0
                basis = "output"
                """), "graph.toml");
        VramReport report = GraphValidator.validate(g, Map.of(), 1280, 720, 1920, 1080);

        String renderRow = report.lines().stream().filter(l -> l.startsWith("gAlbedo")).findFirst().orElseThrow();
        assertTrue(renderRow.contains("1280x720 (render)"), "render-basis row sized off render resolution: " + renderRow);

        String outputRow = report.lines().stream().filter(l -> l.startsWith("native")).findFirst().orElseThrow();
        assertTrue(outputRow.contains("1920x1080 (output)"), "output-basis row sized off output resolution: " + outputRow);
    }

    // --- Water-surface builtins: builtin.waterNormal/builtin.waterDepth resolve like the other
    // engine-owned builtins (sunShadowMap/builtin.depth_opaque), written at the opaque stage HEAD
    // (before builtin.depth_opaque's own mid-finish() capture), so unlike builtin.depth_opaque they
    // carry no geometry-only PassType restriction: any pass type may reference them. -------------

    @Test
    void waterBuiltinsAreRegistered() {
        assertTrue(GraphValidator.BUILTINS.contains("builtin.waterNormal"));
        assertTrue(GraphValidator.BUILTINS.contains("builtin.waterDepth"));
    }

    @Test
    void waterBuiltinsResolveOnAFullscreenPass() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "ssr_trace_water"
                type = "fullscreen"
                shader = "shaders/post/ssr_trace.fsh"
                inputs = ["builtin.waterNormal", "builtin.waterDepth"]
                outputs = ["builtin.output"]
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, Map.of(), 1920, 1080));
    }

    @Test
    void unknownWaterBuiltinThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [[pass]]
                name = "bogus"
                type = "fullscreen"
                shader = "shaders/post/x.fsh"
                inputs = ["builtin.waterFoo"]
                outputs = ["builtin.output"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.bogus.inputs", e.key());
    }

    @Test
    void badTomlFixtureFailsToParse() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(resource("bad_toml/graph.toml"), "graph.toml"));
        assertEquals("graph.toml", e.file());
    }

    @Test
    void fixturePackAndScreensTomlsLoadCleanly() {
        for (String name : List.of("missing_target", "cycle", "runtime_in_enabledif", "bad_toml", "volume_missing_dims")) {
            assertDoesNotThrow(() -> PackTomlLoader.loadMeta(resource(name + "/pack.toml"), "pack.toml"));
            assertDoesNotThrow(() -> PackTomlLoader.loadScreens(resource(name + "/screens.toml"), "screens.toml"));
        }
    }

    @Test
    void volumeTextureMissingWidthHeightFailsToParse() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(resource("volume_missing_dims/graph.toml"), "graph.toml"));
        assertEquals("graph.toml", e.file());
        assertEquals("textures.badVolume", e.key());
    }

    // --- Pack-shipped texture assets ([textures.*]): a pack-supplied static image, not a render
    // target. Resolved by bare name (no builtin. prefix), read-only, no history slot. -----------

    @Test
    void packTextureAsFullscreenInputValidatesClean() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [textures.waterWaveNormal]
                file = "textures/water_wave_normal.png"

                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                inputs = ["builtin.depth", "waterWaveNormal"]
                outputs = ["builtin.output"]
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, Map.of(), 1920, 1080));
    }

    @Test
    void packTextureAsGeometryInputValidatesClean() {
        // A geometry pass sampling a pack texture is always final-for-frame (loaded once at pack
        // activation, never written by any pass).
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [textures.waterWaveNormal]
                file = "textures/water_wave_normal.png"

                [[pass]]
                name = "terrain_opaque"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"
                inputs = ["waterWaveNormal"]
                outputs = ["builtin.output"]
                """), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, Map.of(), 1920, 1080));
    }

    @Test
    void packTextureAsOutputThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [textures.waterWaveNormal]
                file = "textures/water_wave_normal.png"

                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                inputs = ["builtin.depth"]
                outputs = ["waterWaveNormal"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.resolve.outputs", e.key());
        assertTrue(e.reason().contains("read-only"), e.reason());
    }

    @Test
    void packTextureHistorySuffixThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [textures.waterWaveNormal]
                file = "textures/water_wave_normal.png"

                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                inputs = ["waterWaveNormal.history"]
                outputs = ["builtin.output"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("pass.resolve.inputs", e.key());
        assertTrue(e.reason().contains("no history"), e.reason());
    }

    @Test
    void textureNameCollidingWithTargetNameThrows() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.waterWaveNormal]
                format = "rgba8"
                scale = 1.0

                [textures.waterWaveNormal]
                file = "textures/water_wave_normal.png"
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, Map.of(), 1920, 1080));
        assertEquals("textures.waterWaveNormal", e.key());
    }

    private static Reader resource(String relPath) {
        var stream = GraphValidatorTest.class.getResourceAsStream("/packs/" + relPath);
        if (stream == null) throw new IllegalStateException("missing test fixture: " + relPath);
        return new InputStreamReader(stream, StandardCharsets.UTF_8);
    }

    private static String readResource(String relPath) {
        try (var stream = GraphValidatorTest.class.getResourceAsStream("/packs/" + relPath)) {
            if (stream == null) throw new IllegalStateException("missing test fixture: " + relPath);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
