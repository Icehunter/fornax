package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.icehunter.fornax.pipeline.GeometryInputs;
import dev.icehunter.fornax.pipeline.OpaqueDepth;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure state-machine bits of {@link OpaqueDepth}'s lifecycle that {@code GraphRunner}'s
 * pack-teardown path (Round A, Task 4) depends on -- {@code free()} must be null-safe before any
 * allocation ever happened, since {@code GraphRunner.closeCurrent()} now calls it unconditionally on
 * every pack teardown (a "None" unload, a pack switch, or a rebuild()), including a session where no
 * GPU device has ever been available to allocate anything in the first place. The resize-idempotence
 * and real free/realloc round-trip (both touching {@code RenderSystem.getDevice()}) are not
 * exercised here: this suite runs headless with no GPU device ever bound (see e.g.
 * {@code TargetRegistryBufferTest}'s own doc comment), so {@link OpaqueDepth#ensureSize} always
 * no-ops via its own device-availability guard and never builds a real texture/view to assert
 * against -- there is nothing device-backed left to pin without a live {@code GpuDevice}, which this
 * plain-JUnit suite deliberately never stands up.
 */
class OpaqueDepthLifecycleTest {
    @Test
    void freeBeforeAllocIsNoOpAndViewNull() {
        OpaqueDepth od = new OpaqueDepth();
        od.free(); // must not throw before any allocation
        assertNull(od.getView());
        assertNull(od.getTexture());
    }

    @Test
    void reservedSlotsMapToStableNames() {
        assertEquals("u_GeomInput0", GeometryInputs.slot(0));
        assertEquals("u_GeomInput7", GeometryInputs.slot(GeometryInputs.RESERVED - 1));
        assertEquals(8, GeometryInputs.RESERVED);
    }
}
