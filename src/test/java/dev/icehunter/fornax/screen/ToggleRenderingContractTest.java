package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.ScreenSpec;
import dev.icehunter.fornax.pack.option.OptionAnnotation;
import dev.icehunter.fornax.pack.option.PackOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pack can spell an on/off switch three ways, and all three must reach the settings screen as a tick
 * box.
 *
 * <p>Only the bracket-less {@code #define FOO //[]} form sets {@code PackOption.isBoolean()}, so the
 * other two -- {@code //[0 1]} and a runtime {@code //[0.0..1.0 step 1.0]} -- must still reach the
 * settings screen as a checkbox rather than a cycle button a user has to click through to discover
 * the current value. Every shadow, SSAO and parallax toggle in Plague is declared the second way, so
 * a two-value option that renders as a cycler rather than a checkbox is the common case, not an edge
 * case.
 *
 * <p>Pinned as a test because the distinction is invisible in the pack source: the same switch reads
 * identically in a shader whichever spelling it uses, and only the UI betrays the difference.
 */
public class ToggleRenderingContractTest {
    private static PackOption parse(String declaration) {
        return OptionAnnotation.parseLine(declaration, "test.fsh", 1).orElseThrow();
    }

    @Test
    void bracketlessBooleanIsAToggle() {
        assertTrue(parse("#define SHADOWS //[] compile \"Shadows\"").isBoolean());
    }

    @Test
    void zeroOneEnumIsAToggle() {
        PackOption option = parse("#define SHADOWS 1 //[0 1] compile \"Shadows\"");
        assertTrue(!option.isBoolean(), "precondition: this spelling is not isBoolean");
        assertTrue(isTwoStateZeroOne(option),
                "an option whose only values are 0 and 1 must render as a tick box");
    }

    @Test
    void unitSteppedRuntimeRangeIsAToggle() {
        PackOption option = parse("#define u_Thing 0.0 //[0.0..1.0 step 1.0] runtime \"Thing\"");
        assertTrue(option.range() != null && option.range().min() == 0.0f
                        && option.range().max() == 1.0f && option.range().step() == 1.0f,
                "a 0..1 range stepping by 1 has exactly two reachable values");
    }

    @Test
    void aThreeValueEnumIsNotAToggle() {
        // The guard has to be about the VALUES, not merely about being an enum -- a quality setting
        // with three levels must keep its cycle button.
        PackOption option = parse("#define QUALITY 1 //[0 1 2] compile \"Quality\"");
        assertTrue(!isTwoStateZeroOne(option), "a three-value option must not become a tick box");
    }

    @Test
    void namedTwoValueChoiceIsNotAToggle() {
        // Plague's LIGHT_MODEL. Two values, so every arm above says "tick box" -- but the pack NAMED
        // them, and a tick box renders that as "On/Off", discarding both names. Live report:
        // "what does on/off mean? they seem the same to me".
        //
        // The labels here are a FIXTURE, not a contract: this asserts on named-vs-unnamed, so any
        // two names exercise it identically and the pack may rename its arms freely.
        PackOption option = parse("#define LIGHT_MODEL 1 //[0 1] compile \"Light Model\" "
                + "{0=\"Authored\" 1=\"Physical\"}");
        assertTrue(isTwoStateZeroOne(option), "precondition: it IS two-state 0/1");
        assertTrue(YaclPackRows.hasWordLabels(option), "precondition: the pack named the values");
        assertFalse(YaclPackRows.rendersAsToggle(option),
                "a two-value option whose values are NAMED must keep its cycle button");
    }

    @Test
    void unnamedTwoValueOptionStaysAToggle() {
        // The guard must not swallow the ordinary case it was built around.
        PackOption option = parse("#define SHADOWS 1 //[0 1] compile \"Shadows\"");
        assertFalse(YaclPackRows.hasWordLabels(option));
        assertTrue(YaclPackRows.rendersAsToggle(option));
    }

    @Test
    void labelsEqualToTheirOwnValueCarryNoIntent() {
        // {0="0" 1="1"} renders identically with or without the entries, so it is not a named
        // choice -- treating it as one would cost a tick box and buy nothing.
        PackOption option = parse("#define THING 1 //[0 1] compile \"Thing\" {0=\"0\" 1=\"1\"}");
        assertFalse(YaclPackRows.hasWordLabels(option));
        assertTrue(YaclPackRows.rendersAsToggle(option));
    }

    /** Mirrors YaclPackRows.rendersAsToggle's enum arm. */
    private static boolean isTwoStateZeroOne(PackOption option) {
        List<String> allowed = option.allowedValues();
        return allowed.size() == 2 && allowed.contains("0") && allowed.contains("1");
    }

    /**
     * The live half of the toggle-rendering contract: a governor's dependents must grey/ungrey the
     * instant the governor's OWN row changes while the page is open, not just refresh correctly on
     * reopen -- whatever shape the governor itself rendered as.
     *
     * <p>{@code YaclPackRows.category}'s live dependency-greying listener must be wired for every
     * governor, not only governors collected into a toggleRows map that {@code rendersAsToggle(...)}
     * populates -- a two-state option the pack NAMED (a word-labeled cycler,
     * {@link #namedTwoValueChoiceIsNotAToggle}'s exact shape) does not enter that map. Its
     * dependents' greyed state is still computed correctly from {@code session.getApplied} at
     * page-open time, but wiring only the toggleRows governors would leave dependents of a named
     * cycler stuck at whatever they read on open when the governor flips while the page stays open
     * -- only closing and reopening the page would refresh them. {@code LIGHT_MODEL} here mirrors
     * Plague's real option of that name.
     */
    @Test
    void wordLabeledTwoStateGovernorDependentsUpdateLiveOnGovernorFlip() {
        PackModel model = PackFixtures.miniModelWithTwoStateGovernorsAndDependents();
        PackOption lightModel = model.options().get("LIGHT_MODEL");
        assertTrue(YaclPackRows.hasWordLabels(lightModel), "precondition: the pack named the values");
        assertFalse(YaclPackRows.rendersAsToggle(lightModel),
                "precondition: a named two-value option renders as a cycler, not a tick box");
        assertTrue(YaclPackRows.isTwoState(lightModel), "precondition: still two-state under the hood");

        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        ScreenSpec page = new ScreenSpec("Test Page", List.of(
                "LIGHT_MODEL",
                "u_LightStrength|requires:LIGHT_MODEL"));

        ConfigCategory category = YaclPackRows.category(session, page, model.screens());
        List<Option<?>> rows = allRows(category);
        Option<?> governor = rows.get(0);
        Option<?> dependent = rows.get(1);

        // Default value "0" ("Physical") -> off -> dependent starts greyed, matching session.getApplied.
        assertFalse(dependent.available(), "dependent must start greyed while the governor is off");

        // Flip the governor live, exactly like the cycler widget's own click handler would.
        requestSet(governor, "1");
        assertTrue(dependent.available(),
                "dependent must ungrey the instant the word-labeled governor flips live -- the reported defect");

        requestSet(governor, "0");
        assertFalse(dependent.available(), "dependent must re-grey when the governor flips back live");
    }

    /** Regression guard: an ordinary tick-box governor's live wiring must be unchanged by the fix above. */
    @Test
    void tickBoxGovernorDependentsStillUpdateLiveOnGovernorFlip() {
        PackModel model = PackFixtures.miniModelWithTwoStateGovernorsAndDependents();
        PackOption emitterLights = model.options().get("EMITTER_LIGHTS");
        assertTrue(YaclPackRows.rendersAsToggle(emitterLights), "precondition: renders as a tick box");

        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        ScreenSpec page = new ScreenSpec("Test Page", List.of(
                "EMITTER_LIGHTS",
                "SHADOW_SAMPLES|requires:EMITTER_LIGHTS"));

        ConfigCategory category = YaclPackRows.category(session, page, model.screens());
        List<Option<?>> rows = allRows(category);
        Option<?> governor = rows.get(0);
        Option<?> dependent = rows.get(1);

        assertFalse(dependent.available(), "dependent must start greyed -- EMITTER_LIGHTS defaults off");

        requestSet(governor, Boolean.TRUE);
        assertTrue(dependent.available(), "dependent must ungrey the instant the tick box flips live");

        requestSet(governor, Boolean.FALSE);
        assertFalse(dependent.available(), "dependent must re-grey when the tick box flips back live");
    }

    /** Simulates a widget's own click handler calling the row's {@code requestSet} -- the same entry
     * point every real controller (tick box, cycler) uses to fire an option's live listeners. */
    @SuppressWarnings("unchecked")
    private static void requestSet(Option<?> option, Object value) {
        ((Option<Object>) option).requestSet(value);
    }

    /** Every row across every group, in element order -- YACL's {@code ConfigCategory.Builder}
     * carries an implicit empty root group ahead of any {@code .group(...)} calls this class makes,
     * so indexing {@code category.groups().get(0)} alone would miss the rows entirely. */
    private static List<Option<?>> allRows(ConfigCategory category) {
        List<Option<?>> rows = new ArrayList<>();
        category.groups().forEach(g -> rows.addAll(g.options()));
        return rows;
    }
}
