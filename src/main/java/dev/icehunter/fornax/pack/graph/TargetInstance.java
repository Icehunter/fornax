package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/**
 * One allocated pack render target: a current-frame texture/view, and -- when the pack declared
 * {@code history = true} -- a second, identically-sized/formatted texture/view pair that
 * {@link TargetRegistry#swapHistory()} ping-pongs with at the end of every frame this target's
 * graph is active.
 *
 * <p>Ping-pong (swap the pointer), not commit-via-copy (like the hardcoded pipeline's
 * raw/blended/previous three-texture managers): a pack target only ever declares one name for
 * "this frame's value" plus one {@code .history} read of "last frame's value", so there is no
 * separate "previous" slot to copy into -- swapping which physical texture is "current" and which
 * is "history" after the frame's passes have all run makes what was just written become next
 * frame's history read, with zero extra GPU copy. Both physical textures therefore need identical
 * usage flags (render-attachment + sampleable), since each alternates between being rendered into
 * and being sampled as history across frames.
 */
public final class TargetInstance implements AutoCloseable {
    private final String name;
    private final TargetFormat format;
    private final int width;
    private final int height;
    private final boolean history;

    private GpuTexture texture;
    private GpuTextureView view;
    private GpuTexture historyTexture;
    private GpuTextureView historyView;

    TargetInstance(String name, TargetFormat format, int width, int height, boolean history,
                    GpuTexture texture, GpuTextureView view,
                    @Nullable GpuTexture historyTexture, @Nullable GpuTextureView historyView) {
        this.name = name;
        this.format = format;
        this.width = width;
        this.height = height;
        this.history = history;
        this.texture = texture;
        this.view = view;
        this.historyTexture = historyTexture;
        this.historyView = historyView;
    }

    public String name() {
        return name;
    }

    public TargetFormat format() {
        return format;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean hasHistory() {
        return history;
    }

    public GpuTexture texture() {
        return texture;
    }

    public GpuTextureView view() {
        return view;
    }

    @Nullable
    public GpuTexture historyTexture() {
        return historyTexture;
    }

    @Nullable
    public GpuTextureView historyView() {
        return historyView;
    }

    /** Swaps current <-> history. No-op (and a caller bug) if this target isn't history-backed. */
    void swap() {
        if (!history) {
            return;
        }
        GpuTexture t = texture;
        GpuTextureView v = view;
        texture = historyTexture;
        view = historyView;
        historyTexture = t;
        historyView = v;
    }

    /**
     * Document-safe: no wait-idle here by design. Every caller (TargetRegistry.close/reconcile/
     * ensureSize's removeIf) already calls VulkanComputeBackend.waitForGpuIdleBeforeDestroy()
     * immediately before reaching this, so a guard here would just be a redundant second drain on
     * the hot teardown path. If a future caller is ever added outside TargetRegistry, it must do
     * the same before calling this.
     */
    @Override
    public void close() {
        view.close();
        texture.close();
        if (history) {
            historyView.close();
            historyTexture.close();
        }
    }
}
