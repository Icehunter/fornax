package dev.icehunter.fornax.pack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@link PackSwitch#apply} needs a live {@code Minecraft} instance, a real shaderpacks directory,
 * and (for a successful rebuild) a GPU device to exercise directly, the same constraint that makes
 * {@code BuiltinResolutionContractTest} a source-level test. Pins the failure paths structurally
 * instead: the old pack's filesystem must not be closed before the switch is known to proceed, and
 * a rebuild failure must roll {@code GraphRunner} back with {@code GraphRunner.unload()}, the same
 * way {@code PackReload.reload()}'s identical catch does.
 */
class PackSwitchContractTest {
    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax/pack/PackSwitch.java");

    @Test
    void oldPackFilesystemIsNotClosedBeforeTheTargetPackIsResolved() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static boolean apply(");
        assertTrue(methodStart >= 0, "PackSwitch.apply must still exist");
        String method = source.substring(methodStart);

        int branchStart = method.indexOf("if (targetPackName.isEmpty())");
        assertTrue(branchStart >= 0, "must still branch on an empty target name");

        String beforeBranch = method.substring(0, branchStart);
        assertFalse(beforeBranch.contains("closeIfCustomFileSystem("),
                "the old pack's filesystem must not be closed until the switch is known to proceed:"
                        + " closing it unconditionally up front leaves GraphRunner.currentPack()"
                        + " pointing at an already-closed filesystem when the target pack turns out"
                        + " not to exist, since that path never touches GraphRunner at all");
    }

    @Test
    void pickedPackNotFoundKeepsTheOldPackActiveAndLogsRatherThanSilentlyReverting() throws IOException {
        String source = Files.readString(SOURCE);
        int nullCheck = source.indexOf("if (picked == null) {");
        assertTrue(nullCheck >= 0, "must still check for a target pack that failed to resolve");
        String nullBranch = source.substring(nullCheck, source.indexOf("return false;", nullCheck) + "return false;".length());

        assertFalse(nullBranch.contains("closeIfCustomFileSystem("),
                "the still-active old pack must not have its filesystem closed just because the"
                        + " new target pack was not found: nothing is wrong with the old pack");
        assertFalse(nullBranch.contains("GraphRunner.unload()"),
                "the old pack must keep rendering: GraphRunner is never touched on this path");
        assertTrue(nullBranch.contains("FornaxMod.LOGGER.warn("),
                "a target pack vanishing must be logged, not silently reverted with no trace");
    }

    @Test
    void rebuildFailureRollsGraphRunnerBackWithUnload() throws IOException {
        String source = Files.readString(SOURCE);
        int catchStart = source.indexOf("} catch (FornaxPackError e) {");
        assertTrue(catchStart >= 0, "must still catch a rebuild failure");
        int catchEnd = source.indexOf("return false;", catchStart) + "return false;".length();
        String catchBlock = source.substring(catchStart, catchEnd);

        assertTrue(catchBlock.contains("GraphRunner.unload()"),
                "GraphRunner.rebuild mutates its statics before its final resolve step, so a"
                        + " failure partway through can leave currentPack/registry/compileValues"
                        + " pointing at the broken new pack; unload() must roll it back to inactive,"
                        + " the same way PackReload.reload()'s identical catch already does");
        assertTrue(catchBlock.contains("settings.activePack = previousPackName;"),
                "the persisted pack name must still revert on failure");
        assertTrue(catchBlock.contains("settings.shadersEnabled = false;"),
                "shaders must still be forced off on a rebuild failure");
    }

    @Test
    void rendererReloadAfterAFailureIsChainedOnUnloadsFutureNeverCalledDirectly() throws IOException {
        // This class's own doc names it "THE RENDER-STATE LATCH LAW": RendererReload.request() must
        // never run before the resource-reload future it depends on has landed, or the terrain
        // pipelines resync against vanilla-override state that hasn't finished clearing yet.
        // Live-caught testing the unload() fix above: calling request() directly right after unload()
        // produced stale, ghosted terrain geometry on the very frame this catch was meant to fall back
        // cleanly to vanilla.
        String source = Files.readString(SOURCE);
        int catchStart = source.indexOf("} catch (FornaxPackError e) {");
        assertTrue(catchStart >= 0, "must still catch a rebuild failure");
        int catchEnd = source.indexOf("return false;", catchStart) + "return false;".length();
        String catchBlock = source.substring(catchStart, catchEnd);

        assertFalse(catchBlock.contains("RendererReload.request();"),
                "RendererReload.request() must never be called directly in this catch, only chained"
                        + " on unload()'s returned future");
        assertTrue(catchBlock.contains(".thenRunAsync(RendererReload::request,"),
                "the renderer reload must be chained on unload()'s future, the same way the"
                        + " successful-apply path at the bottom of this method already does");
    }
}
