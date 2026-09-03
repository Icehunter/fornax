package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.atlas.BlockAtlasView;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pass.water.WaterSurfaceManager;
import dev.icehunter.fornax.pipeline.CelestialSprites;
import dev.icehunter.fornax.pipeline.GBuffer;
import dev.icehunter.fornax.pipeline.GBufferManager;
import dev.icehunter.fornax.pipeline.NoiseTexture;
import dev.icehunter.fornax.pipeline.OpaqueDepth;
import dev.icehunter.fornax.atlas.MaterialMapAtlas;
import dev.icehunter.fornax.atlas.NormalMapAtlas;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Resolves a {@code PassSpec} input/output reference string -- {@code builtin.*}, a declared
 * target's name, or {@code <target>.history} -- to the real GPU resource it names, for the
 * runners in this package. The G-buffer builtins ({@code builtin.gNormal/gAlbedo/
 * gMaterial/gAo/gMotion/depth}) are resolved against {@link GBufferManager}'s live instance exactly like the
 * hardcoded pipeline's own passes read it directly; {@code builtin.output} is always
 * {@code Minecraft.getInstance().gameRenderer.mainRenderTarget()}; {@link ShadowMapManager#TARGET}
 * ("sunShadowMap", not {@code builtin.}-prefixed -- it is engine-owned exactly like sceneHistory,
 * not a G-buffer attachment) resolves against {@link ShadowMapManager}'s live instance the same
 * nullable way. {@code builtin.noise} resolves against {@link NoiseTexture}'s lazy, once-per-
 * session static holder -- unlike every other builtin here it is never null once a GPU device
 * exists (see that class's doc), and it carries an extra bind-site contract {@link
 * FullscreenPassRunner} enforces by name: LINEAR + REPEAT, not the NEAREST + CLAMP_TO_EDGE every
 * other input gets. A pack-declared {@code [textures.*]} asset (e.g. {@code waterWaveNormal}) --
 * bare name, no {@code builtin.} prefix, since it is pack-supplied content, not engine-generated --
 * resolves against {@link GraphRunner#packTextureRegistry()}'s live instance the same nullable way,
 * sharing {@code builtin.noise}'s LINEAR + REPEAT bind-site contract (see {@link
 * PackTextureRegistry#isDeclared} and {@link FullscreenPassRunner}'s sampler special-case).
 * {@link OpaqueDepth#NAME} ({@code builtin.depth_opaque}) resolves against {@link
 * GraphRunner#opaqueDepth()}'s live instance -- an engine-owned, sampleable D32 copy of the opaque
 * G-buffer depth, captured once per frame at the finish-opaque boundary; unlike the G-buffer's own
 * live depth attachment (bound for depth-testing during translucent draws), this copy is always
 * safe to sample. {@link WaterSurfaceManager#NORMAL_NAME}/{@link WaterSurfaceManager#DEPTH_NAME}
 * ({@code builtin.waterNormal}/{@code builtin.waterDepth}) resolve against {@link
 * WaterSurfaceManager}'s live instance the same nullable way as the shadow map -- both are written
 * at the OPAQUE stage HEAD (before {@code OpaqueDepth}'s own mid-{@code finish()} capture), so
 * unlike {@code builtin.depth_opaque} they carry no {@code PassType} restriction.
 *
 * <p>A {@code mipchainTargets} map is threaded through separately from {@link TargetRegistry}:
 * {@link MipchainRunner} owns its own multi-level texture independently of the registry (a pack
 * target referenced by a {@code mipchain} pass's own {@code target} needs per-mip-level views for
 * its seed/reduce loop that a plain single-level {@link TargetInstance} doesn't model), so any
 * other pass reading that target by name (e.g. an SSR-trace-shaped pass sampling the full Hi-Z
 * chain) is resolved here first against that map before falling back to the registry.
 *
 * <p>A {@link PassType#CONSOLIDATE} pass's output resolves against {@link
 * GraphRunner#consolidateTargets()}, a static accessor like {@link
 * GraphRunner#packTextureRegistry()}/{@link GraphRunner#opaqueDepth()}, not a threaded parameter:
 * unlike mipchain's per-level seed/reduce loop, every consumer here is a plain
 * {@code sampler2DArray} read with no per-layer state to thread through.
 */
final class GraphInputResolver {
    private GraphInputResolver() {
    }

    static GpuTextureView resolveView(String ref, TargetRegistry registry, Map<String, MipchainRunner> mipchainTargets) {
        GpuTextureView builtin = resolveBuiltinView(ref);
        if (builtin != null) {
            return builtin;
        }

        PackTextureRegistry textures = GraphRunner.packTextureRegistry();
        if (textures != null && textures.isDeclared(ref)) {
            GpuTextureView view = textures.getView(ref);
            if (view == null) {
                throw new IllegalStateException("Fornax graph: pack texture '" + ref + "' is declared but not yet loaded");
            }
            return view;
        }

        String base = ref.endsWith(".history") ? ref.substring(0, ref.length() - ".history".length()) : ref;
        boolean wantsHistory = !base.equals(ref);

        MipchainRunner mip = mipchainTargets.get(base);
        if (mip != null) {
            return mip.fullChainView();
        }

        ConsolidateRunner consolidate = GraphRunner.consolidateTargets().get(base);
        if (consolidate != null) {
            return consolidate.fullArrayView();
        }

        TargetInstance instance = registry.get(base);
        if (instance == null) {
            throw new IllegalStateException("Fornax graph: input '" + ref + "' resolved to no allocated target");
        }
        return wantsHistory ? instance.historyView() : instance.view();
    }

    static GpuTexture resolveTexture(String ref, TargetRegistry registry) {
        GpuTexture builtin = resolveBuiltinTexture(ref);
        if (builtin != null) {
            return builtin;
        }

        PackTextureRegistry textures = GraphRunner.packTextureRegistry();
        if (textures != null && textures.isDeclared(ref)) {
            GpuTexture texture = textures.getTexture(ref);
            if (texture == null) {
                throw new IllegalStateException("Fornax graph: pack texture '" + ref + "' is declared but not yet loaded");
            }
            return texture;
        }

        String base = ref.endsWith(".history") ? ref.substring(0, ref.length() - ".history".length()) : ref;
        boolean wantsHistory = !base.equals(ref);

        TargetInstance instance = registry.get(base);
        if (instance == null) {
            throw new IllegalStateException("Fornax graph: reference '" + ref + "' resolved to no allocated target");
        }
        return wantsHistory ? instance.historyTexture() : instance.texture();
    }

    @Nullable
    private static GpuTextureView resolveBuiltinView(String ref) {
        GBuffer gbuffer = GBufferManager.getInstance();
        return switch (ref) {
            case "builtin.depth" -> gbuffer == null ? null : gbuffer.getDepthView();
            case "builtin.gNormal" -> gbuffer == null ? null : gbuffer.getNormalView();
            case "builtin.gAlbedo" -> gbuffer == null ? null : gbuffer.getAlbedoView();
            case "builtin.gMaterial" -> gbuffer == null ? null : gbuffer.getMaterialView();
            case "builtin.gAo" -> gbuffer == null ? null : gbuffer.getAoView();
            case "builtin.gMotion" -> gbuffer == null ? null : gbuffer.getMotionView();
            case "builtin.output" -> mainRenderTarget().getColorTextureView();
            case "builtin.celestials" -> CelestialSprites.atlasView();
            case "builtin.blockAtlas" -> BlockAtlasView.view();
            // Sprite rectangles, indexed by the sprite ID a terrain vertex carries in a_Position.w.
            // Refreshed on access as sprites are interned during meshing, so a pass reading it early
            // in world load sees whatever has registered so far rather than a stale snapshot.
            case "builtin.spriteBounds" -> dev.icehunter.fornax.pipeline.SpriteBoundsTexture.view();
            // Each sprite's REAL labPBR height range (min, max), so a pack can decide whether to
            // trace the nominal quarter-block depth or rescale to what the texture actually uses.
            case "builtin.spriteHeightRange" -> dev.icehunter.fornax.pipeline.SpriteBoundsTexture.rangeViewOrNull();
            // Vanilla's own light-colour LUT, indexed by (block light, sky light). It already encodes
            // time of day, weather, dimension and night vision, so a pack sampling it gets vanilla's
            // exact light response for free instead of approximating it with hand-picked constants.
            case "builtin.lightmap" -> Minecraft.getInstance().gameRenderer.levelLightmap();
            // The labPBR atlases Fornax stitches alongside the block atlas. Null until a resource
            // reload has built them (or when the resource pack ships no _n/_s textures at all), which
            // the caller already treats as "not resolvable this frame" rather than an error.
            case "builtin.normalAtlas" -> {
                NormalMapAtlas atlas = NormalMapAtlas.getInstance();
                yield atlas == null ? null : atlas.getTextureView();
            }
            case "builtin.materialAtlas" -> {
                MaterialMapAtlas atlas = MaterialMapAtlas.getInstance();
                yield atlas == null ? null : atlas.getTextureView();
            }
            case "builtin.noise" -> NoiseTexture.getView();
            case OpaqueDepth.NAME -> GraphRunner.opaqueDepth().getView();
            // Same texture/view for both pack-visible names -- see ShadowMapManager.RAW_TARGET's own
            // doc for why a second name exists (a different sampler downstream, not a different
            // resource here).
            case ShadowMapManager.TARGET, ShadowMapManager.RAW_TARGET -> ShadowMapManager.getView();
            case WaterSurfaceManager.NORMAL_NAME -> WaterSurfaceManager.getNormalView();
            case WaterSurfaceManager.DEPTH_NAME -> WaterSurfaceManager.getDepthView();
            default -> null;
        };
    }

    @Nullable
    private static GpuTexture resolveBuiltinTexture(String ref) {
        GBuffer gbuffer = GBufferManager.getInstance();
        return switch (ref) {
            case "builtin.depth" -> gbuffer == null ? null : gbuffer.getDepthTexture();
            case "builtin.gNormal" -> gbuffer == null ? null : gbuffer.getNormalTexture();
            case "builtin.gAlbedo" -> gbuffer == null ? null : gbuffer.getAlbedoTexture();
            case "builtin.gMaterial" -> gbuffer == null ? null : gbuffer.getMaterialTexture();
            case "builtin.gAo" -> gbuffer == null ? null : gbuffer.getAoTexture();
            case "builtin.gMotion" -> gbuffer == null ? null : gbuffer.getMotionTexture();
            case "builtin.output" -> mainRenderTarget().getColorTexture();
            case "builtin.celestials" -> CelestialSprites.atlasTexture();
            case "builtin.blockAtlas" -> BlockAtlasView.texture();
            // The bounds table is only ever sampled, never a copy source or mipchain target, and its
            // texture is created lazily inside SpriteBoundsTexture. Nothing needs the raw handle.
            case "builtin.spriteBounds" -> null;
            case "builtin.spriteHeightRange" -> null;
            // Only the view is reachable through GameRenderer; nothing needs the raw texture handle
            // for the lightmap (it is never a copy source or a mipchain target), so this stays null
            // rather than reaching further into vanilla for a handle with no consumer.
            case "builtin.lightmap" -> null;
            case "builtin.normalAtlas" -> {
                NormalMapAtlas atlas = NormalMapAtlas.getInstance();
                yield atlas == null ? null : atlas.getTexture();
            }
            case "builtin.materialAtlas" -> {
                MaterialMapAtlas atlas = MaterialMapAtlas.getInstance();
                yield atlas == null ? null : atlas.getTexture();
            }
            case "builtin.noise" -> NoiseTexture.getTexture();
            case OpaqueDepth.NAME -> GraphRunner.opaqueDepth().getTexture();
            case ShadowMapManager.TARGET, ShadowMapManager.RAW_TARGET -> ShadowMapManager.getTexture();
            case WaterSurfaceManager.NORMAL_NAME -> WaterSurfaceManager.getNormalTexture();
            case WaterSurfaceManager.DEPTH_NAME -> WaterSurfaceManager.getDepthTexture();
            default -> null;
        };
    }

    private static RenderTarget mainRenderTarget() {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }
}
