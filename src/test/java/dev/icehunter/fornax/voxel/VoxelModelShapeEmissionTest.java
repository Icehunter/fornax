package dev.icehunter.fornax.voxel;

import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoxelModelShapeEmissionTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static final BlockAndTintGetter WORLD = BlockAndTintGetter.EMPTY;
    private static BlockState state() { return Blocks.OAK_FENCE.defaultBlockState(); }
    private static VoxelModelShape.Resolver resolver(EmittedModel model) {
        return new VoxelModelShape.Resolver(IndigoRenderer.INSTANCE, unused -> model, unused -> null);
    }
    @Test void customFinalEmitterOverridesDifferentVanillaPartsAndPreservesEveryFenceConnection() {
        for (int mask = 0; mask < 16; mask++) {
            var model = new EmittedModel(VoxelModelShapeTest.fence(mask));
            var actual = resolver(model).resolve(state(), WORLD, new BlockPos(21, 3, -27));
            assertEquals(VoxelModelShape.reconstruct(List.of(VoxelModelShapeTest.part(model.quads))), actual);
            assertEquals(1, model.emissions);
            assertEquals(0, model.collections, "the vanilla parts deliberately describe the wrong geometry");
            assertEquals(new BlockPos(21,3,-27), model.position);
            assertSame(WORLD, model.world);
        }
    }
    @Test void keyedGeometryReusesWithinOneHarvestButNullKeysAlwaysEmitAndPositionsStayIndependent() {
        var model = new EmittedModel(VoxelModelShapeTest.fence(1)); var resolver = resolver(model);
        assertNotNull(resolver.resolve(state(), WORLD, BlockPos.ZERO));
        assertNotNull(resolver.resolve(state(), WORLD, new BlockPos(1,0,0)));
        assertEquals(1, model.emissions);
        model.key = null;
        assertNotNull(resolver.resolve(state(), WORLD, new BlockPos(2,0,0)));
        model.quads = VoxelModelShapeTest.fence(2);
        var changed = resolver.resolve(state(), WORLD, new BlockPos(3,0,0));
        assertEquals(VoxelModelShape.reconstruct(List.of(VoxelModelShapeTest.part(model.quads))), changed);
        assertEquals(3, model.emissions);
        model.key = "different-geometry";
        assertEquals(changed, resolver.resolve(state(), WORLD, new BlockPos(4,0,0)));
        assertEquals(4, model.emissions);
        assertEquals(changed, resolver(model).resolve(state(), WORLD, BlockPos.ZERO));
        assertEquals(5, model.emissions, "a fresh harvest has no old model/atlas cache");
    }
    @Test void keyAndEmissionReceiveIndependentlyReseededRandomAndNoEarlyCull() {
        var model = new EmittedModel(VoxelModelShapeTest.fence(1));
        var pos = new BlockPos(-1024,71,49);
        assertNotNull(resolver(model).resolve(state(), WORLD, pos));
        long expected = RandomSource.create(state().getSeed(pos)).nextLong();
        assertEquals(expected, model.keyRandom); assertEquals(expected, model.emitRandom);
    }
    @Test void coincidentUncertainEmissiveOverlayIsCoveredButUnbackedOrShiftedGeometryIsNot() {
        var model = new EmittedModel(VoxelModelShapeTest.fence(1)); model.overlay = true;
        assertNotNull(resolver(model).resolve(state(), WORLD, BlockPos.ZERO));
        model.quads = List.of(VoxelModelShapeTest.quad(Direction.UP,new org.joml.Vector3f(0,16,0),new org.joml.Vector3f(16,16,16)));
        assertNull(resolver(model).resolve(state(), WORLD, BlockPos.ZERO));
        model.quads = VoxelModelShapeTest.fence(1); model.shiftOverlay = true;
        assertNull(resolver(model).resolve(state(), WORLD, BlockPos.ZERO));
    }
    @Test void nonRectangularFinalEmissionRejectsAValidUnderlyingModelAndCaptureStorageIsBounded() {
        var model = new EmittedModel(VoxelModelShapeTest.fence(1)); model.damage = true;
        assertNull(resolver(model).resolve(state(), WORLD, BlockPos.ZERO));
        model.damage = false; model.repeats = 1000;
        assertNull(resolver(model).resolve(state(), WORLD, BlockPos.ZERO));
    }
    @Test void optionalModelExceptionsFallBackWithoutCachingPartialGeometryOrCatchingSeriousErrors() {
        var model = new EmittedModel(VoxelModelShapeTest.fence(1)); var resolver = resolver(model);
        model.keyFailure = true;
        assertNull(resolver.resolve(state(), WORLD, BlockPos.ZERO));
        model.keyFailure = false; model.emissionFailure = true;
        assertNull(resolver.resolve(state(), WORLD, BlockPos.ZERO));
        model.emissionFailure = false;
        assertNotNull(resolver.resolve(state(), WORLD, BlockPos.ZERO), "partial emission was not cached");
        model.key = null; model.overlay = true;
        var failingSprite = new VoxelModelShape.Resolver(IndigoRenderer.INSTANCE, unused -> model,
                unused -> { throw new IllegalStateException("fixture atlas unavailable"); });
        assertNull(failingSprite.resolve(state(), WORLD, BlockPos.ZERO));
        model.seriousFailure = true;
        assertThrows(AssertionError.class, () -> resolver.resolve(state(), WORLD, BlockPos.ZERO));
    }
    static final class EmittedModel implements BlockStateModel, FabricBlockStateModel {
        List<BakedQuad> quads; Object key = "same-geometry";
        boolean overlay, shiftOverlay, damage, keyFailure, emissionFailure, seriousFailure; int repeats = 1, emissions, collections;
        long keyRandom, emitRandom; BlockPos position; BlockAndTintGetter world;
        EmittedModel(List<BakedQuad> quads) { this.quads = quads; }
        public void collectParts(RandomSource random, List<BlockStateModelPart> result) {
            collections++; result.add(VoxelModelShapeTest.part(VoxelModelShapeTest.cuboid(0,0,0,16,16,16,null)));
        }
        public Material.Baked particleMaterial() { return null; }
        public int materialFlags() { return 0; }
        public Object createGeometryKey(BlockAndTintGetter world, BlockPos pos, BlockState state, RandomSource random) {
            if (keyFailure) throw new IllegalStateException("fixture key unavailable");
            if (seriousFailure) throw new AssertionError("fixture serious failure");
            keyRandom = random.nextLong(); return key;
        }
        private static QuadEmitter fill(QuadEmitter emitter, BakedQuad quad) {
            emitter.clear().chunkLayer(quad.materialInfo().layer()).nominalFace(quad.direction())
                    .atlas(net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas.BLOCK).color(-1,-1,-1,-1);
            for (int v = 0; v < 4; v++) {
                var p = quad.position(v);
                emitter.pos(v,p.x(),p.y(),p.z()).uv(v,net.minecraft.client.model.geom.builders.UVPair.unpackU(quad.packedUV(v)),
                        net.minecraft.client.model.geom.builders.UVPair.unpackV(quad.packedUV(v)));
            }
            return emitter;
        }
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter world, BlockPos pos, BlockState state,
                              RandomSource random, Predicate<Direction> cull) {
            emissions++; this.world = world; this.position = pos.immutable(); emitRandom = random.nextLong();
            assertFalse(cull.test(null)); for (var face : Direction.values()) assertFalse(cull.test(face));
            for (int repeat = 0; repeat < repeats; repeat++) for (var quad : quads) {
                fill(emitter, quad);
                if (damage) emitter.pos(0, quad.position0().x()+1f/32, quad.position0().y(), quad.position0().z());
                emitter.emit();
                if (emissionFailure) throw new IllegalStateException("fixture partial emission failed");
                if (overlay) {
                    fill(emitter, quad).chunkLayer(ChunkSectionLayer.CUTOUT).emissive(true);
                    if (shiftOverlay) for (int i=0;i<4;i++) emitter.pos(i,quad.position(i).x()+1f/16,quad.position(i).y(),quad.position(i).z());
                    emitter.emit();
                }
            }
        }
    }
}
