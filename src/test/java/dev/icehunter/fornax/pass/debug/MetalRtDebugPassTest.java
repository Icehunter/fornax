package dev.icehunter.fornax.pass.debug;

import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.GBufferDebugView;
import dev.icehunter.fornax.config.RtDebugMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetalRtDebugPass#wanted} is a pure function of the live debug-view selection, the live
 * {@code rtDebugMode} setting and the {@code fornax.rt.always} system property, so its whole truth
 * table is pinned here without touching Metal or the GPU, the same split {@code
 * MetalRtSupportTest} uses for {@code allowedBy} versus the real device probe.
 */
class MetalRtDebugPassTest {
    @AfterEach
    void restoreDefaults() {
        FornaxConfig.get().debugView = GBufferDebugView.OFF;
        FornaxConfig.get().rtDebugMode = RtDebugMode.OFF;
        System.clearProperty("fornax.rt.always");
    }

    @Test
    void notWantedWhenNeitherTheViewNorThePropertyIsSet() {
        FornaxConfig.get().debugView = GBufferDebugView.OFF;
        System.clearProperty("fornax.rt.always");

        assertFalse(MetalRtDebugPass.wanted());
    }

    @Test
    void wantedWhenTheDebugViewIsSelected() {
        FornaxConfig.get().debugView = GBufferDebugView.METAL_RT_SUN_MASK;
        System.clearProperty("fornax.rt.always");

        assertTrue(MetalRtDebugPass.wanted());
    }

    @Test
    void wantedWhenThePropertyForcesItRegardlessOfTheSelectedView() {
        FornaxConfig.get().debugView = GBufferDebugView.OFF;
        System.setProperty("fornax.rt.always", "true");

        assertTrue(MetalRtDebugPass.wanted());
    }

    @Test
    void notWantedWhenThePropertyIsSetToSomethingOtherThanTrue() {
        FornaxConfig.get().debugView = GBufferDebugView.OFF;
        System.setProperty("fornax.rt.always", "no");

        assertFalse(MetalRtDebugPass.wanted());
    }

    @Test
    void wantedWhenTheSceneDebugViewIsSelected() {
        FornaxConfig.get().debugView = GBufferDebugView.METAL_RT_SCENE_DEBUG;

        assertTrue(MetalRtDebugPass.wanted());
    }

    @Test
    void wantedWhenTheDebugModeIsAnythingButOffRegardlessOfTheSelectedView() {
        FornaxConfig.get().debugView = GBufferDebugView.OFF;
        FornaxConfig.get().rtDebugMode = RtDebugMode.HIT_MISS;

        assertTrue(MetalRtDebugPass.wanted());
    }

    @Test
    void notWantedWhenTheDebugModeIsOffAndNothingElseAsksForIt() {
        FornaxConfig.get().debugView = GBufferDebugView.OFF;
        FornaxConfig.get().rtDebugMode = RtDebugMode.OFF;

        assertFalse(MetalRtDebugPass.wanted());
    }
}
