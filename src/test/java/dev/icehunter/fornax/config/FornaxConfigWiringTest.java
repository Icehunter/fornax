package dev.icehunter.fornax.config;

import com.google.gson.Gson;
import dev.icehunter.fornax.pass.ssaa.SsaaPreset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Load/save WIRING against a real (temp) file, complementing {@link FornaxSettingsMigrationTest}'s
 * pure-logic coverage -- specifically the fresh-install path: the file {@code load()}'s no-file
 * branch writes must already carry {@link FornaxSettings#CURRENT_SCHEMA_VERSION}, or the next
 * launch's migration treats the brand-new file as legacy and re-derives {@code aaMethod} from
 * {@code ssaaPreset}, silently clobbering whatever the user picked in their first session.
 */
class FornaxConfigWiringTest {
    @TempDir
    Path configDir;

    @Test
    void freshInstallSaveStampsCurrentSchemaVersion() throws IOException {
        Path path = configDir.resolve("fornax.json");
        FornaxConfig.install(new FornaxSettings());

        FornaxConfig.load(path); // no file: defaults written out immediately

        FornaxSettings onDisk = new Gson().fromJson(Files.readString(path), FornaxSettings.class);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, onDisk.schemaVersion);
    }

    @Test
    void aaMethodChoiceFromTheFirstSessionSurvivesASecondLaunch() {
        Path path = configDir.resolve("fornax.json");
        FornaxConfig.install(new FornaxSettings());
        FornaxConfig.load(path);                 // first launch, fresh install
        FornaxConfig.get().aaMethod = AaMethod.OFF; // user picks a non-default method
        FornaxConfig.save(path);                 // settings screen close

        FornaxConfig.install(new FornaxSettings()); // simulated fresh JVM
        FornaxConfig.load(path);                 // second launch

        assertEquals(AaMethod.OFF, FornaxConfig.get().aaMethod);
    }

    @Test
    void metalHudChoiceFromTheFirstSessionSurvivesASecondLaunch() {
        // A "toggle works this session, gone after restart" bug is an apply-ordering defect in
        // FornaxSettingsScreen, not a persistence bug in this class -- but metalHud has no round-trip
        // test of its own the way aaMethod does. This pins the half of the contract this class
        // actually owns: mirrors aaMethodChoiceFromTheFirstSessionSurvivesASecondLaunch exactly,
        // substituting metalHud.
        Path path = configDir.resolve("fornax.json");
        FornaxConfig.install(new FornaxSettings());
        FornaxConfig.load(path);           // first launch, fresh install
        FornaxConfig.get().metalHud = true; // user turns the HUD on
        FornaxConfig.save(path);           // settings screen close

        FornaxConfig.install(new FornaxSettings()); // simulated fresh JVM
        FornaxConfig.load(path);           // second launch

        assertEquals(true, FornaxConfig.get().metalHud);
    }

    @Test
    void voxelReachIgnoresRenderDistanceChoiceFromTheFirstSessionSurvivesASecondLaunch() {
        // Mirrors metalHudChoiceFromTheFirstSessionSurvivesASecondLaunch exactly -- new field
        // introduced by the same live fix that added SettingsApplyRouter's diff entry for it
        // (2026-07-26), pinning the persistence half of that contract.
        Path path = configDir.resolve("fornax.json");
        FornaxConfig.install(new FornaxSettings());
        FornaxConfig.load(path);
        FornaxConfig.get().voxelReachIgnoresRenderDistance = true;
        FornaxConfig.save(path);

        FornaxConfig.install(new FornaxSettings());
        FornaxConfig.load(path);

        assertEquals(true, FornaxConfig.get().voxelReachIgnoresRenderDistance);
    }

    @Test
    void legacyFileWithSupersamplingStillMigratesThroughTheRealLoadPath() throws IOException {
        // Guards the wiring fix's other edge: stamping fresh files must NOT stop a genuinely legacy
        // file (no schemaVersion/aaMethod keys at all) from migrating on its first load.
        Path path = configDir.resolve("fornax.json");
        Files.writeString(path, "{\"shadersEnabled\": true, \"ssaaPreset\": \"X2\"}");
        FornaxConfig.install(new FornaxSettings());

        FornaxConfig.load(path);

        assertEquals(AaMethod.SSAA, FornaxConfig.get().aaMethod);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, FornaxConfig.get().schemaVersion);
    }

    @Test
    void savedX9FactorMigratesToX4AndIsRewrittenOnDisk() throws IOException {
        // Real-Gson leg of the X9 removal: "X9" no longer names an enum constant, so Gson maps it
        // to null through the actual load path; migration normalizes to X4 and the schema bump
        // triggers load()'s save-on-change, so the stale "X9" is rewritten on disk exactly once.
        Path path = configDir.resolve("fornax.json");
        Files.writeString(path, "{\"aaMethod\": \"SSAA\", \"ssaaPreset\": \"X9\", \"schemaVersion\": 2}");
        FornaxConfig.install(new FornaxSettings());

        FornaxConfig.load(path);

        assertEquals(AaMethod.SSAA, FornaxConfig.get().aaMethod);
        assertEquals(SsaaPreset.X4, FornaxConfig.get().ssaaPreset);
        FornaxSettings onDisk = new Gson().fromJson(Files.readString(path), FornaxSettings.class);
        assertEquals(SsaaPreset.X4, onDisk.ssaaPreset);
        assertEquals(FornaxSettings.CURRENT_SCHEMA_VERSION, onDisk.schemaVersion);
    }
}
