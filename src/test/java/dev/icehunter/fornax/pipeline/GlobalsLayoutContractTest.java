package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the std140 size of the {@code u_Globals} block declared in {@code globals.glsl} to the buffer
 * size {@code UniformBufferManagerMixin} allocates for it.
 *
 * <p>These two are written by different people at different times in different languages, and nothing
 * mechanically ties them together. When they disagree, every member past the divergence is read from
 * the wrong offset -- with no compile error, no validation failure, and no log line. The symptom is a
 * uniform that silently holds a neighbouring field's bytes, which reads as "this feature does nothing"
 * and has cost real debugging time on this codebase before.
 *
 * <p>The same arithmetic also catches the vec3 trap: Mojang's {@code Std140Builder.putVec3} pads a
 * vec3 out to a full 16 bytes, while GLSL lets a following member with smaller alignment sit at
 * offset+12. A scalar placed directly after a vec3 therefore lands at a different offset on each side.
 * Computing the block size under GLSL's rules and comparing it to what Java allocates surfaces that
 * divergence as a size mismatch instead of as a mysteriously dead uniform.
 */
class GlobalsLayoutContractTest {

    private static final Path GLOBALS =
            Path.of("src/main/resources/assets/fornax/shaders/include/globals.glsl");
    private static final Path MANAGER =
            Path.of("src/main/java/dev/icehunter/fornax/mixin/sodium/UniformBufferManagerMixin.java");

    /** GLSL std140: {alignment, size}. vec3 is the notable one -- aligns to 16 but occupies only 12. */
    private static int[] rulesFor(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> new int[] {4, 4};
            case "vec2", "ivec2" -> new int[] {8, 8};
            case "vec3", "ivec3" -> new int[] {16, 12};
            case "vec4", "ivec4" -> new int[] {16, 16};
            case "mat4" -> new int[] {16, 64};
            default -> throw new IllegalArgumentException(
                    "globals.glsl declares a type this contract check does not model: " + type
                            + " -- add its std140 alignment/size rather than removing the check");
        };
    }

    @Test
    void declaredBlockSizeMatchesTheAllocatedBufferSize() throws IOException {
        List<String[]> members = declaredMembers();
        assertTrue(members.size() >= 10, "parsed suspiciously few u_Globals members: " + members.size());

        int offset = 0;
        for (String[] member : members) {
            int[] rules = rulesFor(member[0]);
            offset = align(offset, rules[0]) + rules[1];
        }
        int declaredSize = align(offset, 16); // a std140 block rounds up to a vec4 boundary

        assertEquals(allocatedBufferSize(), declaredSize,
                "globals.glsl's u_Globals block computes to " + declaredSize + " bytes but"
                        + " UniformBufferManagerMixin allocates " + allocatedBufferSize() + ". Every member"
                        + " past the divergence will read the wrong bytes, silently. Members parsed: "
                        + members.stream().map(m -> m[0] + " " + m[1]).toList());
    }

    private static int align(int offset, int alignment) {
        return (offset + alignment - 1) / alignment * alignment;
    }

    /** Members of the {@code u_Globals} block, in declaration order, as {type, name}. */
    private static List<String[]> declaredMembers() throws IOException {
        String source = Files.readString(GLOBALS);
        int start = source.indexOf("uniform u_Globals {");
        assertTrue(start >= 0, "could not find the u_Globals block in " + GLOBALS);
        String body = source.substring(start, source.indexOf("};", start));

        // Strip comments first: the block is heavily commented, and a type keyword inside prose would
        // otherwise be parsed as a member.
        body = body.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");

        Matcher m = Pattern.compile("\\b(float|int|uint|bool|vec2|vec3|vec4|ivec2|ivec3|ivec4|mat4)\\s+(\\w+)\\s*;")
                .matcher(body);
        List<String[]> members = new ArrayList<>();
        while (m.find()) {
            members.add(new String[] {m.group(1), m.group(2)});
        }
        return members;
    }

    private static int allocatedBufferSize() throws IOException {
        Matcher m = Pattern.compile("return\\s+(\\d+);").matcher(Files.readString(MANAGER));
        assertTrue(m.find(), "could not find the allocated u_Globals size in " + MANAGER);
        return Integer.parseInt(m.group(1));
    }
}
