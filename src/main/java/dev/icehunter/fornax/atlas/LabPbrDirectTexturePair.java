package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/** One reload generation's source-resolution direct entity {@code _n}/{@code _s} textures. */
public final class LabPbrDirectTexturePair implements AutoCloseable {
    @Nullable
    private final Texture normal;
    @Nullable
    private final Texture material;

    private LabPbrDirectTexturePair(@Nullable Texture normal, @Nullable Texture material) {
        this.normal = normal;
        this.material = material;
    }

    static LabPbrDirectTexturePair create(Identifier owner, ResourceManager resources,
                                          LabPbrSidecarDescriptor descriptor) {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return new LabPbrDirectTexturePair(null, null);
        }

        int[] ownerDimensions = dimensions(resources, owner);
        Texture normal = uploadLane(device, owner, "normal", resources,
                descriptor.normal(), ownerDimensions);
        Texture material = uploadLane(device, owner, "material", resources,
                descriptor.material(), ownerDimensions);
        return new LabPbrDirectTexturePair(normal, material);
    }

    @Nullable
    GpuTextureView normalView() {
        return this.normal == null ? null : this.normal.view();
    }

    @Nullable
    GpuTextureView materialView() {
        return this.material == null ? null : this.material.view();
    }

    /** Decodes exactly one source PNG without resizing or transforming categorical bytes. */
    static NativeImage loadSource(ResourceManager resources, Identifier sidecar) throws IOException {
        Resource resource = resources.getResource(sidecar)
                .orElseThrow(() -> new IOException("missing LabPBR sidecar " + sidecar));
        try (InputStream input = resource.open()) {
            return NativeImage.read(NativeImage.Format.RGBA, input);
        }
    }

    @Nullable
    private static Texture uploadLane(GpuDevice device, Identifier owner, String lane,
                                      ResourceManager resources, Optional<Identifier> sidecar,
                                      @Nullable int[] ownerDimensions) {
        if (sidecar.isEmpty() || ownerDimensions == null) {
            return null;
        }
        Identifier id = sidecar.get();
        try (NativeImage image = loadSource(resources, id)) {
            if ((long) image.getWidth() * ownerDimensions[1]
                    != (long) image.getHeight() * ownerDimensions[0]) {
                FornaxMod.LOGGER.warn("[LabPBR] {} sidecar {} for {} has aspect {}x{}, owner is {}x{}; using neutral lane",
                        lane, id, owner, image.getWidth(), image.getHeight(),
                        ownerDimensions[0], ownerDimensions[1]);
                return null;
            }
            GpuTexture texture = device.createTexture(
                    "Fornax LabPBR " + lane + " " + owner,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
            try {
                device.createCommandEncoder().writeToTexture(texture, image);
                return new Texture(texture, device.createTextureView(texture));
            } catch (RuntimeException failure) {
                texture.close();
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            FornaxMod.LOGGER.warn("[LabPBR] Could not load {} sidecar {} for {}; using neutral lane",
                    lane, id, owner, failure);
            return null;
        }
    }

    @Nullable
    private static int[] dimensions(ResourceManager resources, Identifier owner) {
        try (NativeImage image = loadSource(resources, owner)) {
            return new int[] {image.getWidth(), image.getHeight()};
        } catch (IOException | RuntimeException failure) {
            FornaxMod.LOGGER.warn("[LabPBR] Could not validate direct albedo owner {}; using neutral sidecars",
                    owner, failure);
            return null;
        }
    }

    @Override
    public void close() {
        if (this.normal != null) {
            this.normal.close();
        }
        if (this.material != null) {
            this.material.close();
        }
    }

    private record Texture(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
        @Override
        public void close() {
            view.close();
            texture.close();
        }
    }
}
