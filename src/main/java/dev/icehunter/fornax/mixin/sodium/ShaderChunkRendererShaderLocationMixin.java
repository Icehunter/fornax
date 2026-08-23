package dev.icehunter.fornax.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.FornaxRenderPasses;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Points terrain pipeline compilation at Fornax's terrain shader instead of the official {@code
 * sodium:blocks/block_layer_opaque} shader source: {@code fornax:blocks/shadow} (the engine's own
 * depth-only shadow shader, unconditionally, regardless of pack-active state) for {@link
 * FornaxRenderPasses#SHADOW}; {@code fornax_runtime:blocks/terrain} (the active pack's own terrain
 * shader, served by {@code RuntimeShaderPack}) while a pack is active ({@link
 * GraphRunner#isActive()}); and {@code fornax:blocks/terrain} (the engine's built-in FALLBACK shader
 * in this mod's own assets) otherwise.
 *
 * <p>The shadow routing is deliberately NOT gated on {@link FornaxRenderState#isActive()} the way
 * the terrain fallback/runtime choice is: {@code fornax:blocks/shadow} is engine-owned minimal
 * plumbing (like the debug blit), not pack content, for v1 -- there is no pack-provided shadow
 * shader to prefer over it, so the SHADOW pass always resolves to the same engine asset whether or
 * not a pack graph is driving the rest of terrain rendering.
 *
 * <p><b>Stock Sodium's shader is NEVER a valid choice in this process</b>: {@code
 * CompactChunkVertexMixin} installs {@code FornaxChunkVertex} via a class-init {@code @Redirect},
 * so every chunk mesh in existence uses Fornax's attribute formats (e.g. RGBA16_UNORM position) --
 * stock {@code block_layer_opaque} declares CompactChunkVertex's packed-uint attributes and fails
 * pipeline compilation against them (live-caught on MoltenVK: "Vertex attribute a_Position(0) of
 * type uint2 cannot be read using MTLAttributeFormatUShort4Normalized", a hard crash at first
 * terrain draw with shaders toggled off). The fallback shader compiles without {@code USE_DEFERRED}
 * (see {@code ShaderChunkRendererConstantsMixin}), so its single forward output matches the
 * single-attachment pipeline state and render pass the other (isActive-gated) mixins leave in place.
 * Covers all four terrain passes (SOLID/CUTOUT/TRANSLUCENT share one shader file, differentiated
 * only by per-pass preprocessor constants; SHADOW gets its own dedicated file).
 *
 * <p>{@code createShader(String, TerrainRenderPass)} calls {@code
 * Identifier.fromNamespaceAndPath(String, String)} exactly three times with the literal {@code
 * "sodium"} namespace -- once for the pipeline's own debug/registry label (built from the pass's
 * existing pipeline location, not the literal shader path -- left alone here), then once each for
 * {@code withVertexShader}/{@code withFragmentShader}, both with the literal path {@code
 * "blocks/block_layer_opaque"}. Ordinal 0 is the label call; ordinals 1 and 2 are the two
 * shader-source calls this mixin redirects.
 */
@Mixin(ShaderChunkRenderer.class)
public class ShaderChunkRendererShaderLocationMixin {
    private static final String FORNAX_RUNTIME_NAMESPACE = "fornax_runtime";
    private static final String FORNAX_NAMESPACE = "fornax";
    private static final String TERRAIN_SHADER_PATH = "blocks/terrain";
    private static final String SHADOW_SHADER_PATH = "blocks/shadow";

    private static Identifier fornax$terrainShader(TerrainRenderPass pass) {
        if (FornaxRenderPasses.isShadow(pass)) {
            return Identifier.fromNamespaceAndPath(FORNAX_NAMESPACE, SHADOW_SHADER_PATH);
        }

        if (!FornaxRenderState.isActive()) {
            return Identifier.fromNamespaceAndPath(FORNAX_NAMESPACE, TERRAIN_SHADER_PATH);
        }

        // Honour the terrain pass's own declared `program` rather than assuming the conventional
        // path, so `program` is a real key instead of documentation. Falls back to the convention
        // when the active pack declares no terrain pass or leaves `program` off -- the pack still
        // has to ship the files, and RuntimeShaderPack failing to resolve them is the loud error.
        String declared = GraphRunner.geometryProgramPath(GeometrySlot.TERRAIN);
        return Identifier.fromNamespaceAndPath(FORNAX_RUNTIME_NAMESPACE,
                declared != null ? declared : TERRAIN_SHADER_PATH);
    }

    @Redirect(
            method = "createShader",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/resources/Identifier;fromNamespaceAndPath(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;",
                    ordinal = 1
            )
    )
    private static Identifier fornax$retargetVertexShader(String namespace, String path, @Local(argsOnly = true) TerrainRenderPass pass) {
        return fornax$terrainShader(pass);
    }

    @Redirect(
            method = "createShader",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/resources/Identifier;fromNamespaceAndPath(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;",
                    ordinal = 2
            )
    )
    private static Identifier fornax$retargetFragmentShader(String namespace, String path, @Local(argsOnly = true) TerrainRenderPass pass) {
        return fornax$terrainShader(pass);
    }
}
