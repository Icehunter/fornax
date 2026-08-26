package dev.icehunter.fornax.mixin.sodium;
import dev.icehunter.fornax.pipeline.UniformBufferManagerExtension;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pack.layout.PackOptionsBuffer;
import dev.icehunter.fornax.pass.shadow.ShadowFrameState;
import dev.icehunter.fornax.pass.taa.CameraJitter;
import dev.icehunter.fornax.pipeline.PbrSettingsLayout;
import dev.icehunter.fornax.pipeline.PreviousFrameCameraTransform;
import dev.icehunter.fornax.voxel.EmitterFrameState;
import dev.icehunter.fornax.voxel.VoxelWindow;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * Widens Sodium's per-frame uniform buffer from 256 to 608 bytes and adds a second, Fornax-only
 * uniform buffer for PBR settings, so the Java-side layout matches the {@code u_Globals}/{@code
 * u_PbrSettings} struct Fornax's overridden {@code globals.glsl} declares -- both Fornax's own
 * shaders and Sodium's own unmodified terrain shaders compile against that same override, so the
 * buffer Sodium uploads must carry the extra fields or every terrain draw reads garbage past byte
 * 256.
 *
 * <p><b>Why not a full {@code @Overwrite}:</b> every difference here is a pure, tail-appended
 * addition, not a restructuring:
 * <ul>
 *   <li>The constructor's only change is the {@code MappableRingBuffer} size literal (256 -> 576);
 *   trivially isolated with {@code @ModifyArg} rather than duplicating the whole constructor body
 *   (which independently derives {@code maxRegions} from render distance/world height -- logic this
 *   mixin has no reason to touch or risk drifting from upstream).</li>
 *   <li>{@code update(...)}'s {@code Std140Builder} chain is a strict fluent append: upstream's
 *   chain writes nine fields ending in a float and an int, terminated by {@code get()}, at exactly
 *   184 written bytes -- the 256-byte figure above is the ring buffer's per-slot capacity, not how
 *   much of it that chain writes. {@code GlobalUniformsWriteMixin} adds fourteen more
 *   {@code put*} calls (two mat4 previous-frame matrices, two jitter vec2s, one inverse
 *   projection*modelView mat4, one sun/moon light view-projection mat4 -- {@code u_SunViewProj},
 *   one ivec4 voxel window -- {@code u_VoxelWindow}, one vec3 camera position -- {@code
 *   u_CameraAbs}, then four vec4s of sky-tail data -- {@code u_SkyColor}/{@code u_SunriseColor}/
 *   {@code u_SkyCelestial}/{@code u_SkyState} -- then one vec4, {@code u_WaterState} (Water Round C
 *   Task 4), then one vec4, {@code u_ShadowMapParams} (the shadow radial-distortion bias), then one
 *   final vec4, {@code u_CameraSkyLight} (camera-block vanilla sky-light level, cave/border-fog
 *   enclosure round), ending the block at byte 608)
 *   immediately before that same terminal {@code .get()}. {@code Std140Builder}'s {@code put*}
 *   methods mutate and return {@code this}, so a {@code @WrapOperation} around that one terminal
 *   {@code .get()} invocation can append the extra fields to the same builder instance before
 *   delegating to the real {@code get()} -- with zero risk of the two chains silently diverging on
 *   any future upstream change to the first nine fields, since this mixin never re-states them.</li>
 *   <li>{@code prepareFrame()}/{@code updatePbrSettings()}/{@code getPbrSettingsBuffer()}: the PBR
 *   settings buffer's own once-per-frame flag and accessors are entirely new state with no upstream
 *   equivalent to conflict with -- added as {@code @Unique} members plus a {@code @Inject(RETURN)}
 *   tail on the constructor (to allocate the new {@code MappableRingBuffer}) and on {@code
 *   prepareFrame()} (to reset the new flag alongside the existing one).</li>
 * </ul>
 * Verified against Sodium mc26.2-0.9.0 (bf93ed83); no Sodium source is reproduced here.
 */
@Mixin(UniformBufferManager.class)
public abstract class UniformBufferManagerMixin implements UniformBufferManagerExtension {
    @Unique
    private MappableRingBuffer fornax$pbrSettingsData;

