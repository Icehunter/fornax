package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.BufferSize;
import dev.icehunter.fornax.pack.graph.TargetBasis;
import dev.icehunter.fornax.pack.graph.TargetFilter;
import dev.icehunter.fornax.pack.graph.TargetKind;
import dev.icehunter.fornax.pack.graph.TextureSize;
import org.jspecify.annotations.Nullable;

/**
 * {@code filter} is how a later pass's sampler reads this target -- see {@link TargetFilter}. Every
 * convenience constructor below defaults it to {@link TargetFilter#NEAREST}, which is what every
 * target got before the field existed, so adding it changed no existing behaviour.
 *
 * <p>{@code bufferSize} is meaningful ONLY on a {@link TargetKind#BUFFER} target, and its two states
 * are the whole ownership distinction for buffer targets: non-null means the PACK declared the size
 * ({@code stride_bytes} x {@code count}) and {@link dev.icehunter.fornax.pack.graph.TargetPlan}
 * allocates it like any other target; null means the buffer is ENGINE-owned and some engine call
 * site drives {@code TargetRegistry.ensureBufferSize} for it instead. Every texture target carries
 * null. See {@link BufferSize}.
 *
 * <p>{@code fixedSize} is the texture counterpart to {@code scale}: when non-null the target has an
 * exact pixel extent independent of the render/output resolution. The loader makes those forms
 * mutually exclusive, and every buffer target carries null.
 */
public record TargetSpec(String name, @Nullable String format, double scale, boolean history,
                          @Nullable String enabledIf, TargetBasis basis, TargetKind kind,
                          TargetFilter filter, @Nullable BufferSize bufferSize,
                          @Nullable TextureSize fixedSize, boolean storage) {
    /** Convenience constructor for the (still overwhelmingly common) texture, render-basis target. */
    public TargetSpec(String name, String format, double scale, boolean history, @Nullable String enabledIf) {
        this(name, format, scale, history, enabledIf, TargetBasis.RENDER, TargetKind.TEXTURE,
                TargetFilter.NEAREST);
    }

    /** Convenience constructor for a texture target with an explicit basis (e.g. {@code SceneHistory}). */
    public TargetSpec(String name, String format, double scale, boolean history,
                       @Nullable String enabledIf, TargetBasis basis) {
        this(name, format, scale, history, enabledIf, basis, TargetKind.TEXTURE, TargetFilter.NEAREST);
    }

    /**
     * The pre-{@code filter} canonical shape, preserved verbatim so existing callers keep compiling.
     *
     * <p>Deliberately NOT paired with a {@code (.., TargetBasis, TargetFilter)} sibling: that would
     * have the same arity as this one and differ only in the last enum's type, so a call passing the
     * wrong one would fail as a confusing conversion error rather than an unknown method. Use
     * {@link #withFilter} instead.
     */
    public TargetSpec(String name, @Nullable String format, double scale, boolean history,
                       @Nullable String enabledIf, TargetBasis basis, TargetKind kind) {
        this(name, format, scale, history, enabledIf, basis, kind, TargetFilter.NEAREST);
    }

    /** The pre-{@code bufferSize} canonical shape, preserved verbatim (delegating with no declared
     * buffer size) so every existing texture-target caller keeps compiling unchanged. */
    public TargetSpec(String name, @Nullable String format, double scale, boolean history,
                       @Nullable String enabledIf, TargetBasis basis, TargetKind kind,
                       TargetFilter filter) {
        this(name, format, scale, history, enabledIf, basis, kind, filter, null, null, false);
    }

    /** Compatibility constructor for the pre-fixed-extent canonical record shape. */
    public TargetSpec(String name, @Nullable String format, double scale, boolean history,
                       @Nullable String enabledIf, TargetBasis basis, TargetKind kind,
                       TargetFilter filter, @Nullable BufferSize bufferSize) {
        this(name, format, scale, history, enabledIf, basis, kind, filter, bufferSize, null, false);
    }

    /** Compatibility constructor for the pre-storage-image canonical record shape. */
    public TargetSpec(String name, @Nullable String format, double scale, boolean history,
                       @Nullable String enabledIf, TargetBasis basis, TargetKind kind,
                       TargetFilter filter, @Nullable BufferSize bufferSize,
                       @Nullable TextureSize fixedSize) {
        this(name, format, scale, history, enabledIf, basis, kind, filter, bufferSize, fixedSize, false);
    }

    /** This target with a different sampler filter; every other field unchanged. */
    public TargetSpec withFilter(TargetFilter newFilter) {
        return new TargetSpec(name, format, scale, history, enabledIf, basis, kind, newFilter,
                bufferSize, fixedSize, storage);
    }

    /**
     * An ENGINE-owned buffer-kind (SSBO) target: no pixel format, no scale, no basis, and no
     * declared size -- its bytes are set directly via
     * {@link dev.icehunter.fornax.pack.graph.TargetRegistry#ensureBufferSize} by whatever owns its
     * lifecycle (the voxel window manager, for the brick grid), never by
     * {@link dev.icehunter.fornax.pack.graph.TargetPlan#compute}. A pack declares one of these
     * purely so {@code GraphValidator} recognizes the name as a legal pass reference.
     *
     * <p>Filter is meaningless here -- a buffer binds as a texel or storage buffer, never through a
     * sampler -- so it carries NEAREST as an inert placeholder rather than a nullable field.
     */
    public static TargetSpec buffer(String name, @Nullable String enabledIf) {
        return buffer(name, enabledIf, null);
    }

    /**
     * A buffer-kind target with an optional PACK-declared size. A non-null {@code size} is what
     * moves this target from "engine sizes it" to "{@link
     * dev.icehunter.fornax.pack.graph.TargetPlan} sizes it" -- see {@link BufferSize} and this
     * record's own doc comment.
     */
    public static TargetSpec buffer(String name, @Nullable String enabledIf, @Nullable BufferSize size) {
        return new TargetSpec(name, null, 0.0, false, enabledIf, TargetBasis.RENDER, TargetKind.BUFFER,
                TargetFilter.NEAREST, size, null, false);
    }
}
