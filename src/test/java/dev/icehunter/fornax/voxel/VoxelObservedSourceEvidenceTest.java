package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelObservedSourceEvidenceTest {
    private static final List<BakedQuad> CUBE = VoxelModelShapeTest.cuboid(0, 0, 0, 16, 16, 16, null);
    private static final MaterialSourceIndex.Summary POSITIVE = new MaterialSourceIndex.Summary(0, 1, 0, 1, 0, 0xfeffffff);
    private static final MaterialSourceIndex.Summary ZERO = new MaterialSourceIndex.Summary(0, 1, 1, 0, 0, 0);

    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void visibleEmittedFaceSurvivesVariantPublicationWithFiveCulledDirections() {
        try (var pixels = new VoxelFaceAppearanceTest.Pixels()) {
            var sprite = new VoxelFaceAppearanceTest.Sprite(pixels);
            var index = new MaterialSourceIndex(Map.of(sprite, POSITIVE));
            var appearance = capture(sprite, index, false);
            assertEquals(0x05ffffff, appearance.words()[7]);
            assertNotNull(appearance.sourceFaces(index.generation()));
            var data = variant(appearance, index.generation(), 15, ZERO);
            assertEquals(2, data.sourceEvidence().supportedMask(2));
            assertEquals(2, data.sourceEvidence().authoredMask(2));
            assertEquals(61, data.sourceEvidence().unknownMask(2)); // The other five direction bits.
            var bytes = publish(data, index.generation());
            assertEquals(1, VoxelSourceWindowTest.word(bytes, 2));
            assertEquals(0, VoxelSourceWindowTest.word(bytes, VoxelSourceWindow.CELL_BASE + 4)
                    & VoxelSourceWindow.UNKNOWN_SOURCE);
        }
    }

    @Test void changedEmittedSpritesReplaceBothPositiveAndZeroFallbackClaims() {
        try (var pixels = new VoxelFaceAppearanceTest.Pixels()) {
            var positiveSprite = new VoxelFaceAppearanceTest.Sprite(pixels, 0);
            var zeroSprite = new VoxelFaceAppearanceTest.Sprite(pixels, 2);
            var index = new MaterialSourceIndex(Map.of(positiveSprite, POSITIVE, zeroSprite, ZERO));
            for (var actualSprite : List.of(positiveSprite, zeroSprite)) {
                boolean emits = actualSprite == positiveSprite;
                var appearance = capture(actualSprite, index, false);
                var data = variant(appearance, index.generation(), 0, emits ? ZERO : POSITIVE);
                assertEquals(emits ? 2 : 0, data.sourceEvidence().eligibleMask(2));
                assertEquals(emits ? 1 : 0, VoxelSourceWindowTest.word(publish(data, index.generation()), 2));
            }
        }
    }

    @Test void staleAtlasEvidenceCannotSurviveEqualUvWordsOrAChangedGeneration() {
        try (var pixels = new VoxelFaceAppearanceTest.Pixels()) {
            var sprite = new VoxelFaceAppearanceTest.Sprite(pixels);
            var first = new MaterialSourceIndex(Map.of(sprite, POSITIVE));
            var second = new MaterialSourceIndex(Map.of(sprite, ZERO));
            var before = capture(sprite, first, false);
            var after = capture(sprite, second, false);
            assertTrue(java.util.Arrays.equals(before.words(), after.words()));
            assertNotEquals(before, after, "Generation and source facts participate in capture identity");
            assertNull(before.sourceFaces(second.generation()));
            var data = variant(before, second.generation(), 15, POSITIVE);
            assertEquals(15, data.sourceEvidence().intrinsicEmission(2));
            assertEquals(63, data.sourceEvidence().unknownMask(2));
            assertEquals(0, data.sourceEvidence().eligibleMask(2));
            assertEquals(0, VoxelSourceWindowTest.word(publish(data, second.generation()), 2));
        }
    }

    @Test void unsupportedOverlayCannotPoisonAnIndependentEmittingDirection() {
        try (var pixels = new VoxelFaceAppearanceTest.Pixels()) {
            var sprite = new VoxelFaceAppearanceTest.Sprite(pixels);
            var index = new MaterialSourceIndex(Map.of(sprite, POSITIVE));
            var appearance = capture(sprite, index, true);
            assertEquals(VoxelFaceTexture.OPAQUE_COVERAGE, appearance.words()[14]);
            var data = variant(appearance, index.generation(), 15, POSITIVE);
            assertEquals(2, data.sourceEvidence().eligibleMask(2));
            assertEquals(61, data.sourceEvidence().unknownMask(2));
            var bytes = publish(data, index.generation());
            assertEquals(1, VoxelSourceWindowTest.word(bytes, 2));
            assertEquals(0, VoxelSourceWindowTest.word(bytes, VoxelSourceWindow.CELL_BASE + 4)
                    & VoxelSourceWindow.UNKNOWN_SOURCE);
        }
    }

    @Test void intrinsicMissingMapFallbackNeedsKnownEmittedGeometry() {
        try (var pixels = new VoxelFaceAppearanceTest.Pixels()) {
            var sprite = new VoxelFaceAppearanceTest.Sprite(pixels);
            var index = new MaterialSourceIndex(Map.of(sprite,
                    MaterialSourceIndex.unavailable(MaterialSourceIndex.MISSING_MAP)));
            var known = variant(capture(sprite, index, false), index.generation(), 15, ZERO);
            assertEquals(2, known.sourceEvidence().eligibleMask(2));
            assertEquals(2, known.sourceEvidence().missingMask(2));
            assertEquals(1, VoxelSourceWindowTest.word(publish(known, index.generation()), 2));
            var unknown = variant(VoxelFaceAppearance.UNAVAILABLE.resolve(layer -> -1), index.generation(), 15, POSITIVE);
            assertEquals(15, unknown.sourceEvidence().intrinsicEmission(2));
            assertFalse(unknown.sourceEvidence().knownZeroSource(2));
            assertEquals(0, unknown.sourceEvidence().eligibleMask(2));
            assertEquals(0, VoxelSourceWindowTest.word(publish(unknown, index.generation()), 2));
        }
    }

    @Test void partialResolvedBoundaryCannotAdmitFullCellAppearanceSourceProof() {
        try (var pixels = new VoxelFaceAppearanceTest.Pixels()) {
            var sprite = new VoxelFaceAppearanceTest.Sprite(pixels);
            var index = new MaterialSourceIndex(Map.of(sprite, POSITIVE));
            var appearance = capture(sprite, index, false);
            var boundary = new VoxelBoundaryCapture.Boundary(VoxelShapeKind.PARTIAL,
                    List.of(new VoxelShapeClassifier.PackedBox(0, 0, 0, 16, 8, 16)),
                    appearance.words(), appearance.colors(), false, new float[4], 0);
            var data = variant(appearance, index.generation(), 15, POSITIVE, boundary);
            assertEquals(VoxelShapeKind.PARTIAL, data.palette().entries().get(2).shapeKind());
            assertEquals(63, data.sourceEvidence().unknownMask(2));
            assertEquals(0, VoxelSourceWindowTest.word(publish(data, index.generation()), 2));
        }
    }

    @Test void equalRenderedWordsKeepDifferentSourceProofsSeparateAndDeduplicateRepeats() {
        try (var pixels = new VoxelFaceAppearanceTest.Pixels()) {
            var sprite = new VoxelFaceAppearanceTest.Sprite(pixels);
            var firstIndex = new MaterialSourceIndex(Map.of(sprite, POSITIVE));
            var nextIndex = new MaterialSourceIndex(Map.of(sprite, ZERO));
            var first = capture(sprite, firstIndex, false);
            var next = capture(sprite, nextIndex, false);
            var base = new SectionPalette.Entry(VoxelShapeKind.FULL, List.of(), first.colors(), 0, false, 0,
                    false, new float[4], 0, FaceSealResolver.ALL, first.words());
            var entries = new ArrayList<>(List.of(base));
            var variants = new VoxelPaletteShapes(entries, unused -> {});
            assertEquals(1, variants.observed(0, null, first), "Equal render bytes cannot prove equal source metadata");
            assertEquals(1, variants.observed(0, null, first));
            assertEquals(2, variants.observed(0, null, next));
            assertEquals(2, variants.observed(0, null, next));
            assertEquals(3, entries.size());
            var full = new ArrayList<>(Collections.nCopies(SectionHarvester.maxPaletteEntries(), base));
            var capped = new VoxelPaletteShapes(full, unused -> {});
            assertEquals(0, capped.observed(0, null, first));
            assertTrue(capped.overflowed());
            for (int face = 0; face < 6; face++)
                assertEquals(0, full.getFirst().faceTextureWords()[face * VoxelFaceTexture.FACE_WORDS] & (1 << 24));
        }
    }

    private static VoxelFaceAppearance.Resolved capture(VoxelFaceAppearanceTest.Sprite sprite,
                                                       MaterialSourceIndex index, boolean overlay) {
        var capture = new VoxelBoundaryCapture.Capture(quad -> sprite, -1, List.of(), index);
        var emitter = IndigoRenderer.INSTANCE.quadEmitter(quad -> {});
        VoxelBoundaryCapture.observe(emitter, face -> face != Direction.UP && (!overlay || face != Direction.NORTH), early -> {
            for (var quad : CUBE) {
                emitSprite(emitter, quad, ChunkSectionLayer.SOLID, -1, sprite);
                if (overlay && quad.direction() == Direction.NORTH)
                    emitSprite(emitter, quad, ChunkSectionLayer.CUTOUT, 1, sprite);
            }
        }, capture);
        return capture.appearance().resolve(layer -> -1);
    }

    private static void emitSprite(net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter emitter,
                                   BakedQuad quad, ChunkSectionLayer layer, int tint,
                                   VoxelFaceAppearanceTest.Sprite sprite) {
        VoxelFaceAppearanceTest.fill(emitter, quad, layer, tint);
        for (int vertex = 0; vertex < 4; vertex++)
            emitter.uv(vertex, sprite.getU0() + emitter.u(vertex) * (sprite.getU1() - sprite.getU0()),
                    sprite.getV0() + emitter.v(vertex) * (sprite.getV1() - sprite.getV0()));
        emitter.emit();
    }

    /** Uses the same source callback and palette producer as SectionHarvester's live-model seam. */
    private static SectionHarvester.Result variant(VoxelFaceAppearance.Resolved appearance, long generation,
                                                   int intrinsic, MaterialSourceIndex.Summary fallback) {
        return variant(appearance, generation, intrinsic, fallback, null);
    }

    private static SectionHarvester.Result variant(VoxelFaceAppearance.Resolved appearance, long generation,
                                                   int intrinsic, MaterialSourceIndex.Summary fallback,
                                                   VoxelBoundaryCapture.Boundary boundary) {
        var fixture = VoxelEmitterPoolTest.result(generation, 0);
        var entries = new ArrayList<>(fixture.palette().entries());
        var evidence = new VoxelSourceEvidence.Builder();
        evidence.add(false, 0, List.of());
        evidence.add(true, intrinsic, Collections.nCopies(6, fallback));
        var variants = new VoxelPaletteShapes(entries, evidence::copy,
                (base, observed) -> evidence.copyObserved(base,
                        observed == null ? null : observed.sourceFaces(generation), generation));
        int entry = variants.observed(1, boundary, appearance);
        assertEquals(2, entry);
        var cells = fixture.paletteIndices().clone();
        cells[0] = (byte) entry;
        evidence.addCell(true, entry);
        return new SectionHarvester.Result(cells, new SectionPalette(entries), fixture.lightmap(),
                fixture.sourceSummary(), fixture.harvestGeneration(), evidence.finish(false));
    }

    private static java.nio.ByteBuffer publish(SectionHarvester.Result data, long generation) {
        var window = new VoxelSourceWindow();
        window.reset(5, generation);
        window.commit(0, data, new VoxelSectionState.Snapshot(0, 0, 0, 5, 1, 1));
        return window.preparePublication().bytes();
    }
}
