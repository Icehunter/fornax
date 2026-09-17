package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.rt.RayTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level, deliberately: the kernel this covers needs a live Metal device to execute, and CI
 * runs on Linux where every device-gated test in this package is skipped. The pairing it checks is
 * the silent one. The kernel's constant struct and the Java writer's byte offsets are two halves of
 * one ABI with no compiler between them, so a struct member added on one side only produces a
 * dispatch that reads the tier out of whatever follows the allocation.
 */
class MeshShadowKernelContractTest {

    private static final Path KERNEL =
            Path.of("src/main/resources/assets/fornax/shaders_engine/rt_mesh_shadow.metal");
    private static final Path TRACER =
            Path.of("src/main/java/dev/icehunter/fornax/metalfx/rt/MeshShadowTracer.java");

    @Test
    void theConstantStructCarriesTheTierAfterTheFilterGuard() throws IOException {
        String source = Files.readString(KERNEL);
        int guard = source.indexOf("float filterGuardUv;");
        int tier = source.indexOf("uint tier;");
        assertTrue(guard > 0, "the constant struct must still declare filterGuardUv");
        assertTrue(tier > guard,
                "tier is appended after filterGuardUv; inserting it earlier moves every later "
                        + "offset the Java writer hardcodes");
    }

    /**
     * G and A leave the kernel in one store. Writing them separately, or writing the tier on a
     * skipped ray, would let a reader that trusts G see a tier for a texel raster still owns.
     */
    @Test
    void aTracedTexelWritesTheTierIntoGreenAndASkippedRayWritesZero() throws IOException {
        String source = Files.readString(KERNEL);
        assertTrue(source.contains("output.write(float4(depth,float(c.tier),0.0,1.0),pixel);"),
                "the traced write must carry the tier in G and validity in A");
        assertTrue(source.contains("output.write(float4(depth,0.0,0.0,0.0),pixel)"),
                "a skipped ray must keep writing zero tier and zero validity");
        assertTrue(source.contains("output.write(float4(1.0,0.0,0.0,0.0),pixel)"),
                "mesh_shadow_clear must keep writing a miss depth with no tier and no validity");
    }

    /**
     * 176 and 160 are not independent numbers: 160 is where the tier lands after the two matrices,
     * the camera float4 and the four trailing scalars, and 176 is that rounded up to the struct's
     * 16-byte alignment. Both are asserted here because the failure is a wrong tier, not a crash.
     */
    @Test
    void theJavaWriterAllocatesOneHundredSeventySixBytesAndPutsTheTierAtByteOneHundredSixty()
            throws IOException {
        String source = Files.readString(TRACER);
        assertTrue(source.contains("CONSTANT_BYTES=176"),
                "the allocation must cover the struct including its tail padding");
        assertTrue(source.contains("constants.set(ValueLayout.JAVA_INT,160,RayTier.HARDWARE_MESH.ordinal())"),
                "the tier word must be written at byte 160 from the enum, not from a literal");
    }

    /** The value that ends up in G, pinned so a reordered enum shows up here and not in a frame. */
    @Test
    void theMeshTracerReportsTierThree() {
        assertTrue(RayTier.HARDWARE_MESH.ordinal() == 3,
                "rtTerrainShadowDepth readers compare G against 3 for exact mesh geometry");
    }
}
