package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.pack.BlocksSpec;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.MainScreenSpec;
import dev.icehunter.fornax.pack.PackMeta;
import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.ScreensSpec;
import dev.icehunter.fornax.pack.option.OptionAnnotation;
import dev.icehunter.fornax.pack.option.PackOption;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A headless {@link PackModel} with one runtime slider option, no GPU/filesystem dependence. */
final class PackFixtures {
    private PackFixtures() {}

    static PackModel miniModelWithRuntimeSlider() {
        Map<String, PackOption> options = new LinkedHashMap<>();
        PackOption demo = OptionAnnotation.parseLine(
                "#define u_Demo 0.5 //[0.0..1.0 step 0.05] runtime \"Demo\"").orElseThrow();
        options.put(demo.name(), demo);
        ScreensSpec screens = new ScreensSpec(new MainScreenSpec(List.of(), 1),
                Map.of(), Map.of(), List.of("u_Demo"), Map.of(), List.of());
        PackMeta meta = new PackMeta("Test", "0", List.of(), "MIT", 1);
        GraphSpec graph = new GraphSpec(Map.of(), List.of());
        return new PackModel(Path.of("."), meta, graph, screens, options, BlocksSpec.empty());
    }

    /**
     * Four independent runtime slider options (u_A..u_D) -- feeds the meta Save-burst test: N=4
     * changed meta rows, each a separate {@link dev.icehunter.fornax.pack.MetaSpec} pinning one of
     * these, simulating N binding-setter calls inside YACL's one synchronous {@code finishOrSave}
     * apply-value loop.
     */
    static PackModel miniModelWithFourRuntimeSliders() {
        Map<String, PackOption> options = new LinkedHashMap<>();
        for (String name : List.of("u_A", "u_B", "u_C", "u_D")) {
            PackOption option = OptionAnnotation.parseLine(
                    "#define " + name + " 0.5 //[0.0..1.0 step 0.05] runtime \"" + name + "\"").orElseThrow();
            options.put(option.name(), option);
        }
        ScreensSpec screens = new ScreensSpec(new MainScreenSpec(List.of(), 1),
                Map.of(), Map.of(), List.copyOf(options.keySet()), Map.of(), List.of());
        PackMeta meta = new PackMeta("Test", "0", List.of(), "MIT", 1);
        GraphSpec graph = new GraphSpec(Map.of(), List.of());
        return new PackModel(Path.of("."), meta, graph, screens, options, BlocksSpec.empty());
    }

    /**
     * A boolean compile option, commented off by default (the manual-until-live-verified convention
     * real gating masters like {@code EMITTER_LIGHTS}/{@code SHADOWS} follow) -- feeds {@link
     * dev.icehunter.fornax.screen.MetaBinding#dependencyMet}'s test coverage, which needs a real
     * dependency option to read {@link PackEditSession#getApplied} off.
     */
    static PackModel miniModelWithBooleanCompileOption() {
        Map<String, PackOption> options = new LinkedHashMap<>();
        PackOption gate = OptionAnnotation.parseLine(
                "// #define EMITTER_LIGHTS //[] compile \"Emitter Lights\"").orElseThrow();
        options.put(gate.name(), gate);
        ScreensSpec screens = new ScreensSpec(new MainScreenSpec(List.of(), 1),
                Map.of(), Map.of(), List.of(), Map.of(), List.of());
        PackMeta meta = new PackMeta("Test", "0", List.of(), "MIT", 1);
        GraphSpec graph = new GraphSpec(Map.of(), List.of());
        return new PackModel(Path.of("."), meta, graph, screens, options, BlocksSpec.empty());
    }

