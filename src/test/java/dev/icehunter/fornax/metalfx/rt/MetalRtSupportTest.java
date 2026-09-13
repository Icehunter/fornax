package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.RayTracingMode;
import dev.icehunter.fornax.metalfx.objc.Objc;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetalRtSupport#allowedBy} is a pure function of two device facts and the setting, so its
 * whole truth table is pinned here without touching Metal. The one test that needs the real GPU is
 * gated on {@link Objc#isLoaded()} and only checks the device-level facts, never the setting.
 */
class MetalRtSupportTest {
    @Test
    void offIsNeverAllowedRegardlessOfHardware() {
        assertFalse(MetalRtSupport.allowedBy(true, true, RayTracingMode.OFF));
        assertFalse(MetalRtSupport.allowedBy(true, false, RayTracingMode.OFF));
        assertFalse(MetalRtSupport.allowedBy(false, true, RayTracingMode.OFF));
        assertFalse(MetalRtSupport.allowedBy(false, false, RayTracingMode.OFF));
    }

    @Test
    void autoIsAllowedOnlyWhenBothRaytracingAndApple9AreReported() {
        assertTrue(MetalRtSupport.allowedBy(true, true, RayTracingMode.AUTO));
        assertFalse(MetalRtSupport.allowedBy(true, false, RayTracingMode.AUTO));
        assertFalse(MetalRtSupport.allowedBy(false, true, RayTracingMode.AUTO));
        assertFalse(MetalRtSupport.allowedBy(false, false, RayTracingMode.AUTO));
    }

    @Test
    void forceIsAllowedWheneverRaytracingIsReportedRegardlessOfFamily() {
        assertTrue(MetalRtSupport.allowedBy(true, true, RayTracingMode.FORCE));
        assertTrue(MetalRtSupport.allowedBy(true, false, RayTracingMode.FORCE));
        assertFalse(MetalRtSupport.allowedBy(false, true, RayTracingMode.FORCE));
        assertFalse(MetalRtSupport.allowedBy(false, false, RayTracingMode.FORCE));
    }

    @Test
    void probeReportsRaytracingAndApple9OnThisMachineAndDrivesIsAvailableThroughTheSetting() {
        // M5 Pro, macOS 26.6.2: apple9-family hardware, so both device facts are expected true.
        Assumptions.assumeTrue(Objc.isLoaded());

        MetalRtSupport.probe();
        assertTrue(MetalRtSupport.deviceSupportsRaytracing(),
                "this machine's device is expected to report supportsRaytracing");
        assertTrue(MetalRtSupport.hardwareRayTracing(),
                "an M5 Pro is apple9+, so hardwareRayTracing() must be true");

        RayTracingMode saved = FornaxConfig.get().rayTracing;
        try {
            // isAvailable() must track a live setting change without a second device probe.
            FornaxConfig.get().rayTracing = RayTracingMode.OFF;
            assertFalse(MetalRtSupport.isAvailable());
            assertEquals("ray tracing mode is Off", MetalRtSupport.unavailableReason(),
                    "an explicit Off is reported on its own terms, ahead of any device fact");

            FornaxConfig.get().rayTracing = RayTracingMode.FORCE;
            assertTrue(MetalRtSupport.isAvailable());

            FornaxConfig.get().rayTracing = RayTracingMode.AUTO;
            assertTrue(MetalRtSupport.isAvailable());
            assertEquals(null, MetalRtSupport.unavailableReason());
        } finally {
            FornaxConfig.get().rayTracing = saved;
        }
    }

    @Test
    void secondProbeCallDoesNotRerunTheDeviceQuery() {
        Assumptions.assumeTrue(Objc.isLoaded());

        MetalRtSupport.probe();
        int countAfterFirstProbe = MetalRtSupport.probeRunCount();
        assertTrue(countAfterFirstProbe >= 1, "the first probe() call must run the device query");

        MetalRtSupport.probe();
        MetalRtSupport.probe();
        // isAvailable()/hardwareRayTracing()/unavailableReason() all call probe() internally too;
        // none of them may trigger a second device query once the first has run.
        MetalRtSupport.isAvailable();
        MetalRtSupport.hardwareRayTracing();
        MetalRtSupport.unavailableReason();

        assertEquals(countAfterFirstProbe, MetalRtSupport.probeRunCount(),
                "repeated probe()/isAvailable() calls must reuse the cached device query");
    }
}
