package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.mixin.vulkan.GpuDeviceBackendAccessor;
import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.PackTextureSpec;
import dev.icehunter.fornax.pack.RawVolumeAsset;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK13;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns every pack-shipped static texture asset a loaded pack's {@code GraphSpec.textures()}
 * declares (e.g. {@code [textures.waterWaveNormal]}) -- the read-only, non-render-target sibling of
 * {@link TargetRegistry}. Created (pure bookkeeping, no GPU call) alongside {@link TargetRegistry} in
 * {@code GraphRunner.rebuild()}; {@link #ensureLoaded()} lazily decodes and uploads every declared
 * texture not yet built, called from {@code GraphRunner.prepare()} exactly like {@code
 * TargetRegistry.ensureSize} -- {@code rebuild()} itself can run before any GPU device exists (mod
 * init), so the actual upload is deferred to the first frame a device is available, mirroring every
 * other GPU-touching construction in this package.
 *
 * <p>{@code PackDiscovery.loadFrom} already proved every declared file exists and decodes cleanly
 * at pack-load time (see its {@code validateTextureAssets}) -- a decode failure reaching {@link
 * #ensureLoaded()} here would therefore mean the file changed on disk out from under an already-
 * validated pack (or a same-session race), not an authoring error; it is logged once per texture
 * name rather than thrown, since this runs every frame from {@code prepare()} and must never crash a
 * live frame the way a load-time {@link FornaxPackError} is allowed to.
 *
 * <p>{@link #close()} follows the same teardown law {@link TargetRegistry#close()} and {@code
 * OpaqueDepth#free()} do -- called from {@code GraphRunner.closeCurrent()}, AFTER that method's own
 * {@code VulkanComputeBackend.waitForGpuIdleBeforeDestroy()} call, never before: every {@code
 * GpuTexture}/{@code GpuTextureView} here may still be referenced by a just-submitted frame's
 * command buffer.
 */
public final class PackTextureRegistry implements AutoCloseable {
    private final Path packRoot;
    private final Map<String, PackTextureSpec> specs;
    private final Map<String, GpuTexture> textures = new LinkedHashMap<>();
    private final Map<String, GpuTextureView> views = new LinkedHashMap<>();

    private PackTextureRegistry(Path packRoot, Map<String, PackTextureSpec> specs) {
        this.packRoot = packRoot;
        this.specs = specs;
    }

    public static PackTextureRegistry create(Path packRoot, Map<String, PackTextureSpec> specs) {
        return new PackTextureRegistry(packRoot, specs);
    }

    /** True iff {@code name} is a declared pack-texture name in this registry's owning pack -- used
     * by {@link FullscreenPassRunner}'s LINEAR + REPEAT sampler special-case, mirroring the literal
     * {@code "builtin.noise"} name check it already does. */
    public boolean isDeclared(String name) {
        return specs.containsKey(name);
    }

    /** True iff {@code name} declares a genuine 3D volume rather than a mipmapped 2D image. */
    public boolean isVolume(String name) {
        PackTextureSpec spec = specs.get(name);
        return spec != null && spec.isVolume();
    }

    /**
     * Decodes and uploads every declared texture not yet built. No-op once every declared texture
     * has been loaded (the common steady-state case, checked without touching the GPU) or if no
     * device exists yet -- mirrors {@code TargetRegistry.reconcile}'s own device-availability guard.
     */
    public void ensureLoaded() {
        if (textures.size() >= specs.size()) {
            return;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }
        for (PackTextureSpec spec : specs.values()) {
            if (textures.containsKey(spec.name())) {
                continue;
            }
            load(device, spec);
        }
    }

    private void load(GpuDevice device, PackTextureSpec spec) {
        if (spec.isVolume()) {
            loadVolume(spec);
        } else {
            load2D(device, spec);
        }
    }

    /**
     * Loads a {@code [textures.*]} volume spec through {@link Volume3DTexture}, the engine's one
     * hand-built {@code VK_IMAGE_TYPE_3D} path: {@code device.createTexture} itself throws for any
     * {@code depthOrLayers > 1} (see that class's own doc), so unlike {@link #load2D} this method
     * never touches the {@link GpuDevice} Blaze3D handed {@link #ensureLoaded()}; it re-derives the
     * Vulkan backend directly, the same way {@link Volume3DTexture#create} itself does.
     *
     * <p>Skips (does not throw) on a non-Vulkan backend, since a GL device can never host a real 3D
     * image and there is nothing to retry: the same permanent-skip contract
     * {@code Volume3DTexture.create} documents for itself.
     */
    private void loadVolume(PackTextureSpec spec) {
        Path file = packRoot.resolve(spec.file());
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            // Mirrors Volume3DTexture.create's own gpuDevice == null guard (Volume3DTexture.java)
            // rather than casting straight to GpuDeviceBackendAccessor. Unreachable today since
            // ensureLoaded() already null-checked the device synchronously just above this call, but
            // this method must never assume that stays true.
            return;
        }
        GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) device).fornax$backend();
        // Duplicates the instanceof check Volume3DTexture.create already makes internally
        // (including its unavailableOnThisBackend latch); kept here only so the non-Vulkan case logs
        // its own specific message instead of the generic "no GPU device yet" one below. Must stay
        // in sync with create()'s gate if that ever changes shape.
        if (!(backend instanceof VulkanDevice)) {
            FornaxMod.LOGGER.warn("[Fornax] Skipping volume texture '{}': not on Vulkan backend", spec.name());
            return;
        }
        // Hoisted above the try, mirroring load2D's texture/view hoist: create() can succeed and
        // hand back a real, GPU-backed volume, and the separate upload() call below can then throw
        // (IllegalArgumentException on a mismatch, GpuFatalException on a fence timeout), both landing
        // in this method's own catch. Without this reference the catch has no way to free an
        // already-constructed volume, and ensureLoaded() retries a not-yet-loaded spec every frame,
        // so a persistently failing upload would leak one full 3D image per frame, forever.
        Volume3DTexture volume = null;
        try {
            RawVolumeAsset asset = RawVolumeAsset.read(file);
            int vkFormat = asset.format() == RawVolumeAsset.Format.R8
                    ? VK13.VK_FORMAT_R8_UNORM : VK13.VK_FORMAT_R8G8B8A8_UNORM;
            volume = Volume3DTexture.create("Fornax Pack Volume " + spec.name(),
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    vkFormat, asset.width(), asset.height(), asset.depth());
            if (volume == null) {
                FornaxMod.LOGGER.warn("[Fornax] Skipping volume texture '{}': no GPU device yet", spec.name());
                return;
            }
            volume.upload(asset);
            textures.put(spec.name(), volume);
            views.put(spec.name(), volume.view());
            FornaxMod.LOGGER.info("[Fornax] Pack volume texture '{}' loaded from {} ({}x{}x{}, {})",
                    spec.name(), spec.file(), asset.width(), asset.height(), asset.depth(), asset.format());
        } catch (IOException | RuntimeException e) {
            // Same rationale as load2D's own catch: PackDiscovery already proved this file reads and
            // validates at load time, so reaching this catch means the file changed on disk after
            // validation, or a transient GPU failure. Logged, not thrown, for the same reason. Free
            // the volume if create() succeeded but something after it (upload, here) failed; see
            // the hoist comment above. Only put into textures/views once every step succeeds.
            if (volume != null) volume.close();
            FornaxMod.LOGGER.error("[Fornax] PackTextureRegistry: failed to (re)load volume texture '{}' from {}: {}",
                    spec.name(), spec.file(), e.getMessage());
        }
    }

    private void load2D(GpuDevice device, PackTextureSpec spec) {
        Path file = packRoot.resolve(spec.file());
        // Hoisted above the try so the catch can free it: only put into textures/views once BOTH
        // the texture and its view succeed, or a failure creating the view left the texture
        // orphaned, with no reference anywhere to close it.
        GpuTexture texture = null;
        GpuTextureView view = null;
        try (InputStream in = Files.newInputStream(file);
             NativeImage image = NativeImage.read(NativeImage.Format.RGBA, in)) {
            int mipLevels = computeMipLevelCount(image.getWidth(), image.getHeight());
            texture = device.createTexture("Fornax Pack Texture " + spec.name(),
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, mipLevels);
            CommandEncoder encoder = device.createCommandEncoder();
            encoder.writeToTexture(texture, image, 0, 0, 0, 0);

            // Pack textures are tileable shader inputs, and therefore routinely minified. Upload a
            // full box-filtered chain rather than forcing every distant sample through level zero;
            // the matching mip-enabled sampler lives in FullscreenPassRunner. CommandEncoder has no
            // scaling blit, so (as with the engine's LabPBR atlases) the one-time reduction happens
            // on the CPU and each level is uploaded explicitly.
            NativeImage previous = image;
            for (int level = 1; level < mipLevels; level++) {
                NativeImage mip = downsample(previous);
                encoder.writeToTexture(texture, mip, level, 0, 0, 0);
                if (previous != image) {
                    previous.close();
                }
                previous = mip;
            }
            if (previous != image) {
                previous.close();
            }

            view = device.createTextureView(texture);
            textures.put(spec.name(), texture);
            views.put(spec.name(), view);
            FornaxMod.LOGGER.info("[Fornax] Pack texture '{}' loaded from {} ({}x{}, {} mips)",
                    spec.name(), spec.file(), image.getWidth(), image.getHeight(), mipLevels);
        } catch (IOException | RuntimeException e) {
            // See this class's own doc: PackDiscovery already proved this file decodes at load time,
            // so reaching this catch usually means the file changed on disk after validation, or a
            // transient GPU failure. Logged, not thrown, since ensureLoaded() runs every frame and
            // must never crash a live frame.
            if (view != null) view.close();
            if (texture != null) texture.close();
            FornaxMod.LOGGER.error("[Fornax] PackTextureRegistry: failed to (re)load texture '{}' from {}: {}",
                    spec.name(), spec.file(), e.getMessage());
        }
    }

    static int computeMipLevelCount(int width, int height) {
        // Mojang's GpuTexture.getWidth(level)/getHeight(level) are bare right shifts rather than
        // Vulkan's max(1, dimension >> level). Counting from the larger axis therefore creates a
        // nominal mip whose smaller axis is zero for non-square images (1040x3120 reached 0x1 at
        // level 11), and CommandEncoder.writeToTexture rejects the otherwise valid 1x1 upload.
        // Stop at the smaller axis instead. The last level can remain rectangular (1x3 here), which
        // is legal and still gives the sampler a complete usable chain under Mojang's abstraction.
        int minDimension = Math.max(1, Math.min(width, height));
        return 1 + (31 - Integer.numberOfLeadingZeros(minDimension));
    }

    static NativeImage downsample(NativeImage source) {
        int width = Math.max(1, source.getWidth() >> 1);
        int height = Math.max(1, source.getHeight() >> 1);
        NativeImage result = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        for (int y = 0; y < height; y++) {
            int y0 = Math.min(y * 2, source.getHeight() - 1);
            int y1 = Math.min(y0 + 1, source.getHeight() - 1);
            for (int x = 0; x < width; x++) {
                int x0 = Math.min(x * 2, source.getWidth() - 1);
                int x1 = Math.min(x0 + 1, source.getWidth() - 1);
                result.setPixel(x, y, averageChannels(
                        source.getPixel(x0, y0), source.getPixel(x1, y0),
                        source.getPixel(x0, y1), source.getPixel(x1, y1)));
            }
        }
        return result;
    }

    private static int averageChannels(int p0, int p1, int p2, int p3) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int sum = ((p0 >> shift) & 0xFF) + ((p1 >> shift) & 0xFF)
                    + ((p2 >> shift) & 0xFF) + ((p3 >> shift) & 0xFF);
            result |= ((sum + 2) / 4) << shift;
        }
        return result;
    }

    @Nullable
    public GpuTextureView getView(String name) {
        return views.get(name);
    }

    @Nullable
    public GpuTexture getTexture(String name) {
        return textures.get(name);
    }

    @Override
    public void close() {
        for (GpuTextureView v : views.values()) {
            v.close();
        }
        for (GpuTexture t : textures.values()) {
            t.close();
        }
        views.clear();
        textures.clear();
    }
}