    /**
     * One runtime slider ({@code u_A}) plus one compile enum ({@code SHADOW_SAMPLES}) -- feeds
     * {@link dev.icehunter.fornax.screen.MetaBinding#recompilesOnSave}'s test coverage, which needs a
     * meta able to mix both option types across its tiers the way a real Quality-page meta
     * (e.g. {@code SHADOW_DETAIL}) does.
     */
    static PackModel miniModelWithRuntimeAndCompileOptions() {
        Map<String, PackOption> options = new LinkedHashMap<>();
        PackOption runtime = OptionAnnotation.parseLine(
                "#define u_A 0.5 //[0.0..1.0 step 0.05] runtime \"u_A\"").orElseThrow();
        options.put(runtime.name(), runtime);
        PackOption compile = OptionAnnotation.parseLine(
                "#define SHADOW_SAMPLES 8 //[4 8 12 16] compile \"Shadow Samples\"").orElseThrow();
        options.put(compile.name(), compile);
        ScreensSpec screens = new ScreensSpec(new MainScreenSpec(List.of(), 1),
                Map.of(), Map.of(), List.of("u_A"), Map.of(), List.of());
        PackMeta meta = new PackMeta("Test", "0", List.of(), "MIT", 1);
        GraphSpec graph = new GraphSpec(Map.of(), List.of());
        return new PackModel(Path.of("."), meta, graph, screens, options, BlocksSpec.empty());
    }

    /**
     * Two two-state governors, rendering as the two different shapes {@link
     * dev.icehunter.fornax.screen.YaclPackRows#rendersAsToggle} can produce, each gating one
     * dependent row via {@code |requires:} -- feeds the live dependency-greying listener wiring
     * test: {@code EMITTER_LIGHTS} (bracket-less boolean, gates {@code SHADOW_SAMPLES}) renders as a
     * tick box; {@code LIGHT_MODEL} (word-labeled two-value enum -- the {@code hasWordLabels}
     * carve-out, mirroring Plague's real option of the same name) renders as a labeled cycler and
     * gates {@code u_LightStrength}.
     */
    static PackModel miniModelWithTwoStateGovernorsAndDependents() {
        Map<String, PackOption> options = new LinkedHashMap<>();
        PackOption tickGovernor = OptionAnnotation.parseLine(
                "// #define EMITTER_LIGHTS //[] compile \"Emitter Lights\"").orElseThrow();
        options.put(tickGovernor.name(), tickGovernor);
        PackOption wordGovernor = OptionAnnotation.parseLine(
                "#define LIGHT_MODEL 0 //[0 1] compile \"Light Model\" "
                        + "{0=\"Physical\" 1=\"Custom\"}").orElseThrow();
        options.put(wordGovernor.name(), wordGovernor);
        PackOption dependentOfTick = OptionAnnotation.parseLine(
                "#define SHADOW_SAMPLES 8 //[4 8 12 16] compile \"Shadow Samples\"").orElseThrow();
        options.put(dependentOfTick.name(), dependentOfTick);
        PackOption dependentOfWord = OptionAnnotation.parseLine(
                "#define u_LightStrength 0.5 //[0.0..1.0 step 0.05] runtime \"Light Strength\"").orElseThrow();
        options.put(dependentOfWord.name(), dependentOfWord);

        ScreensSpec screens = new ScreensSpec(new MainScreenSpec(List.of(), 1),
                Map.of(), Map.of(), List.of("u_LightStrength"), Map.of(), List.of());
        PackMeta meta = new PackMeta("Test", "0", List.of(), "MIT", 1);
        GraphSpec graph = new GraphSpec(Map.of(), List.of());
        return new PackModel(Path.of("."), meta, graph, screens, options, BlocksSpec.empty());
    }

    /**
     * Each option's own declared default, keyed by name -- the "applied" snapshot a real {@link
     * PackEditSession(PackModel)} would otherwise read from the (headlessly-unavailable) values file.
     * Feeds {@link PackEditSession}'s test-only two-argument constructor.
     */
    static Map<String, String> defaultValues(PackModel model) {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (PackOption option : model.options().values()) {
            defaults.put(option.name(), option.defaultValue());
        }
        return defaults;
    }
}
