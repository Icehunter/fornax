package dev.icehunter.fornax.util;

import com.mojang.blaze3d.GpuDeviceLossException;

/**
 * One place to decide whether a caught GPU-adjacent failure is fatal (the device is gone, nothing
 * submitted after it can succeed) versus ordinary (log it, degrade, keep rendering).
 *
 * <p>{@code GpuDeviceLossException extends RuntimeException}, so every broad {@code catch
 * (RuntimeException | Exception | Throwable)} around GPU work in this tree -- there were 94 at last
 * count -- silently swallows it exactly like any other bug unless it is checked for explicitly.
 * {@code MetalFxUpscalePass.runIfEnabled} was the one place that did, inline, with the reasoning
 * spelled out in its own catch: falling back and continuing hands the very next unrelated
 * {@code submit()} the same dead device, which surfaces one frame later as an unattributed native
 * crash with no Java stack trace at all. That is not a defect specific to MetalFX; it is true of
 * every catch that wraps a GPU submission. This class is that one rethrow, written once.
 *
 * <p>Call it FIRST in any catch block around GPU work, before logging or falling back:
 * <pre>{@code
 * } catch (RuntimeException e) {
 *     GpuFatalErrors.rethrowIfFatal(e);
 *     LOGGER.warn("...", e);
 *     // ordinary degrade-and-continue path
 * }
 * }</pre>
 *
 * <p>Deliberately not applied to every one of the 94 sites in one change -- some (a per-frame
 * fullscreen-pass retry, a debug probe) have their own considered retry/latch behaviour that a
 * blanket rethrow would need to be reconciled with individually. This exists so each site can adopt
 * it incrementally without re-deriving the same reasoning every time.
 */
public final class GpuFatalErrors {
    private GpuFatalErrors() {
    }

    /**
     * Rethrows {@code failure} unchanged when it is {@link GpuDeviceLossException} or {@link
     * GpuFatalException} (both are {@code RuntimeException}s, so this never needs to wrap one);
     * returns normally for anything else, so the caller's existing log-and-degrade path runs
     * exactly as before for an ordinary failure.
     */
    public static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof RuntimeException runtime
                && (failure instanceof GpuDeviceLossException || failure instanceof GpuFatalException)) {
            throw runtime;
        }
    }
}
