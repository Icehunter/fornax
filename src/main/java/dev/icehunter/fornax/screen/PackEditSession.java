package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.PackDiscovery;
import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.PackValuesFile;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.util.RendererReload;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One editing session shared by every nesting level of {@link PackSettingsScreen}: ALL edits --
 * runtime sliders and compile toggles alike -- stage into {@link #staged} and touch nothing live.
 * {@link #apply()} is the single commit point: persist the per-pack values file, then either one
 * {@code GraphRunner.rebuild} (any compile option changed -- shader text differs) or one runtime
 * buffer update (sliders only), plus one renderer reload where pipelines are affected. {@link
 * #discard()} throws staged edits away. No per-change rebuilds, no live writes from the screens.
 *
 * <p>Three maps, three distinct roles -- read each field's own doc for the full reasoning, this is
 * just the map: {@link #staged} is the live scratch state every LISTENER (drag/click, one option at a
 * time, ring-safe) writes into and every getter's {@link #get} reads; {@link #applied} is the last
 * REAL persist/rebuild snapshot, touched only by {@link #apply()}'s commit and {@link #discard()}'s
 * revert, and is what every dirty-tracking query ({@link #isDirty}, {@link #isOptionDirty}, {@link
 * #appliedValue}) compares {@link #staged} against; {@link #committed} is the YACL binding GETTER's
 * own source of truth ({@link #getApplied}), advanced eagerly by the binding SETTER ({@link
 * #stageQuiet}/{@link #stageAllQuiet}) but never by the listener -- required because YACL's {@code
 * finishOrSave} re-reads the getter, synchronously, immediately after calling the setter and BEFORE
 * ever calling {@link #apply()} (confirmed by bytecode trace; see {@link #committed}'s doc for the
 * full mechanism and the silent-data-loss failure mode a naive two-map design hits).
 */
final class PackEditSession {
    private final PackModel model;
    private final Map<String, String> applied;
    private final Map<String, String> staged;

    /**
     * The YACL binding GETTER's source of truth for every migrated row -- distinct from {@link
     * #applied} (the last REAL persist/rebuild snapshot, touched only by {@link #apply()}, {@link
     * #discard()}, and the constructor). This field exists to satisfy a YACL-internal consistency
     * requirement discovered while fixing the "Save never enables" bug -- confirmed by {@code javap
     * -c} against YACL 3.9.5's {@code YACLScreen.finishOrSave()} bytecode, not assumed: that method
     * runs, in this exact order, (1) ONE synchronous apply-value loop calling every changed option's
     * binding SETTER ({@code stateManager.apply()} -&gt; {@code binding.setValue(pendingValue)}), (2)
     * ONE synchronous "did the setter take?" loop that re-reads the binding GETTER for every option
     * and, if it still disagrees with the option's pending value, force-resyncs it ({@code
     * stateManager.sync()}, which -- per {@code SimpleStateManager.set()}'s bytecode -- fires the
     * option's {@code .listener} AGAIN with the reverted value whenever old and new pending values
     * differ) and logs {@code "value mismatch after applying"}, THEN ONLY THEN (3) {@code
     * config.saveFunction().run()} -- our own {@link #apply()}.
     *
     * <p>Step (2) runs BEFORE step (3). If the binding setter only wrote {@link #staged} (which
     * {@link #apply()} hasn't copied into {@link #applied} yet, since {@link #apply()} hasn't run),
     * step (2) would see a mismatch for EVERY option the Save just touched: it force-reverts that
     * option's pending value, which re-fires the row's {@code .listener} with the reverted value --
     * silently overwriting {@link #staged} back to the pre-edit value BEFORE {@link #apply()} ever
     * runs. By the time {@link #apply()} does run, {@link #isDirty()} sees {@link #staged} already
     * equal to {@link #applied} again and no-ops. Net effect (verified by full bytecode trace, not
     * just reasoned about): clicking Save would log a scary error for every changed option, silently
     * discard every edit, and never call {@link dev.icehunter.fornax.pack.PackValuesFile#save} --
     * worse than the original bug, since Save now visibly "works" (the button is enabled and
     * clickable) while quietly doing nothing.
     *
     * <p>Routing the binding SETTER ({@link #stageQuiet}, {@link #stageAllQuiet}) through this field
     * instead closes that gap: the getter agrees with the setter's own just-written value immediately
     * (step 2's re-read passes cleanly, no revert, no error), while {@link #apply()}'s dirty-tracking
     * still compares {@link #staged} against {@link #applied} (untouched by the setter), so step (3)
     * still has real persist/rebuild work to do. The binding LISTENER ({@link #stage}, {@link
     * #stageAll}) never touches this field -- only {@link #staged} -- which is exactly what keeps a
     * live-preview edit disagreeing with the getter (and therefore YACL's {@code changed()} true)
     * until Save: the mechanism the "Save never enables"/"no save unless Custom" bug report hinged on.
     * {@link #apply()} and {@link #discard()} both resync this field alongside {@link #applied} so it
     * never drifts outside the one synchronous {@code finishOrSave()} window where it's meant to lead.
     */
    private final Map<String, String> committed;

    /**
     * The live-preview GPU write: production always wires this to {@link
     * GraphRunner#updateRuntimeValues}, invoked from {@link #livePreviewIfRuntime} (the {@link
     * #stage} path) and {@link #stageAll}'s runtime block. {@link #apply()} and {@link #discard()}
     * call {@code GraphRunner.updateRuntimeValues} directly instead of through this hook -- their
     * resync/undo writes are a distinct call site from live-preview and are untouched by this seam.
     * Routed through an instance field (rather than called statically) so tests can inject a counting
     * hook and observe invocation COUNT, not just survive the call -- {@code GraphRunner}'s static
     * path silently no-ops headlessly (null {@code optionsBuffer}), so merely "did not throw" proves
     * nothing about whether live-preview fired.
     */
    private final Consumer<Map<String, Float>> runtimePreviewHook;

    PackEditSession(PackModel model) {
        this(model, PackSettingsSupport.mergedValues(model), GraphRunner::updateRuntimeValues);
    }

    /**
     * Test-only seam: builds directly from a caller-supplied "applied" snapshot, bypassing the
     * values-file/game-dir read {@link #PackEditSession(PackModel)} performs via {@link
     * PackSettingsSupport#mergedValues}. That read hits {@code FabricLoaderImpl.getGameDir()}, which
     * throws {@code IllegalStateException: invoked too early?} outside a real client launch (confirmed
     * live -- this project runs plain JUnit, no dev-launch game-dir bootstrap) -- so headless unit
     * tests that need a {@link PackEditSession} over a fixture {@link PackModel} construct through
     * here instead. Production code always uses the single-argument constructor. Live-preview still
     * routes to the real {@link GraphRunner#updateRuntimeValues} (production behavior); tests that need
     * to COUNT live-preview invocations use the three-argument seam below instead.
     */
    PackEditSession(PackModel model, Map<String, String> appliedValues) {
        this(model, appliedValues, GraphRunner::updateRuntimeValues);
    }

    /**
     * Test-only seam: same as {@link #PackEditSession(PackModel, Map)} but with an injected
     * live-preview hook in place of the real {@link GraphRunner#updateRuntimeValues}, so tests can
     * assert on invocation COUNT (e.g. a counting {@link Consumer}) instead of merely surviving the
     * call -- see {@link #runtimePreviewHook}'s doc for why "did not throw" is not observability here.
     * Production code never uses this constructor.
     */
    PackEditSession(PackModel model, Map<String, String> appliedValues,
            Consumer<Map<String, Float>> runtimePreviewHook) {
        this.model = model;
        this.applied = new LinkedHashMap<>(appliedValues);
        this.staged = new LinkedHashMap<>(this.applied);
        this.committed = new LinkedHashMap<>(this.applied);
        this.runtimePreviewHook = runtimePreviewHook;
    }

    PackModel model() {
        return this.model;
    }

    String get(String name) {
        return this.staged.get(name);
    }

    /**
     * The YACL binding GETTER's current value for this option -- reads {@link #committed}, NOT
     * {@link #staged} (a listener's live-preview write must never retroactively satisfy YACL's own
     * pending-vs-getter {@code changed()} check -- that's the original "Save never enables" bug) and
     * NOT directly {@link #applied} either (see {@link #committed}'s doc for the bytecode-verified
     * reason the binding setter needs its own eagerly-updated view, distinct from the real persist
     * snapshot {@link #apply()}'s dirty-check compares against).
     */
    String getApplied(String name) {
        return this.committed.get(name);
    }

    /**
     * Stages one value, live-previewing it ONLY if it actually changed the effective staged value
     * (compared in the option's own terms via {@link #valuesEqual}, the same equality {@link
     * #isOptionDirty} uses). This is what makes YACL's construction-time listener fire safe: {@code
     * OptionImpl}'s constructor invokes each option's {@code .listener} once with the CURRENT value
     * (see {@link MetaBinding#current}), so an unchanged re-stage must never reach {@link
     * #runtimePreviewHook} -- zero ring rotations for a value that never moved, not just one.
     */
    void stage(String name, String value) {
        boolean changed = !valuesEqual(this.model.options().get(name), this.staged.get(name), value);
        this.staged.put(name, value);
        if (changed) {
            livePreviewIfRuntime(name, value);
        }
    }

    /**
     * Records a staged value WITHOUT any live-preview GPU write -- the save-time binding-setter path
     * for the YACL binding layer. Per-drag live-preview is driven by the row's own listener (one
     * option at a time, ring-safe); staging every changed option's value through this record-only
     * method at save time avoids wrapping the 3-slot runtime ring buffer when a single Save carries
     * 3+ changed runtime options (the same hazard {@link #stageAll} guards). {@link #apply()} then
     * does the one combined runtime resync.
     *
     * <p>Also advances {@link #committed} (the binding GETTER's source of truth) to {@code value} --
     * see {@link #committed}'s doc for why: YACL re-reads the getter immediately after calling this
     * setter, in the same synchronous {@code finishOrSave()}, and force-reverts (with an error log)
     * anything that still disagrees.
     *
     * <p>Guards against a cross-row stale-reapply hazard baked into YACL's own {@code finishOrSave}
     * (javap/CFR-confirmed against 3.9.5): its apply-value loop calls EVERY option's setter whenever
     * that option's {@code changed()} is true, and {@code changed()} can go true for a row the user
     * never touched at all -- {@code PackManageScreen#create} builds one {@code OptionImpl} per row,
     * across EVERY tab, all at screen-open time, so an untouched row's own YACL-internal pending value
     * stays frozen at whatever {@link #getApplied} returned then. If an EARLIER setter in the SAME
     * loop pass (e.g. a meta row on the Quality tab, processed first, sharing this option as one of
     * its assign keys -- see {@link #stageAllQuiet}) advances {@link #committed} for this key, that
     * frozen, untouched value now disagrees with the shifted getter, looks "changed" to YACL, and gets
     * handed back here as this row's own setter call -- as if the user meant it. Applying it verbatim
     * would silently revert the fresher edit (confirmed live: "select a Quality-tab tier, press Save
     * while staying on the tab, it snaps back instead of persisting").
     *
     * <p>The discriminator: skip the write ONLY when {@code value} exactly reproduces this session's
     * ORIGINAL (session-open) snapshot -- {@link #applied}, untouched until {@link #apply()} runs --
     * for this key, AND {@link #committed} has ALREADY moved away from that same snapshot this burst
     * (some other setter got there first). A genuine edit's value only coincides with the untouched
     * {@link #applied} snapshot in the rare case the user manually set a row back to its own original
     * value -- skipping that is harmless, since {@link #applied}'s value was already in effect and
     * nothing else has touched this key. This only inspects state already on this session; it never
     * needs to know WHY a setter call was made, only whether honoring it would regress a key someone
     * else already advanced.
     */
    void stageQuiet(String name, String value) {
        if (isStaleReapply(name, value)) {
            return;
        }
        this.staged.put(name, value);
        this.committed.put(name, value);
    }

    /**
     * See {@link #stageQuiet}'s doc for the full mechanism this guards against. {@code appliedValue}
     * is this key's session-open snapshot ({@link #applied}, untouched until {@link #apply()} runs);
     * a setter call is "stale" only when its value reproduces that exact snapshot WHILE {@link
     * #committed} already disagrees with it -- i.e. something else already staged a real change to
     * this key this burst, and this call is trying to drag it back to where the whole session started.
     */
    private boolean isStaleReapply(String name, String value) {
        PackOption option = this.model.options().get(name);
        String appliedValue = this.applied.get(name);
        if (!valuesEqual(option, value, appliedValue)) {
            return false;
        }
        return !valuesEqual(option, this.committed.get(name), appliedValue);
    }

    /**
     * Batched counterpart to {@link #stageQuiet}: records every entry in {@code values} WITHOUT any
     * live-preview GPU write -- the save-time binding-setter path for the YACL meta-row binding layer
     * ({@code MetaBinding.selectQuiet}). YACL's {@code finishOrSave} runs ONE synchronous
     * apply-value loop over every changed option before the save callback fires, so N changed meta
     * rows in one Save burst means N binding-setter calls in that one synchronous loop; routing each
     * through {@link #stageAll} instead would fire N separate {@code GraphRunner.updateRuntimeValues}
     * ring rotations and wrap the 3-slot ring within that single un-submitted frame -- the exact
     * {@code IllegalStateException: Cannot wait on a fence for the current submit} crash documented
     * on {@link #stageAll}. This method skips the runtime write entirely (no rotation at all, not
     * even one); dirty-tracking is otherwise identical to {@link #stageAll} so {@link #apply()} still
     * resyncs/rebuilds correctly from whatever landed in {@link #staged} -- {@link #apply()}'s own
     * single combined resync is the only runtime write this burst ever produces.
     *
     * <p>Also advances {@link #committed} for every entry -- same reason as {@link #stageQuiet}'s
     * single-key version: YACL's finishOrSave re-reads the getter for each changed option right after
     * this setter runs, in the same synchronous call, and reverts (with an error log) anything that
     * still disagrees with the pending value.
     *
     * <p>Routes every entry through {@link #stageQuiet} (one key at a time, not a bulk {@code
     * putAll}) so the same stale-reapply guard applies here too -- a meta's OWN assign keys are never
     * accidentally skipped by it (their values almost never coincide with the untouched {@link
     * #applied} snapshot -- see {@link #stageQuiet}'s doc), but this keeps the two setter paths'
     * safety identical instead of only guarding the single-key one.
     */
    void stageAllQuiet(Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            stageQuiet(entry.getKey(), entry.getValue());
        }
    }

    /**
     * RUNTIME options live-preview immediately: a single {@code u_PackOptions} buffer write, no
     * recompile, no reload -- dragging a strength slider shows on screen as it moves. COMPILE
     * options never touch anything here (their effect requires a shader rebuild, which only
     * {@link #apply()} may trigger). {@link #discard()} restores the session-start snapshot to the
     * buffer, so Cancel undoes the preview too.
     */
    private void livePreviewIfRuntime(String name, String value) {
        PackOption option = this.model.options().get(name);
        if (option == null || option.type() != OptionType.RUNTIME) {
            return;
        }
        try {
            this.runtimePreviewHook.accept(Map.of(name, Float.parseFloat(value)));
        } catch (NumberFormatException ignored) {
            // Non-numeric runtime value (shouldn't happen for v0.1's float-only runtime options).
        }
    }

    /**
     * Stages every entry in {@code values} at once, live-previewing every touched RUNTIME option in a
     * SINGLE combined {@link GraphRunner#updateRuntimeValues} call. Calling {@link #stage} in a loop
     * instead fires one {@code MappableRingBuffer} rotation PER option -- and that ring only has 3
     * slots, so staging 3+ runtime options in one synchronous UI burst (Reset, a Profile switch, or an
     * Import) throws {@code IllegalStateException: Cannot wait on a fence for the current submit} the
     * instant the ring wraps within a single un-submitted frame (confirmed live -- this is a real
     * production crash, not a theoretical concern). Every caller that stages more than one option at
     * once (Reset, profile selection, Import) MUST route through this method, never a loop of
     * individual {@link #stage} calls.
     */
    void stageAll(Map<String, String> values) {
        // Compare against the PRE-overwrite staged values (what's effective right now) before
        // touching staged, so an unchanged entry -- e.g. a matching-tier reselect, or YACL's
        // construction-time listener firing every meta row with its own current value -- never
        // reaches the runtime-preview hook. Every entry still lands in staged unconditionally: this
        // filter only gates the GPU write, not what {@link #apply()} eventually persists.
        Map<String, Float> runtimeUpdates = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            PackOption option = this.model.options().get(name);
            if (option == null || option.type() != OptionType.RUNTIME) {
                continue;
            }
            if (valuesEqual(option, this.staged.get(name), value)) {
                continue;
            }
            try {
                runtimeUpdates.put(name, Float.parseFloat(value));
            } catch (NumberFormatException ignored) {
                // Non-numeric runtime value (shouldn't happen for v0.1's float-only runtime options).
            }
        }
        this.staged.putAll(values);
        if (!runtimeUpdates.isEmpty()) {
            this.runtimePreviewHook.accept(runtimeUpdates);
        }
    }

    /** Stages every option back to its pack-declared default (the main screen's Reset button). */
    void stageDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (PackOption option : this.model.options().values()) {
            defaults.put(option.name(), option.defaultValue());
        }
        stageAll(defaults);
    }

    boolean isDirty() {
        return !this.staged.equals(this.applied);
    }

    /** Whether this one option's staged value differs from its last-applied value (UI accent). */
    boolean isOptionDirty(String name) {
        return !valuesEqual(this.model.options().get(name), this.staged.get(name), this.applied.get(name));
    }

    /** The value this option had when the session opened (or was last applied) -- per-row undo target. */
    String appliedValue(String name) {
        return this.applied.get(name);
    }

    /** Whether this option's staged value already IS the pack-declared default -- per-row reset gate. */
    boolean isStagedDefault(String name) {
        PackOption option = this.model.options().get(name);
        if (option == null) {
            return true;
        }
        return valuesEqual(option, this.staged.get(name), option.defaultValue());
    }

    /**
     * Whether every entry in {@code values} already equals the corresponding staged value, in each
     * option's own equality terms ({@link #valuesEqual}). Defense-in-depth seam for {@link
     * MetaBinding#select}: {@link #stageAll} already skips the live-preview write for unchanged
     * entries, so this check doesn't change ring-safety, but it lets a matching-tier reselect (e.g.
     * YACL's construction-time listener re-affirming the tier that's already in effect) skip {@link
     * #stageAll} -- and the {@link Map#putAll} it performs -- entirely.
     */
    boolean allStagedMatch(Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            PackOption option = this.model.options().get(entry.getKey());
            if (!valuesEqual(option, this.staged.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Value equality in each option's own terms: runtime options are floats, where "0.5" and "0.50"
     * (a slider's own String.valueOf against an annotation's literal) must compare equal; everything
     * else compares as the exact stored string.
     */
    private static boolean valuesEqual(PackOption option, String a, String b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        if (option != null && option.type() == OptionType.RUNTIME && a != null && b != null) {
            try {
                return Float.parseFloat(a) == Float.parseFloat(b);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean compileDirty() {
        for (PackOption option : this.model.options().values()) {
            if (option.type() == OptionType.COMPILE && isOptionDirty(option.name())) {
                return true;
            }
        }
        return false;
    }

    /** Commits every staged edit at once; a clean session is a no-op. */
    void apply() {
        if (!isDirty()) {
            return;
        }

        PackValuesFile.save(PackSettingsSupport.valuesPath(this.model), this.staged);

        if (compileDirty()) {
            // Compile options change the generated shader TEXT -- one full rebuild (which reloads
            // resource packs so blaze3d recompiles) then one renderer reload so Sodium's cached
            // terrain pipelines rebuild against the new state atomically. At most ONE of these per
            // apply, and none at all for runtime-only edits (those already live-previewed). The
            // renderer reload chains on the rebuild's async resource reload -- recompiling terrain
            // before it completes reads the PREVIOUS snapshot's shader text (see
            // RuntimeShaderPack.reload), silently discarding the compile edit until the next reload.
            GraphRunner.rebuild(this.model,
                    PackDiscovery.loadShaderSources(this.model.root()),
                    PackSettingsSupport.compileIntMap(this.model, this.staged),
                    PackSettingsSupport.runtimeFloatMap(this.model, this.staged))
                    .thenRunAsync(RendererReload::request, Minecraft.getInstance())
                    .exceptionally(t -> {
                        FornaxMod.LOGGER.error(
                                "[Fornax] Resource reload failed after compile-option apply; renderer reload skipped", t);
                        return null;
                    });
        } else {
            // Runtime-only edits already live-previewed on each stage(); this is just a resync.
            GraphRunner.updateRuntimeValues(PackSettingsSupport.runtimeFloatMap(this.model, this.staged));
        }

        this.applied.clear();
        this.applied.putAll(this.staged);
        // Keep the getter's view in lockstep with the real persist snapshot: for the YACL path
        // committed already equals staged here (every changed key went through stageQuiet/
        // stageAllQuiet before this ran), so this is a no-op there; for the legacy PackSettingsScreen
        // path (which calls apply() directly, never through a YACL binding setter) committed would
        // otherwise stay stuck at the pre-apply values forever. See committed's doc.
        this.committed.clear();
        this.committed.putAll(this.staged);
    }

    /**
     * Throws every staged edit away, reverting to the last-applied snapshot -- the ONE explicit
     * discard action left in the YACL flow: {@code YACLScreen}'s "Undo" button ({@code
     * mixin.yacl.YACLScreenCloseMixin}'s {@code undo()} injection routes here via {@link
     * PackChromeActions.Context#discardPending()}). Every OTHER way of leaving the screen (Done,
     * Escape, or YACL's own "Cancel" button, which despite its label still just calls {@code
     * onClose()} under the hood) now settles on {@link #apply()} instead, via that same mixin's
     * {@code onClose()} injection -- so this is no longer "unreachable"; it's reachable from exactly
     * one place, on purpose.
     */
    void discard() {
        this.staged.clear();
        this.staged.putAll(this.applied);
        // Undo must also revert any live previews: push the session-start (last-applied) runtime
        // values back into the u_PackOptions buffer.
        GraphRunner.updateRuntimeValues(PackSettingsSupport.runtimeFloatMap(this.model, this.applied));
        // Resync the getter's view too, in case a since-abandoned Save had advanced it ahead of
        // applied (see committed's doc) -- defensive parity for the same reason apply() resyncs it.
        this.committed.clear();
        this.committed.putAll(this.applied);
    }
}
