package dev.icehunter.fornax.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RayTracingBackendTest {
    @Test
    void backendMenuOffersOnlyImplementedSupportedApisWithAutomaticFirst() {
        assertEquals(List.of(RayTracingMode.AUTO, RayTracingMode.OFF, RayTracingMode.FORCE),
                RayTracingMode.availableBackends(true));
        assertEquals(List.of(RayTracingMode.AUTO, RayTracingMode.OFF),
                RayTracingMode.availableBackends(false));
    }

    @Test
    void legacyConfigValuesKeepTheirBackendMeaningAcrossMigrationAndRoundTrip() {
        Gson gson = new Gson();
        String[] labels = {"None", "Automatic", "Metal RT"};
        for (RayTracingMode mode : RayTracingMode.values()) {
            FornaxSettings settings = FornaxSettings.migrate(gson.fromJson(
                    "{\"schemaVersion\":4,\"rayTracing\":\"" + mode.name() + "\"}", FornaxSettings.class));
            assertEquals(mode, settings.rayTracing);
            assertEquals(labels[mode.ordinal()], settings.rayTracing.backendLabel());
            FornaxSettings restored = FornaxSettings.migrate(gson.fromJson(gson.toJson(settings), FornaxSettings.class));
            assertEquals(mode, restored.rayTracing);
        }
    }

    @Test
    void freshAbsentNullAndUnknownSelectionsAllResolveToAutomatic() {
        assertEquals("Automatic", new FornaxSettings().rayTracing.backendLabel());
        for (String json : List.of("{}", "{\"rayTracing\":null}", "{\"rayTracing\":\"FUTURE_API\"}")) {
            assertEquals(RayTracingMode.AUTO,
                    FornaxSettings.migrate(new Gson().fromJson(json, FornaxSettings.class)).rayTracing);
        }
    }
}
