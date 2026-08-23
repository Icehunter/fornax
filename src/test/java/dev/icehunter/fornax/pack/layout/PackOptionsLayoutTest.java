package dev.icehunter.fornax.pack.layout;

import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PackOptionsLayoutTest {
    @Test
    void allFloatOptionsPackDenselyAndRoundBlockSizeUpTo16() {
        PackOptionsLayout layout = PackOptionsLayout.build(List.of(
                runtimeFloat("A"), runtimeFloat("B"), runtimeFloat("C")));

        assertEquals(0, layout.offsets().get("A"));
        assertEquals(4, layout.offsets().get("B"));
        assertEquals(8, layout.offsets().get("C"));
        assertEquals(16, layout.blockSize());
    }

    @Test
    void scalarAfterVec3LandsAtNext16ByteBoundaryNotInPadding() {
        PackOption a = runtimeFloat("A");
        PackOption b = runtimeFloat("B");
        PackOption c = runtimeFloat("C");

        // Test-only seam: B is treated as a synthetic vec3 so the std140 math is provable without
        // waiting on a real vec-typed runtime option (v0.1's real options are all floats).
        PackOptionsLayout layout = PackOptionsLayout.build(List.of(a, b, c),
                o -> o.name().equals("B") ? "vec3" : "float");

        assertEquals(0, layout.offsets().get("A"));
        assertEquals(16, layout.offsets().get("B"));
        // The scalar-after-vec3 rule: C must NOT land at 28 (vec3's trailing 4 bytes of padding).
        assertEquals(32, layout.offsets().get("C"));
        assertEquals(48, layout.blockSize());
    }

    @Test
    void nonRuntimeOptionsAreExcludedFromTheLayout() {
        PackOption runtime = runtimeFloat("RUNTIME_ONE");
        PackOption compile = new PackOption("COMPILE_ONE", OptionType.COMPILE, null, List.of("0", "1"),
                false, false, "0", "Compile One", Map.of());

        PackOptionsLayout layout = PackOptionsLayout.build(List.of(compile, runtime));

        assertEquals(1, layout.offsets().size());
        assertEquals(0, layout.offsets().get("RUNTIME_ONE"));
        assertNull(layout.offsets().get("COMPILE_ONE"));
    }

    @Test
    void glslBlockContainsHeaderAndOneMemberLinePerOption() {
        PackOptionsLayout layout = PackOptionsLayout.build(List.of(
                runtimeFloat("SSAO_RADIUS"), runtimeFloat("SSR_STRENGTH")));

        String glsl = layout.glslBlock();

        assertTrue(glsl.contains("layout(std140) uniform u_PackOptions"));
        assertTrue(glsl.contains("float SSAO_RADIUS;"));
        assertTrue(glsl.contains("float SSR_STRENGTH;"));
        // FULLSCREEN passes resolve u_PackOptions by name in their Blaze3D bind group, not by a
        // positional descriptor binding -- so the no-arg overload must never emit one.
        assertFalse(glsl.contains("binding ="));
    }

    // --- Part C1: glslBlock(int binding) for COMPUTE passes' positional descriptor binding ------

    @Test
    void glslBlockWithBindingEmitsExplicitSetAndBindingQualifier() {
        PackOptionsLayout layout = PackOptionsLayout.build(List.of(runtimeFloat("SSAO_RADIUS")));

        String glsl = layout.glslBlock(3);

        assertTrue(glsl.contains("layout(std140, set = 0, binding = 3) uniform u_PackOptions"),
                "compute pass's block must declare the exact binding number packOptions resolves to");
        assertTrue(glsl.contains("float SSAO_RADIUS;"));
    }

    @Test
    void glslBlockEmitsExplicitOffsets() {
        // build with a vec3 followed by a float via the typeOf seam
        List<PackOption> opts = List.of(runtimeFloat("a"), runtimeFloat("b"), runtimeFloat("c"));
        PackOptionsLayout layout = PackOptionsLayout.build(opts,
                o -> o.name().equals("b") ? "vec3" : "float");
        String glsl = layout.glslBlock();

        assertTrue(glsl.contains("layout(offset = 0) float a;"));
        assertTrue(glsl.contains("layout(offset = 16) vec3 b;"));
        assertTrue(glsl.contains("layout(offset = 32) float c;"));
        assertTrue(glsl.contains("#extension GL_ARB_enhanced_layouts"));
    }

    private static PackOption runtimeFloat(String name) {
        return new PackOption(name, OptionType.RUNTIME, null, List.of(), false, false, "0.0", name, Map.of());
    }
}
