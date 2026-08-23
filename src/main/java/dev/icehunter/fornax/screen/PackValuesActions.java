package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.PackValuesFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

/**
 * Session-free Import / Export / Reset actions for a pack's values file, shared by the YACL {@link
 * PackManageScreen}. The old bespoke {@link PackSettingsScreen} carried session-STAGED versions of
 * these that it committed later on Apply/Done; the manage screen has no staging surface, so each
 * mutating action here persists and rebuilds IMMEDIATELY.
 *
 * <p>Both mutating actions route their commit through a throwaway {@link PackEditSession} rather than
 * a hand-rolled apply: {@link PackEditSession#apply()} already encodes the render-state-latch-safe
 * "persist the per-pack values file, then AT MOST one {@code GraphRunner.rebuild} (a compile option
 * changed -- shader text differs) or one runtime-buffer resync (sliders only), plus one renderer
 * reload where pipelines are affected" semantics. Reusing it keeps this helper byte-identical to the
 * tested path and never forks the apply logic (latch law).
 */
public final class PackValuesActions {
    private PackValuesActions() {}

    /**
     * Native save dialog, then writes the pack's CURRENT merged values (on-disk file merged with each
     * option's own default) to the chosen file. Side-effect free -- no rebuild. The manage screen has
     * no staging, so "current settings" is the merged on-disk state, not a live edit session.
     */
    public static void export(PackModel model) {
        String defaultDir = PackSettingsSupport.valuesPath(model).getParent().toString();
        String defaultPath = defaultDir + File.separator + model.meta().name() + "-export.txt";
        String chosen;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filter = stack.mallocPointer(1);
            filter.put(stack.UTF8("*.txt"));
            filter.flip();
            chosen = TinyFileDialogs.tinyfd_saveFileDialog(
                    "Export Settings", defaultPath, filter, "Settings files (*.txt)");
        }
        if (chosen == null) {
            return; // user cancelled
        }
        PackValuesFile.save(Path.of(chosen), PackSettingsSupport.mergedValues(model));
    }

    /**
     * Native open dialog, then stages every recognized value from the chosen file and commits it with
     * one rebuild. Unknown keys are dropped by {@link PackValuesFile#load} (pack-drift tolerance).
     */
    public static void importSettings(PackModel model) {
        String defaultDir = PackSettingsSupport.valuesPath(model).getParent().toString();
        String chosen;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filter = stack.mallocPointer(1);
            filter.put(stack.UTF8("*.txt"));
            filter.flip();
            chosen = TinyFileDialogs.tinyfd_openFileDialog(
                    "Import Settings", defaultDir + File.separator, filter, "Settings files (*.txt)", false);
        }
        if (chosen == null) {
            return; // user cancelled
        }
        Map<String, String> loaded = PackValuesFile.load(Path.of(chosen), model.options());
        PackEditSession session = new PackEditSession(model);
        session.stageAll(loaded); // one combined runtime-preview write (ring-safe -- see stageAll's doc)
        session.apply();          // persist + at most one rebuild
    }

    /**
     * Vanilla {@link ConfirmScreen} first; on confirm, stages every option back to its pack default
     * and commits with one rebuild. BOTH the confirm and cancel paths return to {@code returnScreen}
     * (the caller passes a freshly-built manage screen -- fresh-parent law).
     */
    public static void resetToDefaults(Screen returnScreen, PackModel model) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        PackEditSession session = new PackEditSession(model);
                        session.stageDefaults();
                        session.apply();
                    }
                    minecraft.gui.setScreen(returnScreen);
                },
                Component.translatable("gui.fornax.manage.reset.title"),
                Component.translatable("gui.fornax.manage.reset.body")));
    }
}
