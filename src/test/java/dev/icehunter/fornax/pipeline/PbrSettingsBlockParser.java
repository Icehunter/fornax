package dev.icehunter.fornax.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the member names of a GLSL {@code layout(std140) uniform u_PbrSettings { ... }} block out of
 * shader source, in declaration order.
 *
 * <p>Test-only, and shared by {@code PbrSettingsLayoutTest} (Fornax's own built-in fallback shader
 * and the bundled fixtures) and {@code PlaguePackLoadsTest} (the Plague pack's real terrain shader),
 * so the two cannot disagree about what "the block's members" means.
 *
 * <p>Deliberately a small hand-rolled scan rather than anything general: it exists to catch a
 * mismatch between two hand-maintained lists, so it must not itself be clever enough to paper over
 * one. It reads only simple {@code float name;} lines -- every member of this block is a float by
 * construction, since std140 scalar packing is the whole reason the block can be extended without
 * moving existing offsets -- and it FAILS LOUDLY on anything else inside the braces rather than
 * skipping it, because a silently-skipped {@code vec4} would shift every subsequent offset and is
 * exactly the corruption this parser is used to detect.
 */
public final class PbrSettingsBlockParser {

    private static final Pattern OPEN =
            Pattern.compile("^\\s*layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+u_PbrSettings\\s*\\{\\s*$");
    private static final Pattern MEMBER = Pattern.compile("^\\s*float\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;\\s*$");

    private PbrSettingsBlockParser() {}

    /**
     * @return the block's member names in declaration order, or an empty list if the file declares no
     *         such block (which is legal: most shaders do not).
     */
    public static List<String> membersOf(Path shader) throws IOException {
        List<String> lines = Files.readAllLines(shader);
        List<String> members = new ArrayList<>();
        boolean inBlock = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!inBlock) {
                if (OPEN.matcher(line).matches()) {
                    inBlock = true;
                }
                continue;
            }
            String trimmed = line.strip();
            if (trimmed.startsWith("};")) {
                return members;
            }
            // Comments and blank lines carry the reasoning and are expected throughout.
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            Matcher m = MEMBER.matcher(line);
            if (!m.matches()) {
                throw new IllegalStateException(shader + ":" + (i + 1)
                        + " -- u_PbrSettings may contain only `float name;` members, found: " + trimmed
                        + ". Any other type changes std140 offsets for every member after it, which"
                        + " silently corrupts the two shorter declarations of this same block.");
            }
            members.add(m.group(1));
        }
        if (inBlock) {
            throw new IllegalStateException(shader + " -- unterminated u_PbrSettings block");
        }
        return List.of();
    }
}
