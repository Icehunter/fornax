package dev.icehunter.fornax.config;

import com.google.gson.Gson;
import dev.icehunter.fornax.voxel.BrickGridUpload;
import dev.icehunter.fornax.voxel.SectionHarvester;
import dev.icehunter.fornax.voxel.VoxelFaceTexture;
import dev.icehunter.fornax.voxel.VoxelPaletteLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VoxelPaletteCapacitySettingsTest {
    @TempDir Path directory;
    @AfterEach void reset() { FornaxConfig.install(new FornaxSettings()); }

    @Test void earlyClassLoadingCannotFreezeDefaultBeforeConfigAndSavingRequiresRestart() throws Exception {
        FornaxConfig.install(new FornaxSettings());
        assertEquals(96, SectionHarvester.maxPaletteEntries());
        assertEquals(96 * 64L, BrickGridUpload.paletteBytesPerSlot());
        assertEquals(96 * 168, VoxelFaceTexture.bytesPerSlot());
        Path path = directory.resolve("fornax.json");
        for (var tier : VoxelPaletteCapacity.values()) {
            Files.writeString(path, "{\"voxelPaletteCapacity\":\"" + tier.name() + "\"}");
            FornaxConfig.load(path);
            int capacity = tier.entries();
            assertEquals(capacity, VoxelPaletteLayout.entries());
            assertEquals(capacity * 64L, BrickGridUpload.paletteBytesPerSlot());
            assertEquals(capacity * 168, VoxelFaceTexture.bytesPerSlot());
            FornaxConfig.get().voxelPaletteCapacity = VoxelPaletteCapacity.ENTRIES_96;
            FornaxConfig.save(path);
            assertEquals(capacity, VoxelPaletteLayout.entries(), "save must not change live storage");
        }
        FornaxConfig.load(path);
        assertEquals(96, VoxelPaletteLayout.entries());
    }

    @Test void missingUnknownAndNullValuesKeepThePreviousAllocationDefault() {
        for (String json : new String[]{"{}", "{\"voxelPaletteCapacity\":null}",
                "{\"voxelPaletteCapacity\":\"REMOVED\"}"}) {
            var settings = FornaxSettings.migrate(new Gson().fromJson(json, FornaxSettings.class));
            assertEquals(VoxelPaletteCapacity.ENTRIES_96, settings.voxelPaletteCapacity);
        }
    }

    @Test void changingCapacitySavesWithoutReapplyingTheLiveGraph() {
        var before = new FornaxSettings();
        var after = new FornaxSettings();
        after.voxelPaletteCapacity = VoxelPaletteCapacity.ENTRIES_240;
        assertEquals(Set.of(SettingsApplyRouter.Action.SAVE_ONLY), SettingsApplyRouter.route(before, after));
    }
}
