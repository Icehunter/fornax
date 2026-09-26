package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The player-mirror MRT: a half-resolution copy of three of the five entities G-buffer
 * attachments (normal, albedo plus skylight, material plus blocklight) plus a matching depth
 * target. A dedicated engine pass ({@code PlayerMirrorCaster}) draws the player's reflected
 * geometry into it.
 *
 * <p>Each slot keeps its own instance ({@link #forSlot}), not a single static holder: {@link
 * GeometrySlot#PLAYER_MIRROR} (the floor), {@link GeometrySlot#PLAYER_MIRROR_X} and {@link
 * GeometrySlot#PLAYER_MIRROR_Z} are three independent MRT families, each claimable on its own and
 * each needing its own textures. Keying every static field by base pipeline alone would hand the
 * floor's targets to a wall pass reading its builtins: an empty or wrong reflection with no error
 * anywhere. An instance is created lazily per slot and lives for the session; nothing removes one
 * from the registry, since the set of slots is fixed at three and an unclaimed instance sitting at
 * width/height -1 costs nothing.
 *
 * <p>No motion and no AO lane: the mirror is composited fresh from a single still frame each time
 * it is sampled, so it has no history to reproject against. Its resolve reads AO from the sampling
 * pixel's G-buffer, not from the mirror's, so the mirror needs no AO lane of its own.
 *
 * <p>Half resolution because the marched reflection samples this MRT once per covered receiver
 * pixel in screen space, under the planar (or, for a wall, per-axis) reflection identity. A
 * full-resolution self-reflection would double the entities' draw and fill-rate cost for detail
 * the final composite already loses in the march.
 *
 * <p>Allocated only while the active pack claims the matching slot with a program (see {@link
 * #shouldAllocateTargets}), unlike {@link GBufferManager}, which exists for every active pack
 * regardless of what it claims. This is a pack-load-time fact, not a per-frame runtime one, so
 * {@code GraphRunner.prepare()} gates each slot's resize call on its own claim, the way it gates
 * {@code OpaqueDepth} on {@code packReferencesOpaqueDepth}. This differs from {@link
 * WaterSurfaceManager}, whose allocation gate is a runtime option and is sized from its own mixin
 * call site instead.
 *
 * <p>Formats are {@link GBufferManager}'s named constants, reused here by reference rather than
 * retyped: a convention mismatch between this MRT and the entities G-buffer it is modelled on
 * would make the mirror pass's shader read the wrong channel layout with no error anywhere. Depth
 * reuses the same reversed-Z clear value ({@code RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE}, 0.0, the
 * far plane) the main G-buffer and {@link WaterSurfaceManager} both clear to: every one of these
 * MRTs draws under the camera's projection (only the vertex stage reflects the player's position),
 * so its depth convention must agree with every other camera-space depth target, not with {@code
 * ShadowMapManager}'s forward-Z ortho substitution.
 */
public final class PlayerMirrorTargets {
    public static final String ALBEDO_NAME = "builtin.mirrorAlbedo";
    public static final String NORMAL_NAME = "builtin.mirrorNormal";
    public static final String MATERIAL_NAME = "builtin.mirrorMaterial";
    public static final String DEPTH_NAME = "builtin.mirrorDepth";

    public static final String X_ALBEDO_NAME = "builtin.mirrorXAlbedo";
    public static final String X_NORMAL_NAME = "builtin.mirrorXNormal";
    public static final String X_MATERIAL_NAME = "builtin.mirrorXMaterial";
    public static final String X_DEPTH_NAME = "builtin.mirrorXDepth";

    public static final String Z_ALBEDO_NAME = "builtin.mirrorZAlbedo";
    public static final String Z_NORMAL_NAME = "builtin.mirrorZNormal";
    public static final String Z_MATERIAL_NAME = "builtin.mirrorZMaterial";
    public static final String Z_DEPTH_NAME = "builtin.mirrorZDepth";

    private static final Map<GeometrySlot, PlayerMirrorTargets> INSTANCES = new EnumMap<>(GeometrySlot.class);

    /** The instance for {@code slot}, created on first use and kept for the session. */
    public static synchronized PlayerMirrorTargets forSlot(GeometrySlot slot) {
        return INSTANCES.computeIfAbsent(slot, PlayerMirrorTargets::new);
    }

    /** Every slot this MRT family exists for, in declaration order. For a caller, such as
     * GraphRunner's per-frame sizing loop or this class's tests, that needs to act on all three
     * without naming them individually. */
    public static GeometrySlot[] slots() {
        return new GeometrySlot[] {GeometrySlot.PLAYER_MIRROR, GeometrySlot.PLAYER_MIRROR_X, GeometrySlot.PLAYER_MIRROR_Z};
    }

    /** The four {@code builtin.mirror*} names {@code slot}'s family exposes (normal, albedo,
     * material, depth, in that order), for {@code GraphRunner}/{@code GraphValidator}. Keeps the
     * per-family builtin-name list in one place instead of retyped at every caller that needs to
     * know which names belong to which slot. */
    public static String[] builtinNamesFor(GeometrySlot slot) {
        return switch (slot) {
            case PLAYER_MIRROR -> new String[] {NORMAL_NAME, ALBEDO_NAME, MATERIAL_NAME, DEPTH_NAME};
            case PLAYER_MIRROR_X -> new String[] {X_NORMAL_NAME, X_ALBEDO_NAME, X_MATERIAL_NAME, X_DEPTH_NAME};
            case PLAYER_MIRROR_Z -> new String[] {Z_NORMAL_NAME, Z_ALBEDO_NAME, Z_MATERIAL_NAME, Z_DEPTH_NAME};
            default -> throw new IllegalArgumentException(
                    "PlayerMirrorTargets has no builtin names for " + slot + ": not one of the three mirror slots");
        };
    }

    private final GeometrySlot slot;

    @Nullable
    private volatile GpuTexture normalTexture;
    @Nullable
    private volatile GpuTextureView normalView;
    @Nullable
    private volatile GpuTexture albedoTexture;
    @Nullable
    private volatile GpuTextureView albedoView;
    @Nullable
    private volatile GpuTexture materialTexture;
    @Nullable
    private volatile GpuTextureView materialView;
    @Nullable
    private volatile GpuTexture depthTexture;
    @Nullable
    private volatile GpuTextureView depthView;
    private volatile int width = -1;
    private volatile int height = -1;

    private PlayerMirrorTargets(GeometrySlot slot) {
        this.slot = slot;
    }

    /**
     * Whether the MRT must exist: the graph is active and the loaded pack claims the matching slot
     * with a program. Allocation is a separate question from whether anything draws into it yet,
     * the same distinction {@link WaterSurfaceManager#shouldAllocateTargets} makes, but unlike
     * that gate, {@code slotClaimed} is a pack-load-time fact the caller already has cached
     * (mirroring {@code packReferencesOpaqueDepth}), not a runtime option read fresh every call.
     * Slot-independent: the caller passes each slot's claim boolean, so this stays one pure
     * function for all three families instead of three copies.
     */
    public static boolean shouldAllocateTargets(boolean graphActive, boolean slotClaimed) {
        return graphActive && slotClaimed;
    }

    /**
     * Ensures a half-resolution MRT is installed on this instance, sized at half of {@code
     * fullWidth}/{@code fullHeight} on each axis (rounded down, floored at 1x1): the same
     * width/height basis {@link GBufferManager}'s G-buffer uses, halved. Safe to call every frame;
     * a no-op once the requested size matches this instance's current one.
     */
    public void ensureSize(int fullWidth, int fullHeight) {
        int width = Math.max(1, fullWidth / 2);
        int height = Math.max(1, fullHeight / 2);
        if (normalTexture != null && this.width == width && this.height == height) {
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            FornaxMod.LOGGER.warn("[PlayerMirror] Skipping (re)build for {}: no GPU device available",
                    slot.token());
            return;
        }

        // Hoisted into locals and only assigned to the instance fields once the whole sequence
        // succeeds, like GBufferManager/WaterSurfaceManager's ensureSize: a mid-sequence failure
        // (VRAM pressure, a transient driver rejection) would otherwise orphan whatever textures
        // already succeeded, with no reference left to close them.
        //
        // Same usage-flag set as WaterSurfaceManager's clearable textures: USAGE_COPY_SRC so a
        // later graph pass can sample or copy this as a regular input, and USAGE_COPY_DST, which
        // CommandEncoder.clearColorTexture/clearColorAndDepthTextures require (both
        // verifyColorTexture and verifyDepthTexture check for it) for the alloc-time clear below
        // and every per-frame clear() call. Without USAGE_COPY_DST, clearColorAndDepthTextures
        // throws "Color texture must have USAGE_COPY_DST" from
        // CommandEncoder.verifyColorTexture whenever this slot is claimed, regardless of whether
        // water is in view, since allocation keys on the pack's slot claim, not on anything at
        // runtime.
        GpuTexture nextNormalTexture = null;
        GpuTextureView nextNormalView = null;
        GpuTexture nextAlbedoTexture = null;
        GpuTextureView nextAlbedoView = null;
        GpuTexture nextMaterialTexture = null;
        GpuTextureView nextMaterialView = null;
        GpuTexture nextDepthTexture = null;
        GpuTextureView nextDepthView = null;
        try {
            nextNormalTexture = device.createTexture("Fornax Player Mirror Normal",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                    GBufferManager.NORMAL_FORMAT, width, height, 1, 1);
            nextNormalView = device.createTextureView(nextNormalTexture);

            nextAlbedoTexture = device.createTexture("Fornax Player Mirror Albedo",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                    GBufferManager.ALBEDO_FORMAT, width, height, 1, 1);
            nextAlbedoView = device.createTextureView(nextAlbedoTexture);

            nextMaterialTexture = device.createTexture("Fornax Player Mirror Material",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                    GBufferManager.MATERIAL_FORMAT, width, height, 1, 1);
            nextMaterialView = device.createTextureView(nextMaterialTexture);

            nextDepthTexture = device.createTexture("Fornax Player Mirror Depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                    GBufferManager.DEPTH_FORMAT, width, height, 1, 1);
            nextDepthView = device.createTextureView(nextDepthTexture);

            // Clear at allocation, like every other engine-owned target: colour clears to
            // transparent zero, depth clears to the main camera's reversed-Z far value (0.0), not
            // ShadowMapManager's forward-Z substitution. See this class's javadoc.
            Vector4f zero = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
            var encoder = device.createCommandEncoder();
            encoder.clearColorAndDepthTextures(nextNormalTexture, zero,
                    nextDepthTexture, RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
            encoder.clearColorTexture(nextAlbedoTexture, zero);
            encoder.clearColorTexture(nextMaterialTexture, zero);
        } catch (RuntimeException e) {
            closeIfNotNull(nextDepthView);
            closeIfNotNull(nextDepthTexture);
            closeIfNotNull(nextMaterialView);
            closeIfNotNull(nextMaterialTexture);
            closeIfNotNull(nextAlbedoView);
            closeIfNotNull(nextAlbedoTexture);
            closeIfNotNull(nextNormalView);
            closeIfNotNull(nextNormalTexture);
            throw e;
        }

        GpuTexture oldNormalTexture = normalTexture;
        GpuTextureView oldNormalView = normalView;
        GpuTexture oldAlbedoTexture = albedoTexture;
        GpuTextureView oldAlbedoView = albedoView;
        GpuTexture oldMaterialTexture = materialTexture;
        GpuTextureView oldMaterialView = materialView;
        GpuTexture oldDepthTexture = depthTexture;
        GpuTextureView oldDepthView = depthView;

        normalTexture = nextNormalTexture;
        normalView = nextNormalView;
        albedoTexture = nextAlbedoTexture;
        albedoView = nextAlbedoView;
        materialTexture = nextMaterialTexture;
        materialView = nextMaterialView;
        depthTexture = nextDepthTexture;
        depthView = nextDepthView;
        this.width = width;
        this.height = height;

        if (oldNormalTexture != null) {
            // Live per-frame resize path (window resize or SSAA render-scale change) on the same
            // live instance: the same crash-class hazard as GBufferManager/WaterSurfaceManager's
            // ensureSize. See VulkanComputeBackend.waitForGpuIdleBeforeDestroy for the two live
            // MoltenVK crashes this guards against.
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        }
        closeIfNotNull(oldNormalView);
        closeIfNotNull(oldNormalTexture);
        closeIfNotNull(oldAlbedoView);
        closeIfNotNull(oldAlbedoTexture);
        closeIfNotNull(oldMaterialView);
        closeIfNotNull(oldMaterialTexture);
        closeIfNotNull(oldDepthView);
        closeIfNotNull(oldDepthTexture);

        FornaxMod.LOGGER.info("[PlayerMirror] (Re)built {} at {}x{}", slot.token(), width, height);
    }

    /**
     * Releases this instance's current textures, if any, and resets to unallocated. Null-safe and
     * idempotent, like every other engine-owned target's {@code close()}: safe to call on every
     * pack teardown even when this slot was never claimed. The caller ({@code
     * GraphRunner.closeCurrent()}) already runs {@code VulkanComputeBackend
     * .waitForGpuIdleBeforeDestroy()} once at the top of that method before freeing any GPU
     * resource it owns, so this method does not repeat that call.
     */
    public void close() {
        GpuTextureView currentNormalView = normalView;
        GpuTexture currentNormalTexture = normalTexture;
        GpuTextureView currentAlbedoView = albedoView;
        GpuTexture currentAlbedoTexture = albedoTexture;
        GpuTextureView currentMaterialView = materialView;
        GpuTexture currentMaterialTexture = materialTexture;
        GpuTextureView currentDepthView = depthView;
        GpuTexture currentDepthTexture = depthTexture;
        normalView = null;
        normalTexture = null;
        albedoView = null;
        albedoTexture = null;
        materialView = null;
        materialTexture = null;
        depthView = null;
        depthTexture = null;
        width = -1;
        height = -1;

        closeIfNotNull(currentNormalView);
        closeIfNotNull(currentNormalTexture);
        closeIfNotNull(currentAlbedoView);
        closeIfNotNull(currentAlbedoTexture);
        closeIfNotNull(currentMaterialView);
        closeIfNotNull(currentMaterialTexture);
        closeIfNotNull(currentDepthView);
        closeIfNotNull(currentDepthTexture);
    }

    /** Calls {@link #close()} on every slot's instance, for {@code GraphRunner.closeCurrent()} to
     * call once regardless of which slots, if any, were claimed. */
    public static void closeAll() {
        for (GeometrySlot slot : slots()) {
            forSlot(slot).close();
        }
    }

    /**
     * Clears depth only, to the reversed-Z far value, for {@code PlayerMirrorCaster} to call once
     * per frame at this slot's phase entry. Explicit per-call clearing rather than a render-pass
     * load op, because unlike {@link GBufferManager}'s G-buffer, where whichever writer draws
     * first that frame clears every attachment through its load ops, the mirror MRT can draw the
     * player through several separate pipeline draws in one phase and none of them is reliably
     * first: a load-op clear on the first of several draws would erase what the previous one just
     * wrote.
     *
     * <p>Depth only, not colour. {@code player_mirror_resolve.fsh} reads {@code
     * builtin.mirrorDepth} first and discards the whole fragment when it is the clear value,
     * before it ever reads a colour lane, so a stale colour texel under a freshly cleared depth
     * texel is never read regardless of what it holds. Clearing colour here would only cost
     * bandwidth, roughly a quarter of this call's cost, for three attachments this phase is about
     * to rewrite from a live draw anyway. This uses the same mechanism as {@link #ensureSize}'s
     * alloc-time clear but a narrower scope: that one still clears everything, once, at
     * allocation.
     *
     * <p>A no-op before the MRT is allocated.
     */
    public void clear() {
        GpuTexture currentDepth = depthTexture;
        if (currentDepth == null) {
            return;
        }
        RenderSystem.getDevice().createCommandEncoder()
                .clearDepthTexture(currentDepth, RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
    }

    /**
     * The full clear: all three colour attachments to transparent zero plus depth to the
     * reversed-Z far value. Used only by the falling-edge case in {@code
     * FeatureSolidFeaturesGraphMixin} for this slot: the last clear before a gap of unknown length
     * (the caster may not run again for many frames, or for the rest of the session), so a stale
     * colour lane left behind is not bounded by "the next frame overwrites it" the way the
     * per-frame path is. Fires once per falling edge rather than every frame, so the full cost is
     * not one this class pays often.
     */
    public void clearFullOnFallingEdge() {
        GpuTexture currentNormal = normalTexture;
        GpuTexture currentAlbedo = albedoTexture;
        GpuTexture currentMaterial = materialTexture;
        GpuTexture currentDepth = depthTexture;
        if (currentNormal == null || currentAlbedo == null || currentMaterial == null || currentDepth == null) {
            return;
        }
        Vector4f zero = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorAndDepthTextures(currentNormal, zero, currentDepth, RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
        encoder.clearColorTexture(currentAlbedo, zero);
        encoder.clearColorTexture(currentMaterial, zero);
    }

    private static void closeIfNotNull(@Nullable GpuTexture texture) {
        if (texture != null) {
            texture.close();
        }
    }

    private static void closeIfNotNull(@Nullable GpuTextureView view) {
        if (view != null) {
            view.close();
        }
    }

    public GeometrySlot slot() {
        return slot;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Nullable
    public GpuTextureView getNormalView() {
        return normalView;
    }

    @Nullable
    public GpuTexture getNormalTexture() {
        return normalTexture;
    }

    @Nullable
    public GpuTextureView getAlbedoView() {
        return albedoView;
    }

    @Nullable
    public GpuTexture getAlbedoTexture() {
        return albedoTexture;
    }

    @Nullable
    public GpuTextureView getMaterialView() {
        return materialView;
    }

    @Nullable
    public GpuTexture getMaterialTexture() {
        return materialTexture;
    }

    @Nullable
    public GpuTextureView getDepthView() {
        return depthView;
    }

    @Nullable
    public GpuTexture getDepthTexture() {
        return depthTexture;
    }
}
