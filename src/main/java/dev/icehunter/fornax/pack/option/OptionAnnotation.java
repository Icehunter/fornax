package dev.icehunter.fornax.pack.option;

import dev.icehunter.fornax.pack.FornaxPackError;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a single annotated {@code #define} line into a {@link PackOption}.
 *
 * <p>A line containing the {@code //[} marker is an annotation attempt: if it does not parse as a
 * well-formed declaration, that is a {@link FornaxPackError}, never a silent skip. Lines without
 * the marker (plain defines, ordinary comments, shader code) are not options and are ignored.
 */
public final class OptionAnnotation {
    // group 1: leading "//" (=> commented boolean, default off)
    // group 2: option name
    // group 3: optional value token
    // group 4: bracket body (range/values, may be empty)
    // group 5: runtime|compile
    // group 6: quoted label
    // group 7: optional {enum} body
    private static final Pattern DECL = Pattern.compile(
            "^\\s*(//\\s*)?#define\\s+(\\w+)(?:\\s+(\\S+))?\\s*//\\s*" +
                    "\\[(.*?)]\\s*(runtime|compile)\\s*\"([^\"]*)\"\\s*(\\{.*})?\\s*$");

    private static final Pattern ENUM_ENTRY = Pattern.compile("(\\S+?)=\"([^\"]*)\"");
    private static final Pattern DEFINE_NAME = Pattern.compile("#define\\s+(\\w+)");

    /** Presence of this marker makes a line an annotation attempt (subject to strict parsing). */
    private static final String MARKER = "//[";

    private OptionAnnotation() {}

    /** Parses without file/line context; errors carry an empty file and no line prefix. */
    public static Optional<PackOption> parseLine(String line) {
        return parseLine(line, "", 0);
    }

    public static Optional<PackOption> parseLine(String line, String file, int lineNumber) {
        Matcher m = DECL.matcher(line);
        if (!m.matches()) {
            if (line.contains(MARKER)) {
                throw error(file, nameOf(line), lineNumber,
                        "malformed option annotation, expected #define NAME [value] //[range-or-values]"
                                + " runtime|compile \"Label\": " + line.trim());
            }
            return Optional.empty();
        }

        boolean commented = m.group(1) != null;
        String name = m.group(2);
        String value = m.group(3);
        String bracket = m.group(4).trim();
        OptionType type = m.group(5).equals("runtime") ? OptionType.RUNTIME : OptionType.COMPILE;
        String label = m.group(6);
        String enumBody = m.group(7);

        if (bracket.isEmpty()) {
            // Boolean toggle: no value token; presence of leading // sets default off.
            if (value != null) {
                throw error(file, name, lineNumber, "boolean option must not carry a value token");
            }
            if (enumBody != null) {
                throw error(file, name, lineNumber, "enum value names are only valid for enum options");
            }
            return Optional.of(new PackOption(name, type, null, List.of(), true, !commented,
                    commented ? "0" : "1", label, Map.of()));
        }

        if (commented) {
            throw error(file, name, lineNumber, "commented declaration is only valid for boolean options");
        }
        if (value == null) {
            throw error(file, name, lineNumber, "non-boolean option must carry a value token");
        }

        if (bracket.contains("..")) {
            if (enumBody != null) {
                throw error(file, name, lineNumber, "enum value names are only valid for enum options");
            }
            OptionRange range = parseRange(bracket, file, name, lineNumber);
            return Optional.of(new PackOption(name, type, range, List.of(), false, false, value, label, Map.of()));
        }

        List<String> allowed = List.of(bracket.split("\\s+"));
        if (!allowed.contains(value)) {
            throw error(file, name, lineNumber, "default value \"" + value
                    + "\" is not among the declared values " + allowed);
        }
        return Optional.of(new PackOption(name, type, null, allowed, false, false, value, label,
                parseEnum(enumBody)));
    }

    private static OptionRange parseRange(String bracket, String file, String name, int lineNumber) {
        // "0.5..4.0 step 0.1"  -> min=0.5 max=4.0 step=0.1 (step optional, default (max-min)/100)
        String[] stepSplit = bracket.split("\\bstep\\b");
        String[] bounds = stepSplit[0].trim().split("\\.\\.");
        if (bounds.length != 2) {
            throw error(file, name, lineNumber, "invalid range, expected min..max [step s]: [" + bracket + "]");
        }
        try {
            double min = Double.parseDouble(bounds[0].trim());
            double max = Double.parseDouble(bounds[1].trim());
            double step = stepSplit.length > 1 ? Double.parseDouble(stepSplit[1].trim()) : (max - min) / 100.0;
            return new OptionRange(min, max, step);
        } catch (NumberFormatException e) {
            throw error(file, name, lineNumber, "invalid range, non-numeric bounds or step: [" + bracket + "]");
        }
    }

    private static Map<String, String> parseEnum(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null) return out;
        // DECL's group captures the braces along with the entries. They must be stripped BEFORE
        // entry matching: ENUM_ENTRY's lazy (\S+?) key otherwise swallows the opening brace into the
        // FIRST key -- '{0="Off" ...}' parsed as key "{0", so value 0 lost its display name and
        // rendered as a raw "0" in the settings UI (live-caught on the Reflections cycle).
        String entries = body.trim();
        if (entries.startsWith("{")) entries = entries.substring(1);
        if (entries.endsWith("}")) entries = entries.substring(0, entries.length() - 1);
        Matcher m = ENUM_ENTRY.matcher(entries);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static String nameOf(String line) {
        Matcher m = DEFINE_NAME.matcher(line);
        return m.find() ? m.group(1) : "";
    }

    private static FornaxPackError error(String file, String key, int lineNumber, String reason) {
        return new FornaxPackError(file, key, lineNumber > 0 ? "line " + lineNumber + ": " + reason : reason);
    }
}