    @Unique
    private boolean fornax$hasUpdatedPbrSettingsThisFrame;

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/DynamicUniformStorage;<init>(Ljava/lang/String;II)V"
            ),
            index = 1
    )
    private int fornax$widenUniformBufferSize(int originalSize) {
        // The per-frame globals live in vanilla's DynamicUniformStorage ring (Sodium 0.9.1), so
        // this widens that storage's per-block size (arg index 1 of (String,II)) rather than a
        // MappableRingBuffer size literal -- same 800-byte u_Globals layout (184 official bytes +
        // the 616-byte Fornax tail, sky tail + Water Round C Task 4's
        // u_WaterState tail vec4 + the shadow-acne fix round's u_ShadowMapParams tail vec4 + the
        // cave/border-fog camera-sky-light round's u_CameraSkyLight tail vec4 + the TAAU
        // jitter-immunity round's u_InvProjModelViewNoJitter tail mat4 + u_FrameState + u_HeldLight
        // + the weather round's bob-free u_WeatherAnchor tail vec4 + the water-motion-vector round's
        // u_CameraDelta tail vec4 + the four local-actor ABI vec4s included).
        return 800;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fornax$initPbrSettingsBuffer(net.minecraft.client.multiplayer.ClientLevel level, int renderDistance, CallbackInfo ci) {
        this.fornax$pbrSettingsData = new MappableRingBuffer((Supplier<String>) () -> "Fornax PBR settings buffer",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, PbrSettingsLayout.SIZE_BYTES);
        this.fornax$hasUpdatedPbrSettingsThisFrame = false;
    }

    @Inject(method = "prepareFrame", at = @At("RETURN"))
    private void fornax$resetPbrSettingsFrameFlag(CallbackInfo ci) {
        this.fornax$hasUpdatedPbrSettingsThisFrame = false;
    }

    // The Std140Builder tail-append (previous-frame matrices, jitter, u_InvProjModelView,
    // u_SunViewProj, u_VoxelWindow, u_CameraAbs) belongs in GlobalUniforms's write(ByteBuffer),
    // not here, because Sodium 0.9.1's std140 chain lives entirely in that record -- see
    // GlobalUniformsWriteMixin, which carries the identical append at the identical anchor there.

    /**
     * Re-uploads the PBR settings uniform block. Cheap (tens of bytes), so simplest to re-upload
     * every frame alongside {@code update(...)} rather than tracking dirty state. Guarded to run at
     * most once per frame, mirroring {@code update(...)}'s own {@code hasUpdatedThisFrame} guard --
     * {@code renderLayer} (see {@code SodiumWorldRendererRenderLayerMixin}) calls this once per pass
     * (SOLID/CUTOUT/TRANSLUCENT), and rotating the ring buffer more than once a frame would race the
     * GPU's consumption of the previous pass's mapping.
     *
     * <p>Every value is the active pack's own runtime option, not an engine {@code FornaxSettings}
     * field -- terrain is a DEFERRED geometry-slot shader, so it binds Sodium's terrain bind group
     * and never the generic {@code u_PackOptions} block {@code FullscreenPassRunner} wires into
     * fullscreen passes (see {@code GraphRunner.rebuild}'s own doc comment on that); this small
     * {@code u_PbrSettings} block is the delivery mechanism instead, Java-fed from
     * {@link GraphRunner#optionsBuffer()}. Falls back to per-member defaults with no pack active (or
     * before its options buffer exists yet), so PBR lighting looks identical to today's build even
     * with shaders off.
     *
     * <p><b>The write is a LOOP over {@link PbrSettingsLayout#MEMBERS}, and that is the point.</b>
     * A hand-written {@code .putFloat(...)} chain paired with a hand-written member list in the
     * pack's {@code terrain.fsh}, held in step only by a comment, is fragile: std140 matches the
     * two POSITIONALLY, so a name added to one side only compiles cleanly and silently reads its
     * neighbour's float -- no validation layer can see that, because both sides are individually
     * well-formed. Iterating the shared list removes the Java half of the drift by construction:
     * there is no second ordering here to disagree with. The GLSL half is pinned by
     * {@code PbrSettingsLayoutTest} / {@code PlaguePackLoadsTest}, which parse the block out of the
     * shader source and assert it against that same list.
     */
    @Override
    public void updatePbrSettings() {
        if (this.fornax$hasUpdatedPbrSettingsThisFrame) {
            return;
        }
        this.fornax$hasUpdatedPbrSettingsThisFrame = true;

        PackOptionsBuffer options = GraphRunner.optionsBuffer();

        this.fornax$pbrSettingsData.rotate();

        try (var data = this.fornax$pbrSettingsData.currentBuffer().map(false, true)) {
            Std140Builder builder = Std140Builder.intoBuffer(data.data());
            for (PbrSettingsLayout.Member member : PbrSettingsLayout.MEMBERS) {
                builder.putFloat(options != null
                        ? options.get(member.option(), member.fallback())
                        : member.fallback());
            }
            builder.get();
        }
    }

    @Override
    public GpuBuffer getPbrSettingsBuffer() {
        return this.fornax$pbrSettingsData.currentBuffer();
    }
}
