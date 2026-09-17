package dev.icehunter.fornax.metalfx.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level, deliberately: this kernel needs a live Metal device, and CI runs on Linux where
 * every device-gated test in this package is skipped. What it pins is the halves of the ABI that no
 * compiler compares. {@link RayQueryAbi}'s word constants and the kernel's own struct are two
 * descriptions of the same 32 bytes, and a member added, moved or retyped on one side produces
 * records read at the wrong offsets with nothing to report it.
 */
class RayQueryKernelContractTest {

    private static final Path KERNEL =
            Path.of("src/main/resources/assets/fornax/shaders_engine/rt_ray_query.metal");

    private static String source() throws IOException {
        return Files.readString(KERNEL);
    }

    /** Member order in the struct is the word order in the ABI; there is nothing else tying them. */
    @Test
    void theHitStructDeclaresItsMembersInTheOrderTheWordConstantsName() throws IOException {
        String source = source();
        // Scoped to the RayHit struct: the constants block declares a tier of its own earlier in
        // the file, and a whole-file search would find that one and compare the wrong offsets.
        int start = source.indexOf("struct RayHit {");
        assertTrue(start > 0, "rt_ray_query.metal must declare a RayHit struct");
        String struct = source.substring(start, source.indexOf("};", start));
        List<String> members = List.of(
                "float distance;", "uint flags;", "uint surface;", "uint atlasUv;",
                "packed_float3 normal;", "uint tier;");
        int previous = -1;
        for (String member : members) {
            int at = struct.indexOf(member);
            assertTrue(at > previous,
                    member + " must appear after " + (previous < 0 ? "the struct opening" : "the "
                            + "member before it") + "; member order is the ABI's word order");
            previous = at;
        }
    }

    /**
     * packed_float3, not float3. A plain float3 member carries 16 bytes of size in MSL, which would
     * put the tier at byte 32 and make the record 48 bytes while RayQueryAbi still declared 8
     * words. Everything past the normal would read at the wrong offset and nothing would say so.
     */
    @Test
    void theNormalIsPackedSoTheTierStaysInsideTheThirtyTwoByteRecord() throws IOException {
        assertTrue(source().contains("packed_float3 normal;"),
                "an unpacked float3 would push the tier out of the record");
        assertEquals(32L, RayQueryAbi.hitByteSize(1),
                "the record the kernel writes is 32 bytes; this is the Java side of the same claim");
    }

    /** The version the kernel implements and the version callers declare are one number. */
    @Test
    void theKernelAcceptsExactlyTheAbiVersionThisBuildDeclares() throws IOException {
        assertTrue(source().contains("constants.abiVersion != " + RayQueryAbi.ABI_VERSION + "u"),
                "the kernel must refuse every version but " + RayQueryAbi.ABI_VERSION);
    }

    /**
     * Both write paths set the tier, so a dispatched ray is never indistinguishable from an
     * untouched buffer, and a version mismatch is never indistinguishable from a traced miss.
     */
    @Test
    void everyWritePathSetsTheTierAndTheVersionMismatchPathSetsItToZero() throws IOException {
        String source = source();
        assertTrue(source.contains("miss.tier = constants.tier;"),
                "a traced miss is an answer and carries the tracing tier");
        assertTrue(source.contains("hit.tier = constants.tier;"),
                "a hit carries the tracing tier");
        assertTrue(source.contains("unanswered.tier = 0u;"),
                "a version mismatch writes tier zero, which reads as unanswered under every layout");
    }

    /** The constants block carries the tier; a kernel reading it from elsewhere has no source. */
    @Test
    void theConstantsBlockCarriesTheTierInItsThirdWord() throws IOException {
        String source = source();
        int rayCount = source.indexOf("uint rayCount;");
        int tier = source.indexOf("uint tier;", source.indexOf("struct RayQueryConstants"));
        assertTrue(rayCount > 0 && tier > rayCount,
                "the tier replaces the first padding word, after rayCount");
    }

    /**
     * The cascade's buffer-form discipline, and the clear it rests on.
     *
     * <p>A pack-declared hit buffer persists between frames, so without an engine clear at the start
     * of every ray_query pass, last frame's tier words would block this frame's trace entirely and
     * the pack would read a frozen answer that still looks valid.
     */
    @Test
    void fillModeSkipsAnsweredRecordsAndTheOwnedModeWritesThemAll() throws IOException {
        String source = source();
        assertTrue(source.contains("uint fillMode;"),
                "the constants block must carry the fill flag");
        assertTrue(source.contains("if (constants.fillMode != 0u && hits[index].tier != 0u) {"),
                "a filling dispatch must leave an answered record alone");

        int guard = source.indexOf("if (constants.fillMode != 0u && hits[index].tier != 0u)");
        int trace = source.indexOf("query.reset(r, accelerationStructure, params);");
        assertTrue(guard > 0 && guard < trace,
                "the skip must come before the traversal, or the tier pays for rays it discards");
    }
}
