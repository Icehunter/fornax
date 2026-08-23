package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.BufferSize;
import dev.icehunter.fornax.pack.graph.TargetKind;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code stride_bytes} x {@code count} size syntax for a pack-owned buffer target.
 *
 * <p>Every rejection here is pinned because its absence is silent, not because parsing is hard. A
 * buffer whose size is half-declared, zero, unaligned or absurd would either never be allocated at
 * all (nothing reads {@code stride_bytes} without {@code count}) or fail deep inside a per-frame
 * Vulkan call where the message names neither the file nor the target -- and a pack author would
 * see only "the pass does not run".
 */
class PackTomlLoaderBufferSizeTest {
    private static final String FILE = "graph.toml";

    private static GraphSpec load(String toml) {
        return PackTomlLoader.loadGraph(new StringReader(toml), FILE);
    }

    @Test
    void strideAndCountParseIntoTheTargetsDeclaredSize() {
        GraphSpec graph = load("""
                [targets.snowField]
                kind = "buffer"
                stride_bytes = 4
                count = 262144
                """);
        TargetSpec t = graph.targets().get("snowField");
        assertEquals(TargetKind.BUFFER, t.kind());
        BufferSize size = t.bufferSize();
        assertNotNull(size, "a declared stride/count pair is what makes the target pack-owned");
        assertEquals(4, size.strideBytes());
        assertEquals(262144, size.count());
        assertEquals(1048576L, size.sizeBytes());
    }

    @Test
    void aBufferWithNoSizeKeysStaysEngineOwned() {
        // The pre-existing shape every engine-injected buffer is declared with -- it must keep
        // parsing to a null size, or every shipped pack's voxel*/analyticLightList declarations
        // become pack-owned overnight and TargetPlan starts trying to allocate them at zero bytes.
        GraphSpec graph = load("""
                [targets.voxelOccupancy]
                kind = "buffer"
                """);
        assertNull(graph.targets().get("voxelOccupancy").bufferSize());
    }

    @Test
    void strideWithoutCountIsRejectedAndSaysWhy() {
        // The most dangerous of the malformed cases: a half-declared size reads as "engine-owned",
        // so without this the target is silently never allocated and the author's stride_bytes sits
        // in graph.toml looking like it did something.
        //
        // The REASON is asserted, not just the exception type. Deleting this check does not stop a
        // FornaxPackError from being thrown -- TomlSupport.requireInt still fails on the missing
        // sibling key -- it only degrades the message to a bare "missing required key 'count'",
        // which tells an author nothing about the pairing rule they just broke. Mutation-verified:
        // with the pairing check removed, the type assertion alone still passes.
        FornaxPackError error = assertThrows(FornaxPackError.class, () -> load("""
                [targets.snowField]
                kind = "buffer"
                stride_bytes = 4
                """));
        assertTrue(error.reason().contains("BOTH"), error.reason());
    }

    @Test
    void countWithoutStrideIsRejectedAndSaysWhy() {
        FornaxPackError error = assertThrows(FornaxPackError.class, () -> load("""
                [targets.snowField]
                kind = "buffer"
                count = 1024
                """));
        assertTrue(error.reason().contains("BOTH"), error.reason());
    }

    @Test
    void nonPositiveStrideOrCountIsRejected() {
        assertThrows(FornaxPackError.class, () -> load("""
                [targets.snowField]
                kind = "buffer"
                stride_bytes = 0
                count = 1024
                """));
        assertThrows(FornaxPackError.class, () -> load("""
                [targets.snowField]
                kind = "buffer"
                stride_bytes = 4
                count = -1
                """));
    }

    @Test
    void strideThatIsNotAMultipleOfFourIsRejected() {
        // TargetRegistry.ensureBufferSize passes the total size to vkCmdFillBuffer for the mandatory
        // allocation-time zero-clear, and the Vulkan spec requires that size to be a multiple of 4.
        // A 6-byte stride at an odd count produces an illegal size that only surfaces as a validation
        // -layer complaint (or nothing at all) on a live device.
        assertThrows(FornaxPackError.class, () -> load("""
                [targets.snowField]
                kind = "buffer"
                stride_bytes = 6
                count = 1025
                """));
    }

    @Test
    void sizePastThePerBufferCeilingIsRejected() {
        // 1024 x 1048577 > 1 GiB. Both factors are individually plausible; only the product is not,
        // which is exactly why the check has to be on the product.
        assertThrows(FornaxPackError.class, () -> load("""
                [targets.snowField]
                kind = "buffer"
                stride_bytes = 1024
                count = 1048577
                """));
        assertEquals(1L << 30, BufferSize.MAX_SIZE_BYTES);
    }

    @Test
    void aCountPastIntRangeIsAPackErrorNotAnArithmeticException() {
        // TomlSupport.requireInt's own Math.toIntExact throws a bare ArithmeticException, which
        // carries neither the file nor the key and escapes the loader entirely -- so a single stray
        // digit would surface as an unhandled crash instead of a named pack error.
        assertThrows(FornaxPackError.class, () -> load("""
                [targets.snowField]
                kind = "buffer"
                stride_bytes = 4
                count = 99999999999
                """));
    }

    @Test
    void sizeKeysOnATextureTargetAreRejected() {
        // Symmetric with the existing rule that format/scale/history/basis are refused on a buffer
        // target: both directions fall out of the same per-kind allowed-key set, so a misplaced key
        // is a load error rather than a silently inert one.
        assertThrows(FornaxPackError.class, () -> load("""
                [targets.sceneHdr]
                format = "rgba16f"
                scale = 1.0
                stride_bytes = 4
                count = 16
                """));
    }

    @Test
    void aSizedBufferMayStillCarryAnEnabledIfGate() {
        // The gate is what lets a pack-owned buffer cost nothing when its feature is compiled off --
        // TargetPlan drops it from the plan and TargetRegistry frees it. Pinned here so the size
        // keys and the gate cannot be made mutually exclusive by accident.
        GraphSpec graph = load("""
                [targets.snowField]
                kind = "buffer"
                stride_bytes = 4
                count = 16
                enabled_if = "SNOW_ACCUMULATION"
                """);
        TargetSpec t = graph.targets().get("snowField");
        assertEquals("SNOW_ACCUMULATION", t.enabledIf());
        assertNotNull(t.bufferSize());
    }
}
