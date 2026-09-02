package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the numeric constants {@code docs/ARCHITECTURE.md} quotes to the code they describe. A wrong
 * slot count or block size there is the figure someone budgets a pack's inputs against or sizes a
 * uniform append against, and both surface as data read from the wrong offset rather than an error.
 *
 * <p>{@link GlobalsLayoutContractTest} ties the GLSL block to the Java allocation but cannot catch
 * this case: the prose is a third copy that neither side reads.
 *
 * <p>Parses source text rather than loading classes, like {@code GlobalsLayoutContractTest}: these
 * writers cannot be instantiated outside a live game frame.
 */
class ArchitectureDocConstantsContractTest {

    private static final Path ARCHITECTURE = Path.of("docs/ARCHITECTURE.md");
    private static final Path MANAGER =
            Path.of("src/main/java/dev/icehunter/fornax/mixin/sodium/UniformBufferManagerMixin.java");

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), path + " is missing, so this contract cannot be checked");
        return Files.readString(path);
    }

    private static int matchOne(String haystack, Pattern pattern, String what) {
        Matcher m = pattern.matcher(haystack);
        assertTrue(m.find(), "could not find " + what
                + "; the wording changed, so update this check rather than deleting it");
        int value = Integer.parseInt(m.group(1));
        assertTrue(!m.find(), "found more than one candidate for " + what
                + "; the check is ambiguous and would pass on the wrong one");
        return value;
    }

    @Test
    void documentedGeometryInputSlotCountMatchesTheReservedConstant() throws IOException {
        String doc = read(ARCHITECTURE);

        int documented = matchOne(doc,
                Pattern.compile("`GeometryInputs\\.RESERVED == (\\d+)`"),
                "the documented GeometryInputs.RESERVED value");

        assertEquals(GeometryInputs.RESERVED, documented,
                "ARCHITECTURE.md says GeometryInputs.RESERVED == " + documented + " but the constant is "
                        + GeometryInputs.RESERVED + ". Too low silently caps a pack's declared inputs,"
                        + " too high resolves onto a slot the bind group never appended.");

        assertTrue(doc.contains("`u_GeomInput0.." + (GeometryInputs.RESERVED - 1) + "`"),
                "ARCHITECTURE.md's geometry-input heading does not name the range u_GeomInput0.."
                        + (GeometryInputs.RESERVED - 1) + ", which is what RESERVED == "
                        + GeometryInputs.RESERVED + " actually exposes");
    }

    @Test
    void documentedGlobalsBlockSizeMatchesTheAllocatedBufferSize() throws IOException {
        String doc = read(ARCHITECTURE);

        int allocated = matchOne(read(MANAGER),
                Pattern.compile("return (\\d+);\\s*\\n\\s*}\\s*\\n\\s*@Inject"),
                "the u_Globals ring-buffer size UniformBufferManagerMixin allocates");

        int documentedHeading = matchOne(doc,
                Pattern.compile("### `u_Globals` \\(std140, (\\d+) bytes\\)"),
                "the u_Globals size in the section heading");
        int documentedTotal = matchOne(doc,
                Pattern.compile("Total: (\\d+) bytes exactly"),
                "the u_Globals total stated under the offset table");

        assertEquals(allocated, documentedHeading,
                "ARCHITECTURE.md's u_Globals heading says " + documentedHeading
                        + " bytes but UniformBufferManagerMixin allocates " + allocated);
        assertEquals(allocated, documentedTotal,
                "ARCHITECTURE.md's u_Globals table totals " + documentedTotal
                        + " bytes but UniformBufferManagerMixin allocates " + allocated
                        + ". A short total puts the next tail append on top of an existing member.");
    }

    @Test
    void documentedGlobalsOffsetTableAccountsForEveryDocumentedByte() throws IOException {
        String doc = read(ARCHITECTURE);

        int total = matchOne(doc, Pattern.compile("Total: (\\d+) bytes exactly"),
                "the u_Globals total stated under the offset table");

        Matcher rows = Pattern.compile("\\| `(u_\\w+)` \\| (\\w+) \\| (\\d+) \\| (\\d+) \\|").matcher(doc);
        int lastEnd = 0;
        String lastName = null;
        int counted = 0;
        while (rows.find()) {
            int offset = Integer.parseInt(rows.group(3));
            int size = Integer.parseInt(rows.group(4));
            if (offset >= lastEnd) {
                lastEnd = offset + size;
                lastName = rows.group(1);
            }
            counted++;
        }
        assertTrue(counted >= 10, "parsed suspiciously few u_Globals table rows: " + counted);

        assertEquals(total, lastEnd,
                "the last u_Globals row (" + lastName + ") ends at byte " + lastEnd
                        + " but the table claims a total of " + total
                        + ". Either a tail field has no row, or the total does not include it; both leave"
                        + " the next author appending onto occupied bytes.");
    }
}
