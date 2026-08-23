package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/** Device-generation-owned semantic fallbacks for independently missing LabPBR lanes. */
public final class LabPbrNeutralTextures {
    public static final int NORMAL_ARGB = 0xFF_80_80_FF;
    public static final int MATERIAL_ARGB = 0xFF_00_00_00;

    @Nullable
    private static GpuDevice ownerDevice;
    @Nullable
    private static Texture normal;
    @Nullable
    private static Texture material;

    private LabPbrNeutralTextures() {
    }

    public static synchronized GpuTextureView normalView() {
        ensureCreated();
        return normal.view();
    }

    public static synchronized GpuTextureView materialView() {
        ensureCreated();
        return material.view();
    }

    private static void ensureCreated() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            throw new IllegalStateException("LabPBR fallback requested without a GPU device");
        }
        if (ownerDevice == device && normal != null && material != null) {
            return;
        }

        closeCurrent();
        Texture nextNormal = create(device, "Fornax Neutral LabPBR Normal", NORMAL_ARGB);
        try {
            Texture nextMaterial = create(device, "Fornax Neutral LabPBR Material", MATERIAL_ARGB);
            ownerDevice = device;
            normal = nextNormal;
            material = nextMaterial;
        } catch (RuntimeException failure) {
            nextNormal.close();
            throw failure;
        }
    }

    private static Texture create(GpuDevice device, String label, int argb) {
        GpuTexture texture = device.createTexture(label,
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false)) {
            image.setPixel(0, 0, argb);
            CommandEncoder encoder = device.createCommandEncoder();
            encoder.writeToTexture(texture, image);
            return new Texture(texture, device.createTextureView(texture));
        } catch (RuntimeException failure) {
            texture.close();
            throw failure;
        }
    }

    private static void closeCurrent() {
        if (normal != null) {
            normal.close();
        }
        if (material != null) {
            material.close();
        }
        normal = null;
        material = null;
        ownerDevice = null;
    }

    private record Texture(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
        @Override
        public void close() {
            view.close();
            texture.close();
        }
    }
}
