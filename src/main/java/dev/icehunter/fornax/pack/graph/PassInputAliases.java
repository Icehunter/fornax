package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Names a fullscreen pass's inputs, so a shader can say what it is reading.
 *
 * <p>A pass's inputs arrive as samplers named by POSITION: {@code u_Input0}, {@code u_Input1}, and
 * so on up to however many the pass declares. Nothing in the shader says which target sits at which
 * number, so a reader has to hold the pass's {@code inputs} list from graph.toml in their head
 * while reading, and a writer who inserts an input in the middle silently hands every later sampler
 * a different texture with no error anywhere. That second one is the worst failure this pack has.
 *
 * <p>This writes one line per input:
 *
 * <pre>#define u_voxelLocalRadiance u_Input7</pre>
 *
 * <p>so the shader reads the name and an inserted input turns into an undefined identifier, which
 * the compiler catches.
 *
 * <p><b>A shader can serve more than one pass.</b> The seven bloom blurs share one file and each
 * binds a different target, so a name is written only where EVERY pass using that shader binds the
 * same target at that position. The rest keep their number, which is honest: there is no one name
 * for them.
 *
 * <p><b>An option's name wins.</b> A pack option arrives as {@code u_Exposure} and a target called
 * {@code exposure} would ask for the same name, which would leave a sampler standing where a
 * number belongs and a syntax error a long way from the cause. A name already taken is skipped.
 */
public final class PassInputAliases {
    private PassInputAliases() {
    }

    /** Shader path to the block of defines it gets, for every shader that has one.
     *
     * @param reserved names already spoken for, which is every pack option
     */
    public static Map<String, String> byShader(GraphSpec graph, Set<String> reserved) {
        Map<String, List<PassSpec>> byShader = new LinkedHashMap<>();
        for (PassSpec pass : graph.passes()) {
            if (pass.type() != PassType.FULLSCREEN || pass.shader() == null) {
                continue;
            }
            byShader.computeIfAbsent(pass.shader(), key -> new ArrayList<>()).add(pass);
        }
        Map<String, String> out = new LinkedHashMap<>();
        byShader.forEach((shader, passes) -> {
            String block = defines(passes, reserved);
            if (!block.isEmpty()) {
                out.put(shader, block);
            }
        });
        return Map.copyOf(out);
    }

    private static String defines(List<PassSpec> passes, Set<String> reserved) {
        int shortest = Integer.MAX_VALUE;
        for (PassSpec pass : passes) {
            shortest = Math.min(shortest, pass.inputs().size());
        }
        StringBuilder out = new StringBuilder();
        Set<String> taken = new LinkedHashSet<>(reserved);
        for (int index = 0; index < shortest; index++) {
            String agreed = agreedInput(passes, index);
            if (agreed == null) {
                continue;
            }
            String alias = alias(agreed);
            // Two inputs that reduce to one name would each hide the other. Neither gets one.
            if (alias == null || !taken.add(alias)) {
                continue;
            }
            out.append("#define ").append(alias).append(" u_Input").append(index).append('\n');
        }
        return out.toString();
    }

    private static String agreedInput(List<PassSpec> passes, int index) {
        String first = passes.getFirst().inputs().get(index);
        for (PassSpec pass : passes) {
            if (!pass.inputs().get(index).equals(first)) {
                return null;
            }
        }
        return first;
    }

    /**
     * A target name as a GLSL identifier. {@code builtin.} says where a target comes from rather
     * than what it holds, so it goes; a remaining dot becomes an underscore, and anything else
     * outside a letter, digit or underscore does too.
     */
    static String alias(String input) {
        String name = input.startsWith("builtin.") ? input.substring("builtin.".length()) : input;
        StringBuilder id = new StringBuilder(name.length() + 2);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            id.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        String body = id.toString();
        if (body.isEmpty() || !Character.isLetter(body.charAt(0))) {
            return null;
        }
        return "u_" + Character.toUpperCase(body.charAt(0)) + body.substring(1);
    }

    /** Every alias a pass's own inputs produce, in order, for a reader or a check. */
    public static List<String> aliasesOf(PassSpec pass) {
        List<String> out = new ArrayList<>(pass.inputs().size());
        for (String input : pass.inputs()) {
            String alias = alias(input);
            out.add(alias == null ? "" : alias);
        }
        return List.copyOf(out);
    }
}
