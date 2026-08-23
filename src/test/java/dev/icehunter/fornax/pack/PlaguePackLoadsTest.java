package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.TargetFilter;
import dev.icehunter.fornax.pack.graph.TargetKind;
import dev.icehunter.fornax.pipeline.PbrSettingsBlockParser;
import dev.icehunter.fornax.pipeline.PbrSettingsLayout;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads the Plague pack -- the pack engine features are developed against -- through the real
 * {@link PackDiscovery#loadFrom} chain.
 *
 * <p>Plague is where new engine capability gets exercised, so it claims slots and uses plumbing that
 * is still under construction. Catching a manifest or option-scan error here costs a test run;
 * catching it in-game costs a launch, and the failure arrives as a black screen rather than a
 * message. Skips itself when the pack is absent, like the other pack-loading guards alongside it.
 */
class PlaguePackLoadsTest {

    /**
     * Every COMPILE option's declared values must be whole numbers.
     *
     * <p>A compile option becomes a {@code #define} substituted before compilation, so
     * {@link dev.icehunter.fornax.pack.option.PackOptionValues#toCompileInt} rejects a fractional
     * value -- but it rejects it at APPLY time, on the click of the Apply button, as a fatal
     * {@code IllegalArgumentException} out of the mouse handler. The pack still loads, the settings
     * screen still opens, and the option still renders; the crash arrives only when someone applies.
     *
     * <p>Which is exactly what happened when the star options landed declaring
     * {@code PLAGUE_STAR_SIZE 1.0} and {@code PLAGUE_STAR_SOFTNESS 0.0}: this class was green,
     * everyDeclaredOptionIsReachableInTheSettingsUi was green, and the game died on Apply. Loading a
     * pack is not the same as being able to apply it, and only this asserts the second.
     */
    @Test
    void everyCompileOptionIsAWholeNumber() {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);
        Set<String> offenders = new TreeSet<>();
        for (var option : pack.options().values()) {
            if (option.type() != dev.icehunter.fornax.pack.option.OptionType.COMPILE) {
                continue;
            }
            for (String value : option.allowedValues()) {
                try {
                    Integer.parseInt(value.trim());
                } catch (NumberFormatException e) {
                    offenders.add(option.name() + " = \"" + value + "\"");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "compile options must be whole numbers (declare a fraction as an integer percent "
                        + "and divide in the shader); offenders: " + offenders);
    }

    @Test
    void plaguePackLoadsCleanly() {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        PackModel pack = assertDoesNotThrow(() -> PackDiscovery.loadFrom(root, 1920, 1080),
                "Plague must keep loading -- it is the pack engine work is verified against");

        assertEquals("Plague", pack.meta().name());

        // The slots Plague claims are the point of the pack: a silent drop here would look exactly
        // like the engine failing to route them.
        assertTrue(pack.graph().passes().stream()
                        .anyMatch(p -> p.type() == PassType.GEOMETRY && p.slot() == GeometrySlot.TERRAIN),
                "expected a terrain geometry pass");
        assertTrue(pack.graph().passes().stream()
                        .anyMatch(p -> p.type() == PassType.GEOMETRY && p.slot() == GeometrySlot.ENTITIES),
                "expected an entities geometry pass");
    }

    @Test
    void underwaterShaftQualityIsAWordLabeledCyclerInAuthoredOrder() {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);
        var option = assertDoesNotThrow(
                () -> java.util.Objects.requireNonNull(
                        pack.options().get("WATER_SCATTERING_QUALITY"),
                        "WATER_SCATTERING_QUALITY must be declared"));

        assertEquals("Underwater Light Shafts", option.label());
        assertEquals("1", option.defaultValue(), "Balanced must be the shipped default");
        assertEquals(List.of("0", "1", "2"), option.allowedValues());
        assertEquals(Map.of("0", "Off", "1", "Balanced", "2", "High"),
                option.enumNames(),
                "the settings UI must render words instead of exposing GLSL integer values");
    }

    /**
     * A target that is read back MAGNIFIED must declare {@code filter = "linear"}.
     *
     * <p>Every fullscreen input binds NEAREST by default, which is correct at 1:1 and wrong the
     * moment a pass samples a smaller target across a bigger output: each texel becomes a visible
     * square. Plague's bloom chain is entirely such targets -- quarter scale down to 1/256 -- and
     * {@code bloomFinal} is the worst case, since tonemap reads it at full resolution and it carries
     * the whole bloom contribution rather than one weighted level.
     *
     * <p>Asserted by SCALE rather than by name, so a future downsampled target is covered the day it
     * is declared instead of the day someone notices blocks in a screenshot.
     */
    @Test
    void everyDownsampledTargetIsSampledLinearly() {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);

        Set<String> nearestAndDownsampled = pack.graph().targets().values().stream()
                .filter(t -> t.kind() == TargetKind.TEXTURE)
                .filter(t -> t.scale() > 0.0 && t.scale() < 1.0)
                .filter(t -> t.filter() == TargetFilter.NEAREST)
                .map(TargetSpec::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(nearestAndDownsampled.isEmpty(),
                "these targets are smaller than the frame, so any pass reading them magnifies them and"
                        + " NEAREST turns their texels into visible squares -- declare"
                        + " filter = \"linear\" on each, or document why point sampling is intended: "
                        + nearestAndDownsampled);
    }

    @Test
    void everyDeclaredOptionIsReachableInTheSettingsUi() {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);

        Set<String> onAScreen = pack.screens().screens().values().stream()
                .flatMap(screen -> screen.elements().stream())
                // An element token may carry gate modifiers (NAME|requires:GOVERNOR); the row it
                // places on the screen is the base name.
                .map(token -> token.split("\\|")[0].trim())
                .collect(Collectors.toSet());

        Set<String> orphaned = pack.options().keySet().stream()
                // PACK_* declarations are renderer capabilities, not user preferences. They exist
                // so engine-side ownership decisions survive saved option values and do not
                // render as controls.
                .filter(name -> !name.startsWith("PACK_"))
                .filter(name -> !onAScreen.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(orphaned.isEmpty(),
                "these options are declared in Plague's shaders but appear on no screen, so they take"
                        + " effect while being invisible and unadjustable in game -- add them to a"
                        + " [screens.*] page in screens.toml: " + orphaned);
    }

    @Test
    void noDeferredGeometryShaderDeclaresARuntimeOption() throws Exception {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        // A runtime option is delivered through the generated u_PackOptions block, and which passes
        // receive that block is NOT uniform across geometry passes -- which is why this test grew a
        // slot lookup instead of scanning shaders/blocks wholesale.
        //
        // A DEFERRED geometry pass gets Sodium's terrain bind group and no u_PackOptions at all.
        // Declaring a runtime option in one strips the #define (as designed) and leaves the identifier
        // undefined, so the shader fails to compile and Vulkan hands back an invalid pipeline: a hard
        // crash at the first terrain draw, with nothing in the log pointing at the option that caused
        // it. That is why terrain's POM tunables are bridged through u_PbrSettings push constants.
        //
        // A FORWARD geometry pass DOES get the block (GraphRunner.rebuild's geometry branch, gated on
        // GeometrySlot.rendersForward()) and MUST: it composites into the already-tonemapped frame and
        // cannot reproduce the pack's display transform without exposure, the curve and the grade, all
        // of which are runtime options. So the rule is not "geometry shaders may not", it is "DEFERRED
        // geometry shaders may not" -- and the exemption keys on the engine's own predicate rather
        // than on a filename allowlist, which would go stale the first time a slot changed sides.
        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);
        Map<String, GeometrySlot> slotByProgramStem = new HashMap<>();
        for (PassSpec p : pack.graph().passes()) {
            if (p.type() != PassType.GEOMETRY || p.program() == null) {
                continue;
            }
            String program = p.program();
            int dot = program.lastIndexOf('.');
            slotByProgramStem.put(dot < 0 ? program : program.substring(0, dot),
                    p.slot() == null ? GeometrySlot.DEFAULT : p.slot());
        }

        Path blocks = root.resolve("shaders/blocks");
        if (!Files.isDirectory(blocks)) {
            return;
        }
        Set<String> offenders = new TreeSet<>();
        Set<String> forwardDeclarations = new TreeSet<>();
        try (var files = Files.list(blocks)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".vsh") && !name.endsWith(".fsh")) {
                    continue;
                }
                GeometrySlot slot = slotByProgramStem.get(
                        "shaders/blocks/" + name.substring(0, name.length() - 4));
                // A file in shaders/blocks that no pass claims counts as deferred: it is either dead
                // or about to be claimed, and the strict answer is the safe one either way.
                boolean forward = slot != null && slot.rendersForward();
                for (String line : Files.readAllLines(file)) {
                    if (line.contains("//[") && line.contains("runtime")) {
                        (forward ? forwardDeclarations : offenders).add(name + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "deferred geometry shaders cannot read pack runtime options (no u_PackOptions"
                        + " binding) -- these declarations would crash at the first draw: " + offenders);
        // The positive half. A forward program declaring nothing is not obviously broken, but it means
        // the u_PackOptions block this mechanism added a GraphRunner branch and a bind-group entry for
        // is going unused -- and that branch would then be exercised by nothing at all.
        assertFalse(forwardDeclarations.isEmpty(),
                "no forward geometry shader declares a runtime option, so nothing exercises the"
                        + " u_PackOptions block GraphRunner splices into forward geometry passes");
    }

    /**
     * The same rule as above, but following {@code #moj_import} -- which is where it had a hole
     * exactly the shape of the hazard it exists to catch.
     *
     * <p>The test above scans only files sitting directly in {@code shaders/blocks/}. A runtime
     * option does not have to be DECLARED there to be REACHABLE there: {@code DefineRewriter} strips
     * runtime {@code #define}s globally and per-file across the whole source map, with includes as
     * first-class sources, so an option declared in {@code shaders/include/tonemap.glsl} and used
     * inside one of its function bodies is every bit as undefined in a deferred geometry program as
     * one declared inline -- and completely invisible to a scan that never opens the include.
     *
     * <p>That went from theoretical to load-bearing when the translucent terrain arm started
     * importing {@code fog.glsl} and {@code tonemap.glsl} to fog glass. The pack bridges the options
     * those two reach through {@code u_PbrSettings}, so the correct rule is not "unreachable" but
     * "reachable ONLY if bridged" -- which is what this asserts.
     *
     * <p><b>Why this cannot be left to the shader compile check.</b> {@code tools/check_shaders.sh}
     * feeds RAW source to glslangValidator with no rewriting, so the annotated {@code #define} is
     * still present offline and the identifier resolves to a literal. The file compiles perfectly
     * with the {@code u_PbrSettings} member missing entirely, and fails only on the user's GPU, at
     * the first terrain draw, as an invalid pipeline with nothing in the log naming the option.
     */
    @Test
    void everyRuntimeOptionReachableFromADeferredGeometryProgramIsBridged() throws Exception {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);
        Set<String> bridged = PbrSettingsLayout.MEMBERS.stream()
                .map(PbrSettingsLayout.Member::option)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> unbridged = new TreeSet<>();
        int deferredProgramsChecked = 0;
        for (PassSpec p : pack.graph().passes()) {
            if (p.type() != PassType.GEOMETRY || p.program() == null) {
                continue;
            }
            GeometrySlot slot = p.slot() == null ? GeometrySlot.DEFAULT : p.slot();
            if (slot.rendersForward()) {
                continue; // gets the real u_PackOptions block; the test above covers it
            }
            String program = p.program();
            int dot = program.lastIndexOf('.');
            String stem = dot < 0 ? program : program.substring(0, dot);
            for (String ext : new String[] {".fsh", ".vsh"}) {
                Path shader = root.resolve(stem + ext);
                if (!Files.isRegularFile(shader)) {
                    continue;
                }
                deferredProgramsChecked++;
                for (Path source : transitiveSources(root, shader)) {
                    for (String line : Files.readAllLines(source)) {
                        if (!line.contains("//[") || !line.contains("runtime")) {
                            continue;
                        }
                        Matcher m = RUNTIME_DECLARATION.matcher(line);
                        if (m.find() && !bridged.contains(m.group(1))) {
                            unbridged.add(m.group(1) + " (from " + root.relativize(source) + ")");
                        }
                    }
                }
            }
        }

        assertTrue(deferredProgramsChecked > 0,
                "no deferred geometry program was found, so this test asserted nothing");
        assertTrue(unbridged.isEmpty(),
                "these runtime options are reachable from a DEFERRED geometry program through"
                        + " #moj_import but are not members of u_PbrSettings. DefineRewriter strips"
                        + " their #define across every source file including includes, so the"
                        + " identifier is undefined at the first terrain draw -- an invalid pipeline"
                        + " with nothing in the log naming the cause. Either add the option to"
                        + " PbrSettingsLayout.MEMBERS (and to the pack's u_PbrSettings block, in the"
                        + " same order), or stop importing the file that declares it: " + unbridged);
    }

    /**
     * The pack's own {@code u_PbrSettings} block must match {@code PbrSettingsLayout.MEMBERS}
     * name-for-name, in order.
     *
     * <p>This is the assertion that makes the block safe to extend. std140 matches Java writes to
     * GLSL members BY POSITION and the name exists only on the GLSL side, so a member added to one
     * side and not the other compiles cleanly on both and reads a neighbour's float -- a wrong
     * exposure, or a POM depth that is really a POM quality. Nothing else in the toolchain can see
     * it: the shader is well-formed, the buffer write is well-formed, and no validation layer
     * compares them. See {@code PbrSettingsLayoutTest} for the same pin over the declarations that
     * ship inside the engine repo.
     *
     * <p>Plague declares the block in FULL (unlike the engine's own fallback, which declares a legal
     * short prefix) because it is the pack the whole bridge exists to serve.
     */
    @Test
    void plagueDeclaresTheFullPbrSettingsBlockInLayoutOrder() throws Exception {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        Path terrain = root.resolve("shaders/blocks/terrain.fsh");
        assumeTrue(Files.isRegularFile(terrain), "Plague has no shaders/blocks/terrain.fsh -- skipping");

        List<String> declared = PbrSettingsBlockParser.membersOf(terrain);
        List<String> expected = PbrSettingsLayout.MEMBERS.stream()
                .map(PbrSettingsLayout.Member::option).toList();

        assertEquals(expected, declared,
                "Plague's u_PbrSettings block disagrees with PbrSettingsLayout.MEMBERS. These are"
                        + " matched POSITIONALLY by std140 -- a mismatch compiles cleanly on both"
                        + " sides and silently reads the wrong float. Fix whichever is wrong, and"
                        + " remember the layout is APPEND-ONLY: inserting in the middle moves every"
                        + " offset after it and corrupts the short prefix declarations too.");
    }

    /**
     * The reverse direction: every {@code u_PbrSettings} member must name an option the pack really
     * declares.
     *
     * <p>The test above asks "is every option the shader reaches bridged?". This asks "does every
     * bridge lead anywhere?", and the two failures are opposite and equally silent.
     * {@code PackOptionsBuffer.get(name, fallback)} returns the FALLBACK for a name it does not know
     * -- no exception, no log line -- so a member misspelled on the Java side (or an option the pack
     * later renames) delivers a frozen constant to the shader forever. Exposure would simply stop
     * responding to its own slider, and the block would still be the right size, still be written in
     * the right order, and still match the GLSL name-for-name.
     *
     * <p>Scoped to Plague: {@code PbrSettingsLayout} is engine-wide and a different pack
     * is entitled to declare fewer options, so this is a statement about the pack the bridge was
     * built for, not a universal rule.
     */
    @Test
    void everyBridgedPbrSettingIsAnOptionPlagueActuallyDeclares() {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);
        Set<String> undeclared = PbrSettingsLayout.MEMBERS.stream()
                .map(PbrSettingsLayout.Member::option)
                .filter(name -> !pack.options().containsKey(name))
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(undeclared.isEmpty(),
                "these u_PbrSettings members name no option Plague declares, so PackOptionsBuffer.get"
                        + " returns the hardcoded fallback for them every frame -- the slider moves"
                        + " and nothing happens, with no error anywhere: " + undeclared);
    }

    /** Matches the option NAME out of an annotated runtime declaration: {@code #define NAME value //[..] runtime "Label"}. */
    private static final Pattern RUNTIME_DECLARATION =
            Pattern.compile("#define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s.*//\\[[^\\]]*\\]\\s*runtime\\b");

    /** Matches a Mojang include directive and captures its namespace and path. */
    private static final Pattern MOJ_IMPORT =
            Pattern.compile("^\\s*#moj_import\\s*<([^:>]+):([^>]+)>");

    /**
     * A shader and every pack source it pulls in, transitively.
     *
     * <p>Only {@code fornax_runtime:} (the pack's own {@code shaders/include/}) is followed.
     * {@code fornax:} resolves into the ENGINE's resources, whose includes declare no pack options by
     * construction -- they are engine-owned and never pass through the pack option scanner -- and
     * {@code minecraft:} resolves inside the client jar, which is not on disk here and likewise
     * declares nothing annotated. Following either would be noise, and neither can be the source of
     * the failure this guards.
     */
    private static Set<Path> transitiveSources(Path root, Path entry) throws java.io.IOException {
        Set<Path> seen = new LinkedHashSet<>();
        Deque<Path> pending = new ArrayDeque<>();
        pending.add(entry);
        while (!pending.isEmpty()) {
            Path current = pending.poll();
            if (!Files.isRegularFile(current) || !seen.add(current)) {
                continue;
            }
            for (String line : Files.readAllLines(current)) {
                Matcher m = MOJ_IMPORT.matcher(line);
                if (m.find() && "fornax_runtime".equals(m.group(1))) {
                    pending.add(root.resolve("shaders/include").resolve(m.group(2)));
                }
            }
        }
        return seen;
    }

    /**
     * The option {@code LevelRendererWeatherPassMixin} cancels vanilla's weather on must be one the
     * pack actually declares, and a pack that turns it on must actually draw weather itself.
     *
     * <p>Both halves fail SILENTLY and in opposite directions, which is why they are pinned rather
     * than trusted. The mixin queries a hard-coded string; rename the option in the pack and the
     * query goes to a name nothing declares, {@code isCompileOptionEnabled} returns its hardened
     * false, vanilla's curtain is never cancelled -- and the pack's own pass still runs, so the world
     * gets TWO rains at once with nothing logged. The other direction is worse: cancelling vanilla's
     * pass without shipping a replacement removes precipitation from the game entirely and puts
     * nothing back, which reads as "the weather stopped working" rather than as a missing pass.
     *
     * <p>Reads the mixin's source rather than calling it, for the same reason
     * {@code GlobalsLayoutContractTest} reads {@code globals.glsl}: the string is the contract, and
     * only the text of it can be compared against what the pack declares.
     */
    @Test
    void weatherCancellationAndThePackReplacementAgree() throws Exception {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        Path mixin = Path.of("src/main/java/dev/icehunter/fornax/mixin/vanilla/"
                + "LevelRendererWeatherPassMixin.java");
        assertTrue(Files.isRegularFile(mixin), "expected the weather-cancellation mixin at " + mixin);

        Matcher queried = Pattern.compile("isCompileOptionEnabled\\(\"(\\w+)\"\\)")
                .matcher(Files.readString(mixin));
        assertTrue(queried.find(),
                mixin + " no longer gates on a compile option -- if the cancellation became"
                        + " unconditional, vanilla's rain is gone for every pack, replaced by nothing");
        String optionName = queried.group(1);

        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);

        // NOT DECLARING IT IS A LEGITIMATE STATE, and is currently Plague's. Absent counts as off, so
        // the mixin never cancels and vanilla keeps its own precipitation -- which is the outcome the
        // pack now wants: vanilla's weather is per-COLUMN, standing still in the world as the player
        // walks through it and mixing snow and rain across one frame, and a camera-centred pack pass
        // can do neither. This assertion used to require the declaration; that was written when a
        // pack replacement existed and became wrong the day it was removed.
        //
        // The half worth keeping is the DANGEROUS direction, below: cancelling vanilla's weather
        // without shipping something to draw in its place removes precipitation from the game and
        // puts nothing back, which reads as "the weather stopped working" rather than as an error.
        boolean packOwnsWeather = pack.options().containsKey(optionName)
                && "1".equals(pack.options().get(optionName).defaultValue());
        boolean drawsWeather = pack.graph().passes().stream()
                .anyMatch(p -> p.shader() != null && p.shader().contains("precipitation"));
        if (packOwnsWeather) {
            assertTrue(drawsWeather,
                    "Plague defaults '" + optionName + "' on, so the engine cancels vanilla's weather"
                            + " pass -- but the pack declares no precipitation pass to replace it, which"
                            + " leaves the game with no rain or snow at all rather than with an error");
        }
    }

    /**
     * A pack-owned rain-impact layer must suppress vanilla's separate blue splash particle through
     * a capability the user cannot accidentally turn off while the replacement remains visible.
     */
    @Test
    void plagueRainImpactsOwnTheVanillaSplashSlot() throws Exception {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        Path mixin = Path.of("src/main/java/dev/icehunter/fornax/mixin/vanilla/"
                + "ParticleEngineRainImpactMixin.java");
        String mixinSource = Files.readString(mixin);
        assertTrue(mixinSource.contains("@Mixin(ParticleEngine.class)"),
                "rain-impact ownership must be enforced where particles are created; a wrapper on"
                        + " one weather-tick call site misses alternate paths that create the same"
                        + " vanilla rain particle");
        assertTrue(mixinSource.contains("method = \"createParticle\""),
                "rain-impact ownership no longer intercepts the ParticleEngine creation boundary");
        assertTrue(mixinSource.contains("isCompileOptionEnabled(\"PACK_RAIN_IMPACTS\")"),
                "vanilla splash suppression must follow the pack-owned impact capability, not a"
                        + " user-facing preference that can drift from the replacement effects");
        assertTrue(mixinSource.contains("visuality:water_circle"),
                "Visuality spawns its own water-circle particle independently of vanilla's RAIN"
                        + " particle; pack-owned rain impacts must suppress both competing layers");
        assertTrue(mixinSource.contains("BuiltInRegistries.PARTICLE_TYPE.getKey"),
                "optional mod particles must be matched by registry id without a hard Visuality"
                        + " class dependency");

        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);
        assertTrue(pack.options().containsKey("PACK_RAIN_IMPACTS"),
                "Plague draws rain impacts but does not declare ownership of vanilla's splash slot");
        assertEquals("1", pack.options().get("PACK_RAIN_IMPACTS").defaultValue());
    }

    // A cross-repo test used to live here, plagueSnowFieldMatchesThePrecipClipmapWindow, pinning
    // Plague's snow field extent and anchor snap against PrecipClipmapBuffer.GRID/ANCHOR_SNAP. It
    // went with the snow accumulation compute pass it was describing. Nothing in Plague reads
    // precipClipmap any more, so there is no second half of that contract left to disagree with.
    //
    // Note the shape it was written in, because it is worth reusing and not worth rediscovering: it
    // read the pack's constant out of the SHADER SOURCE. An engine constant cannot enforce anything
    // about a number written in a pack's GLSL, and a test spanning both repos is the only place the
    // two are visible at once.

    /**
     * "Fast" reflections must actually be faster than "Fancy", and faster by RESOLUTION.
     *
     * <p>This pins a defect the pack shipped with for its whole life: {@code SSR_QUALITY} was declared
     * {@code {0="Off" 1="Fancy" 2="Fast"}} while every reflection pass was gated on
     * {@code SSR_QUALITY != 0}, so both non-zero values ran the identical full-resolution chain. The
     * setting was in the menu, it applied cleanly, it triggered a graph rebuild, and it changed
     * nothing at all. That is the worst shape a defect can take -- there is no error, no artifact and
     * no log line, only a user wondering why the fast option is not fast -- and it is exactly the
     * class this suite exists to pin.
     *
     * <p>Asserting merely that the two option values enable different PASS NAMES would re-admit the
     * bug in a new costume: two names running the same shader over the same full-resolution targets
     * are still one tier. So the assertion is on the thing that actually costs milliseconds, the
     * output target's scale, and it is made per shader FILE rather than per pass name so that a
     * future rename cannot quietly satisfy it.
     */
    @Test
    void fastReflectionsRunAtALowerResolutionThanFancy() {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout -- skipping");

        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);
        assumeTrue(pack.options().containsKey("SSR_QUALITY"), "Plague declares no SSR_QUALITY -- skipping");

        Set<String> fancyPasses = enabledPassNames(pack, 1);
        Set<String> fastPasses = enabledPassNames(pack, 2);
        assertNotEquals(fancyPasses, fastPasses,
                "SSR_QUALITY 1 (Fancy) and 2 (Fast) enable the identical set of passes, so the Fast"
                        + " setting does nothing -- it is a promise in the settings UI the pack does not"
                        + " keep");

        double fancyTrace = outputScaleOfEnabledPassUsing(pack, "shaders/post/ssr_trace.fsh", 1);
        double fastTrace = outputScaleOfEnabledPassUsing(pack, "shaders/post/ssr_trace.fsh", 2);
        assertEquals(1.0, fancyTrace, 1e-9, "Fancy must keep tracing at full resolution");
        assertTrue(fastTrace < fancyTrace,
                "Fast traces into a scale " + fastTrace + " target, the same size as Fancy's -- the"
                        + " tier saves nothing");

        double fancyBlur = outputScaleOfEnabledPassUsing(pack, "shaders/post/ssr_blur.fsh", 1);
        double fastBlur = outputScaleOfEnabledPassUsing(pack, "shaders/post/ssr_blur.fsh", 2);
        assertEquals(1.0, fancyBlur, 1e-9, "Fancy must keep blurring at full resolution");
        assertTrue(fastBlur < fancyBlur,
                "the blur was the most expensive pass in the pack (1.84 ms measured, against the"
                        + " 1.12 ms trace that feeds it), so a Fast tier that halves the trace and"
                        + " leaves the blur at full resolution gives up most of its own saving");

        // The engine hands the Hi-Z level count to a trace pass by EXACT name equality
        // (GraphRunner.computeParams). A rename leaves u_Param2 at zero, which pins the tile-skip
        // clamp `min(level + 1, levelCount - 1)` at -1 and corrupts every ray, silently.
        assertEquals(Set.of("ssr_trace_fancy"),
                enabledPassNamesUsing(pack, "shaders/post/ssr_trace.fsh", 1),
                "the engine only supplies u_Param2 to a pass named exactly ssr_trace_fancy/_fast/_water");
        assertEquals(Set.of("ssr_trace_fast"),
                enabledPassNamesUsing(pack, "shaders/post/ssr_trace.fsh", 2),
                "the engine only supplies u_Param2 to a pass named exactly ssr_trace_fancy/_fast/_water");

        // The resolve samples `ssr` every frame with no #ifdef, so it must stay ungated and full-size
        // whatever the tier does upstream -- gate-consistency refuses an ungated pass reading a gated
        // target, and a half-size `ssr` would silently halve Fancy too.
        TargetSpec ssr = pack.graph().targets().get("ssr");
        assertNotNull(ssr, "the resolve's reflection input must exist");
        assertNull(ssr.enabledIf(), "`ssr` must stay ungated: the resolve reads it unconditionally");
        assertEquals(1.0, ssr.scale(), 1e-9, "`ssr` must stay full resolution for both tiers");
    }

    /** Compile-option defaults with {@code SSR_QUALITY} forced to {@code quality}. */
    private static Map<String, Integer> compileValuesAt(PackModel pack, int quality) {
        Map<String, Integer> values = new HashMap<>();
        for (var option : pack.options().values()) {
            if (option.type() != dev.icehunter.fornax.pack.option.OptionType.COMPILE) {
                continue;
            }
            try {
                values.put(option.name(), (int) Double.parseDouble(option.defaultValue()));
            } catch (NumberFormatException ignored) {
                values.put(option.name(), 0);
            }
        }
        values.put("SSR_QUALITY", quality);
        return values;
    }

    private static Set<String> enabledPassNames(PackModel pack, int quality) {
        Map<String, Integer> values = compileValuesAt(pack, quality);
        return pack.graph().passes().stream()
                .filter(p -> p.enabledIf() == null
                        || dev.icehunter.fornax.pack.graph.EnabledIfExpr.parse(p.enabledIf()).evaluate(values))
                .map(PassSpec::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> enabledPassNamesUsing(PackModel pack, String shader, int quality) {
        Set<String> enabled = enabledPassNames(pack, quality);
        return pack.graph().passes().stream()
                .filter(p -> shader.equals(p.shader()) && enabled.contains(p.name()))
                .map(PassSpec::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The scale of the target written by the single enabled pass running {@code shader}. */
    private static double outputScaleOfEnabledPassUsing(PackModel pack, String shader, int quality) {
        Set<String> names = enabledPassNamesUsing(pack, shader, quality);
        assertEquals(1, names.size(),
                "expected exactly one enabled pass running " + shader + " at SSR_QUALITY=" + quality
                        + ", found " + names);
        PassSpec pass = pack.graph().passes().stream()
                .filter(p -> p.name().equals(names.iterator().next()))
                .findFirst().orElseThrow();
        TargetSpec out = pack.graph().targets().get(pass.outputs().get(0));
        assertNotNull(out, "pass " + pass.name() + " writes undeclared target " + pass.outputs().get(0));
        return out.scale();
    }

    private static Path locatePlague() {
        Path repo = Path.of("").toAbsolutePath();
        for (Path candidate : new Path[] {
                repo.resolve("run/shaderpacks/Plague"),
                repo.getParent() == null ? null : repo.getParent().resolve("plague"),
        }) {
            if (candidate != null && Files.isRegularFile(candidate.resolve("pack.toml"))) {
                return candidate;
            }
        }
        return null;
    }
}
