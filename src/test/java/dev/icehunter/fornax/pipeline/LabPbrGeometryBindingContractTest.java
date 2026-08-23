package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import dev.icehunter.fornax.atlas.LabPbrGeometryBindings;
import dev.icehunter.fornax.atlas.LabPbrGeometryBindings.Source;
import dev.icehunter.fornax.pack.GeometrySlot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.model.object.banner.BannerModel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.junit.jupiter.api.Test;

final class LabPbrGeometryBindingContractTest {
    @Test
    void deferredNormalBindingsUseTheSameLinearFilterContract() throws IOException {
        String bindings = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/atlas/LabPbrGeometryBindings.java"));
        String terrain = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/sodium/DefaultChunkRendererTextureBindMixin.java"));
        String draw = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/vanilla/PreparedRenderTypeDeferredMixin.java"));

        String normalSampler = "FilterMode.LINEAR, FilterMode.LINEAR, false";
        // Both active normal bindings must match magnification and minification filters so the
        // one-texel footprint boundary cannot switch filtering behavior between paths.
        assertTrue(bindings.contains(normalSampler),
                "normal/AO/height must stay at the authored base level on this path");
        assertTrue(terrain.contains(normalSampler),
                "terrain normal sampling must use the same base-level contract");
        // _s carries categorical bytes (metal index, porosity/SSS split, emission sentinel) that must
        // never be blended across a mip boundary. Unlike terrain, this binding is read by
        // vanilla-authored entity/block-entity shaders Fornax cannot add an integer-LOD snap to, so
        // the only guarantee available is never reading past level 0 -- mipmapEnable=false clamps
        // SamplerCache's maxLod to 0.0.
        assertTrue(bindings.contains(
                "FilterMode.NEAREST, FilterMode.NEAREST, false"),
                "categorical material bytes must stay level-zero-only on this path");
        assertFalse(bindings.contains(
                "FilterMode.NEAREST, FilterMode.NEAREST, true"),
                "the material sampler must not re-enable its mip chain on this binding");
        assertTrue(draw.contains(
                "binding.normalView(), binding.normalSampler()"));
        assertTrue(draw.contains(
                "binding.materialView(), binding.materialSampler()"));
        assertFalse(bindings.contains("getRepeat(FilterMode.NEAREST)"),
                "the explicit normal sampler must retain its clamp-to-edge contract");
    }

    @Test
    void onlyExactEntityOwnersRouteToDirectSidecars() {
        assertEquals(Source.DIRECT, source(GeometrySlot.ENTITIES,
                "pack", "textures/entity/banner/base.png"));
        assertEquals(Source.DIRECT, source(GeometrySlot.ENTITIES,
                "other", "textures/entity/banner/base.png"));
        assertEquals(Source.NEUTRAL, source(GeometrySlot.ENTITIES,
                "pack", "textures/item/banner.png"));
        assertEquals(Source.NEUTRAL, source(GeometrySlot.ENTITIES,
                "pack", "textures/painting/banner.png"));
        assertEquals(Source.NEUTRAL, source(GeometrySlot.ENTITIES,
                "pack", "textures/entities/not_the_exact_root.png"));
    }

    @Test
    void blockEntitiesUseTheActualBlockAtlasPairRegardlessOfSamplerOwner() {
        assertEquals(Source.BLOCK_ATLAS, source(GeometrySlot.BLOCK_ENTITIES,
                "minecraft", "textures/atlas/blocks.png"));
        assertEquals(Source.BLOCK_ATLAS, source(GeometrySlot.BLOCK_ENTITIES,
                "pack", "textures/entity/chest/normal.png"));
    }

    @Test
    void noOtherGeometrySlotReceivesTheBinding() {
        Identifier entity = id("pack", "textures/entity/banner/base.png");
        assertEquals(Source.NEUTRAL,
                LabPbrGeometryBindings.sourceFor(GeometrySlot.ENTITIES_TRANSLUCENT, entity));
        assertEquals(Source.NEUTRAL,
                LabPbrGeometryBindings.sourceFor(GeometrySlot.BLOCK_ENTITIES_TRANSLUCENT, entity));
        assertEquals(Source.NEUTRAL,
                LabPbrGeometryBindings.sourceFor(GeometrySlot.BANNER_PATTERNS, entity));
        assertEquals(Source.NEUTRAL,
                LabPbrGeometryBindings.sourceFor(GeometrySlot.PARTICLES, entity));
    }

    @Test
    void bannerBaseIsDeferredEntitySolidOnItsExactAtlasWhilePatternsRemainExcluded()
            throws IOException {
        Identifier bannerAtlas = Identifier.withDefaultNamespace(
                "textures/atlas/banner_patterns.png");
        assertEquals(bannerAtlas, Sheets.BANNER_SHEET);
        assertEquals(bannerAtlas, Sheets.BANNER_BASE.atlasLocation());
        assertTrue(bootstrapTargets(BannerModel.class).contains(entitySolidHandle()));
        assertTrue(bootstrapTargets(BannerFlagModel.class).contains(entitySolidHandle()));

        assertEquals(Source.ATLAS,
                LabPbrGeometryBindings.sourceFor(GeometrySlot.ENTITIES, bannerAtlas));
        assertEquals(Source.NEUTRAL,
                LabPbrGeometryBindings.sourceFor(GeometrySlot.BANNER_PATTERNS, bannerAtlas),
                "the forward banner-pattern consumer must not gain sidecar samplers");
    }

    @Test
    void executableLayoutsDeclareBothSamplersOnlyForDeferredEntityConsumers() {
        for (GeometrySlot slot : GeometrySlot.values()) {
            boolean eligible = slot == GeometrySlot.ENTITIES
                    || slot == GeometrySlot.BLOCK_ENTITIES;
            BindGroupLayout deferred = DeferredGeometryPipelines.labPbrBindGroupLayout(slot, false);
            BindGroupLayout forward = DeferredGeometryPipelines.labPbrBindGroupLayout(slot, true);

            assertEquals(eligible, deferred.getSamplers().contains("u_NormalTex"), slot.token());
            assertEquals(eligible, deferred.getSamplers().contains("u_MaterialTex"), slot.token());
            assertFalse(forward.getSamplers().contains("u_NormalTex"), slot.token());
            assertFalse(forward.getSamplers().contains("u_MaterialTex"), slot.token());
        }
    }

    private static Source source(GeometrySlot slot, String namespace, String path) {
        return LabPbrGeometryBindings.sourceFor(slot, id(namespace, path));
    }

    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    private static Handle entitySolidHandle() {
        return new Handle(Opcodes.H_INVOKESTATIC, Type.getInternalName(RenderTypes.class),
                "entitySolid",
                "(Lnet/minecraft/resources/Identifier;)"
                        + "Lnet/minecraft/client/renderer/rendertype/RenderType;",
                false);
    }

    private static List<Handle> bootstrapTargets(Class<?> type) throws IOException {
        List<Handle> targets = new ArrayList<>();
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertTrue(input != null, "missing bytecode for " + type.getName());
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor,
                                                           Handle bootstrapMethodHandle,
                                                           Object... bootstrapMethodArguments) {
                            for (Object argument : bootstrapMethodArguments) {
                                if (argument instanceof Handle handle) {
                                    targets.add(handle);
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return targets;
    }
}
