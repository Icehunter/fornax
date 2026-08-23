package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import java.util.List;

/** One reload generation's animated sidecars, owned and closed with its atlas texture. */
final class LabPbrAnimationSet implements AutoCloseable {
    static final LabPbrAnimationSet EMPTY = new LabPbrAnimationSet(List.of(), List.of());

    private final List<LabPbrAnimatedSidecar> sidecars;
    private boolean closed;

    LabPbrAnimationSet(List<LabPbrAnimatedSidecar> sidecars,
                       List<LabPbrAnimatedSidecar.Rect> occupiedRegions) {
        this.sidecars = List.copyOf(sidecars);
        stopBeforeSharedMipTexels(this.sidecars, occupiedRegions);
    }

    void tick(GpuTexture texture) {
        if (this.closed || this.sidecars.isEmpty()) {
            return;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }
        CommandEncoder[] encoder = new CommandEncoder[1];
        for (LabPbrAnimatedSidecar sidecar : this.sidecars) {
            sidecar.tick((level, x, y, pixels) -> {
                if (encoder[0] == null) {
                    encoder[0] = device.createCommandEncoder();
                }
                encoder[0].writeToTexture(texture, pixels, level, 0, x, y);
            });
        }
    }

    private static void stopBeforeSharedMipTexels(List<LabPbrAnimatedSidecar> sidecars,
                                                   List<LabPbrAnimatedSidecar.Rect> occupied) {
        for (LabPbrAnimatedSidecar sidecar : sidecars) {
            boolean skippedSelf = false;
            for (LabPbrAnimatedSidecar.Rect other : occupied) {
                if (!skippedSelf && other.equals(sidecar.uploadRectAtMip(0))) {
                    skippedSelf = true;
                    continue;
                }
                for (int level = 0; level < sidecar.preparedMipLevels(); level++) {
                    if (overlaps(sidecar.uploadRectAtMip(level), other.atMip(level))) {
                        sidecar.stopBeforeMip(level);
                        break;
                    }
                }
            }
        }
    }

    private static boolean overlaps(LabPbrAnimatedSidecar.Rect a, LabPbrAnimatedSidecar.Rect b) {
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width()
                && a.y() < b.y() + b.height() && b.y() < a.y() + a.height();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (LabPbrAnimatedSidecar sidecar : this.sidecars) {
            sidecar.close();
        }
    }
}
