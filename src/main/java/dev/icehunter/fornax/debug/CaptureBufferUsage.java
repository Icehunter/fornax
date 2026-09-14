package dev.icehunter.fornax.debug;

import com.mojang.blaze3d.buffers.GpuBuffer;
import java.nio.file.Files;
import net.fabricmc.loader.api.FabricLoader;

/** Capture configuration must exist at startup, before the uniform-buffer rings are allocated. */
public final class CaptureBufferUsage {
    private CaptureBufferUsage() {}

    private static final class Configured {
        private static final boolean ENABLED = Files.isRegularFile(
                FabricLoader.getInstance().getGameDir().resolve("config/fornax-capture.json"));
    }

    public static boolean enabled() { return Configured.ENABLED; }

    public static int forCapture(int usage) { return forCapture(usage, Configured.ENABLED); }

    public static int forCapture(int usage, boolean configured) {
        return configured && (usage & GpuBuffer.USAGE_UNIFORM) != 0
                ? usage | GpuBuffer.USAGE_COPY_SRC : usage;
    }
}
