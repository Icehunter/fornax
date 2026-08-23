package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins clear-on-rebuild for the {@link TargetRegistry#resize} route (the SSR half-res parity
 * hook): actually clearing a rebuilt texture needs a live GPU device, so that half is
 * build+deploy-verified only. What IS pure, and what this test owns, is the routing decision {@link
 * TargetRegistry#isResize} makes -- {@code resize()} must never treat a genuine size change as a
 * no-op, because every path that reaches it (the same-named private {@code reconcile}, and the
 * class's own javadoc) always tears down and re-clears the texture rather than leaving stale
 * content behind. Getting this guard backwards silently reintroduces the exact "stale half-res
 * artifact after a quality flip" bug this hardening exists to close.
 */
class TargetRegistryResizeClearTest {

    @Test
    void sizeChangeIsNeverTreatedAsANoOp() {
        assertTrue(TargetRegistry.isResize(960, 540, 1920, 1080), "full-res width/height must count as a resize");
        assertTrue(TargetRegistry.isResize(1920, 1080, 960, 540), "half-res width/height must count as a resize");
        assertTrue(TargetRegistry.isResize(960, 540, 960, 541), "even a one-pixel height change must count as a resize");
        assertTrue(TargetRegistry.isResize(960, 540, 961, 540), "even a one-pixel width change must count as a resize");
    }

    @Test
    void identicalSizeIsANoOp() {
        assertFalse(TargetRegistry.isResize(960, 540, 960, 540), "an unchanged size must stay a no-op");
        assertFalse(TargetRegistry.isResize(1, 1, 1, 1), "an unchanged 1x1 size must stay a no-op");
    }
}
