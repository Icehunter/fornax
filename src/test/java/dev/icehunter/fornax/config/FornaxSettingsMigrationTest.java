package dev.icehunter.fornax.config;

import com.google.gson.Gson;
import dev.icehunter.fornax.pass.ssaa.SsaaPreset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link FornaxSettings#migrate} is the pure logic {@code FornaxConfig.load()} runs right after
 * GSON deserialization -- exercised directly here against hand-built objects, since a legacy
 * {@code fornax.json} deserializing with no {@code aaMethod} key present is indistinguishable, at
 * the Java-object level, from a freshly-constructed {@link FornaxSettings} (Gson leaves an absent
 * field at whatever the class's own field initializer set it to).
 */
class FornaxSettingsMigrationTest {
    @Test
    void legacyWithSupersamplingOnMigratesToSsaa() {
        FornaxSettings legacy = new FornaxSettings();
        legacy.schemaVersion = 0;
        legacy.ssaaPreset = SsaaPreset.X2;

        FornaxSettings migrated = FornaxSettings.migrate(legacy);

        assertEquals(AaMethod.SSAA, migrated.aaMethod);
        assertEquals(SsaaPreset.X2, migrated.ssaaPreset);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
    }

    @Test
    void legacyWithSupersamplingOffMigratesToTaa() {
        FornaxSettings legacy = new FornaxSettings();
        legacy.schemaVersion = 0;
        legacy.ssaaPreset = SsaaPreset.OFF;

        FornaxSettings migrated = FornaxSettings.migrate(legacy);

        assertEquals(AaMethod.TAA, migrated.aaMethod);
        // The v2 step then normalizes the retired OFF factor value -- but only after the v1 step
        // already read it as the legacy on/off signal, so the method above stays TAA.
        assertEquals(SsaaPreset.X2, migrated.ssaaPreset);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
    }

    @Test
    void v1FileWithFactorOffNormalizesFactorButKeepsMethod() {
        // A v1 file the user already ran under: aaMethod is authoritative (possibly hand-picked),
        // ssaaPreset may still be the retired OFF. v2 must fix the factor without touching the
        // method -- supersampling stays off because the method says so, not the factor.
        FornaxSettings v1 = new FornaxSettings();
        v1.schemaVersion = 1;
        v1.aaMethod = AaMethod.OFF;
        v1.ssaaPreset = SsaaPreset.OFF;

        FornaxSettings migrated = FornaxSettings.migrate(v1);

        assertEquals(AaMethod.OFF, migrated.aaMethod);
        assertEquals(SsaaPreset.X2, migrated.ssaaPreset);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
    }

    @Test
    void v1FileWithRealFactorIsUntouchedByV2() {
        FornaxSettings v1 = new FornaxSettings();
        v1.schemaVersion = 1;
        v1.aaMethod = AaMethod.SSAA;
        v1.ssaaPreset = SsaaPreset.X16;

        FornaxSettings migrated = FornaxSettings.migrate(v1);

        assertEquals(AaMethod.SSAA, migrated.aaMethod);
        assertEquals(SsaaPreset.X16, migrated.ssaaPreset);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
    }

    @Test
    void removedX9FactorMigratesToNearestLowerFactor() {
        // A saved "X9" no longer matches any enum constant, so Gson deserializes the field to null
        // (never an error) -- exactly the object shape simulated here. Migration must normalize it
        // to X4, the nearest remaining lower factor, without touching the method.
        FornaxSettings v2 = new FornaxSettings();
        v2.schemaVersion = 2;
        v2.aaMethod = AaMethod.SSAA;
        v2.ssaaPreset = null;

        FornaxSettings migrated = FornaxSettings.migrate(v2);

        assertEquals(AaMethod.SSAA, migrated.aaMethod);
        assertEquals(SsaaPreset.X4, migrated.ssaaPreset);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
    }

    @Test
    void removedDebugViewMigratesToOff() {
        FornaxSettings current = new FornaxSettings();
        current.schemaVersion = FornaxSettings.CURRENT_SCHEMA_VERSION;
        current.debugView = null;

        FornaxSettings migrated = FornaxSettings.migrate(current);

        assertEquals(GBufferDebugView.OFF, migrated.debugView);
    }

    @Test
    void legacyFileHoldingX9StillMigratesMethodFromItsPresence() {
        // v0 + removed constant: the null must still read as "supersampling was on" for the v1
        // step (null != OFF), then normalize to X4 -- the steps may not reorder.
        FornaxSettings legacy = new FornaxSettings();
        legacy.schemaVersion = 0;
        legacy.ssaaPreset = null;

        FornaxSettings migrated = FornaxSettings.migrate(legacy);

        assertEquals(AaMethod.SSAA, migrated.aaMethod);
        assertEquals(SsaaPreset.X4, migrated.ssaaPreset);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
    }

    @Test
    void legacyFrameGenerationTrueMigratesToAuto() {
        // A v3 file that had the boolean on keeps today's AUTO behaviour, not a silent upgrade to
        // unconditional (ALWAYS) engagement; ALWAYS is reachable only by picking it explicitly.
        FornaxSettings v3 = new FornaxSettings();
        v3.schemaVersion = 3;
        v3.frameGeneration = true;

        FornaxSettings migrated = FornaxSettings.migrate(v3);

        assertEquals(FrameGenMode.AUTO, migrated.frameGenMode);
        assertNull(migrated.frameGeneration);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
    }

    @Test
    void legacyFrameGenerationFalseMigratesToOff() {
        FornaxSettings v3 = new FornaxSettings();
        v3.schemaVersion = 3;
        v3.frameGeneration = false;

        FornaxSettings migrated = FornaxSettings.migrate(v3);

        assertEquals(FrameGenMode.OFF, migrated.frameGenMode);
        assertNull(migrated.frameGeneration);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
    }

    @Test
    void absentFrameGenModeMigratesToOff() {
        // A removed FrameGenMode constant (or a schema-4+ file predating a future addition to the
        // enum) deserializes to null; normalize it the same way ssaaPreset/debugView do, not
        // version-gated (see removedDebugViewMigratesToOff, the model for this case).
        FornaxSettings current = new FornaxSettings();
        current.schemaVersion = FornaxSettings.CURRENT_SCHEMA_VERSION;
        current.frameGenMode = null;

        FornaxSettings migrated = FornaxSettings.migrate(current);

        assertEquals(FrameGenMode.OFF, migrated.frameGenMode);
    }

    @Test
    void alreadyMigratedSettingsAreUnchanged() {
        FornaxSettings current = new FornaxSettings();
        current.schemaVersion = FornaxSettings.CURRENT_SCHEMA_VERSION;
        current.aaMethod = AaMethod.OFF;

        FornaxSettings migrated = FornaxSettings.migrate(current);

        assertEquals(AaMethod.OFF, migrated.aaMethod);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
    }

    @Test
    void nullRayTracingMigratesToAuto() {
        // Same reasoning as absentFrameGenModeMigratesToOff: a removed RayTracingMode constant
        // deserializes to null at any persisted version, not just pre-field ones.
        FornaxSettings current = new FornaxSettings();
        current.schemaVersion = FornaxSettings.CURRENT_SCHEMA_VERSION;
        current.rayTracing = null;

        FornaxSettings migrated = FornaxSettings.migrate(current);

        assertEquals(RayTracingMode.AUTO, migrated.rayTracing);
    }

    @Test
    void oldFileWithoutRayTracingFieldDeserializesToAuto() {
        // A pre-existing fornax.json with no rayTracing key, run through the same Gson path
        // FornaxConfig uses, deserializes with the field left at the class's own field
        // initializer (AUTO). migrate must not disturb that.
        FornaxSettings legacy = new Gson().fromJson("{\"schemaVersion\":0}", FornaxSettings.class);

        FornaxSettings migrated = FornaxSettings.migrate(legacy);

        assertEquals(RayTracingMode.AUTO, migrated.rayTracing);
    }

    @Test
    void nullRtDebugModeMigratesToOff() {
        // Same reasoning as nullRayTracingMigratesToAuto: a removed RtDebugMode constant
        // deserializes to null at any persisted version, not just pre-field ones.
        FornaxSettings current = new FornaxSettings();
        current.schemaVersion = FornaxSettings.CURRENT_SCHEMA_VERSION;
        current.rtDebugMode = null;

        FornaxSettings migrated = FornaxSettings.migrate(current);

        assertEquals(RtDebugMode.OFF, migrated.rtDebugMode);
    }

    @Test
    void oldFileWithoutRtDebugModeFieldDeserializesToOff() {
        // A pre-existing fornax.json with no rtDebugMode key, run through the same Gson path
        // FornaxConfig uses, deserializes with the field left at the class's own field
        // initializer (OFF). migrate must not disturb that.
        FornaxSettings legacy = new Gson().fromJson("{\"schemaVersion\":0}", FornaxSettings.class);

        FornaxSettings migrated = FornaxSettings.migrate(legacy);

        assertEquals(RtDebugMode.OFF, migrated.rtDebugMode);
    }

    @Test
    void theRetiredSunMaskViewIsStillDisabledBeforeRenderingAtEverySchemaVersion() {
        // SUN_MASK alone. METAL_RT_SCENE_DEBUG used to be reset here too, on the stated grounds
        // that no settings control exposed its colouring mode; the screen now builds one, so a
        // saved pick must survive instead.
        for (int version : new int[]{0, FornaxSettings.CURRENT_SCHEMA_VERSION}) {
            for (RtDebugMode mode : RtDebugMode.values()) {
                String json = "{\"schemaVersion\":%d,\"debugView\":\"METAL_RT_SUN_MASK\",\"rtDebugMode\":\"%s\"}"
                        .formatted(version, mode.name());
                var result = FornaxSettings.migrate(new Gson().fromJson(json, FornaxSettings.class));
                assertEquals(GBufferDebugView.OFF, result.debugView);
            }
        }
    }

    @Test
    void aSavedSceneDebugPickSurvivesMigrationAtEverySchemaVersion() {
        // The pair that made the view unreachable: the view reset to OFF and the mode forced to
        // OFF. Both are lifted, and both are asserted, because restoring either one alone puts the
        // feature back out of reach with the other half still looking correct.
        for (int version : new int[]{0, FornaxSettings.CURRENT_SCHEMA_VERSION}) {
            for (RtDebugMode mode : RtDebugMode.values()) {
                String json = "{\"schemaVersion\":%d,\"debugView\":\"METAL_RT_SCENE_DEBUG\",\"rtDebugMode\":\"%s\"}"
                        .formatted(version, mode.name());
                var result = FornaxSettings.migrate(new Gson().fromJson(json, FornaxSettings.class));
                assertEquals(GBufferDebugView.METAL_RT_SCENE_DEBUG, result.debugView);
                assertEquals(mode, result.rtDebugMode);
            }
        }
    }

    @Test
    void theSceneModeIsKeptEvenWhileAnotherViewIsSelected() {
        // The mode drives whether the trace DISPATCHES; the view drives whether it is PRESENTED.
        // They are independent on purpose, so a user can leave the mode set while looking at
        // another view. migrate used to zero the mode here, which silently undid half the setup.
        var settings = new FornaxSettings();
        settings.schemaVersion = FornaxSettings.CURRENT_SCHEMA_VERSION;
        settings.debugView = GBufferDebugView.RT_SHADOW;
        settings.rtDebugMode = RtDebugMode.HIT_MISS;
        var result = FornaxSettings.migrate(settings);
        assertEquals(GBufferDebugView.RT_SHADOW, result.debugView);
        assertEquals(RtDebugMode.HIT_MISS, result.rtDebugMode);
    }

    @Test
    void migrationIsIdempotentAcrossRepeatedCalls() {
        FornaxSettings legacy = new FornaxSettings();
        legacy.schemaVersion = 0;
        legacy.ssaaPreset = SsaaPreset.X4;
        legacy.frameGeneration = true;

        FornaxSettings once = FornaxSettings.migrate(legacy);
        FornaxSettings twice = FornaxSettings.migrate(once);

        assertEquals(AaMethod.SSAA, twice.aaMethod);
        assertEquals(SsaaPreset.X4, twice.ssaaPreset);
        assertEquals(FrameGenMode.AUTO, twice.frameGenMode);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, twice.schemaVersion);
    }
}
