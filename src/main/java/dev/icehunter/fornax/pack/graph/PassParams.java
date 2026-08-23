package dev.icehunter.fornax.pack.graph;

/**
 * The {@code u_PassParams} std140 block every {@link FullscreenPassRunner}-built pipeline declares:
 * the generalization of the current hardcoded pipeline's per-pass mechanical uniforms
 * ({@code u_SsaoParams}/{@code u_SsrParams}/{@code u_TaaParams}/{@code u_HiZParams}/
 * {@code u_DownsampleSettings}) down to the handful of purely mechanical values every full-screen
 * pass shape needs -- texel size of its primary output, two pass-specific scalars, the per-frame
 * sun direction, and the celestials atlas's sun/moon sprite rects (a pack's own tunables ride
 * {@code u_PackOptions} instead; see {@code PackOptionsLayout}).
 *
 * <p>64 bytes total (std140): {@code vec2 u_PassTexelSize} at 0, {@code float u_Param2} at 8,
 * {@code float u_Param3} at 12, {@code vec3 u_SunDirection} at 16 (a vec3 costs a full 16 bytes here,
 * not the spec-minimal 12 -- see {@code PackOptionsLayout}'s own javadoc for why), {@code vec4
 * u_SunSpriteRect} at 32, {@code vec4 u_MoonSpriteRect} at 48. The texel-size field is named
 * {@code u_PassTexelSize}, not {@code u_TexelSize}: instance-nameless uniform-block fields live at
 * GLSL global scope, and {@code fornax:globals.glsl}'s {@code u_Globals} block already claims
 * {@code u_TexelSize} there -- a shader importing globals and declaring this block would fail to
 * compile with a field-shadowing error. Only the names differ; the layout contract is otherwise
 * unchanged.
 *
 * <p>{@code u_SunDirection} exists because the real {@code gbuffer_resolve.fsh} independently
 * reproduces terrain's bump-lighting math and has no vertex-shader varying to receive the sun
 * direction through (unlike terrain.vsh's forwarded push-constant value) -- every other fullscreen
 * pass simply ignores these three trailing floats. Only passes that actually need it (today: just
 * {@code resolve}) get a non-zero value from {@code GraphRunner.computeParams}.</p>
 *
 * <p>{@code u_SunSpriteRect}/{@code u_MoonSpriteRect} carry the current frame's sun sprite and
 * phase-indexed moon sprite {@code {u0, v0, u1, v1}} rects within the {@code builtin.celestials}
 * atlas (see {@code CelestialSprites}) -- a pass painting the sun/moon disc against that atlas needs
 * its sprite's rect to map a full-quad UV into the right sub-region, since 26.2 stores each moon
 * phase as its own atlas sprite rather than one sheet sliced by cell math. Zero-rect when
 * uncaptured, same garbage-VRAM discipline as every other frame-state holder.</p>
 *
 * <p><b>Growing this buffer past a GLSL block's declaration is legal.</b> {@code BUFFER_SIZE} sizes
 * the CPU-side {@code MappableRingBuffer} every {@link FullscreenPassRunner} allocates and binds as
 * {@code u_PassParams} -- it is the upper bound on what any pass's shader may read, not a
 * per-shader-mandatory size. A fullscreen pass whose GLSL still declares the block at its old 32
 * bytes (i.e. never references {@code u_SunSpriteRect}/{@code u_MoonSpriteRect}) compiles and runs
 * unchanged: Blaze3D binds the whole (larger) buffer to the uniform slot regardless of how much of
 * it the compiled shader's block declaration actually covers, and the driver simply never reads the
 * unread trailing bytes. Only a pass that wants the new fields needs to redeclare the block at 64
 * bytes.</p>
 */
public final class PassParams {
    public static final int BUFFER_SIZE = 64;

    /**
     * The compute-pass push-constant contract's base size -- deliberately NOT {@link #BUFFER_SIZE}.
     * A compute pass has no reserved uniform-buffer slot for {@code u_PassParams} (see {@code
     * ComputePipelineBuilder}'s own doc comment); it receives the texel-size/param2/param3/sun-direction
     * fields (28 meaningful bytes, padded to 32) as a Vulkan push constant instead, and any {@link
     * ExtraPushConstants} a specific pass needs (e.g. {@code EmitterLightExtra}) are appended
     * immediately after, at a byte offset a pack's own compute shader hardcodes against. Growing
     * {@link #BUFFER_SIZE} for the new sun/moon sprite rects -- fields no compute pass consumes, and
     * a real GPU push-constant range unlike the uniform-buffer path -- must not shift that offset out
     * from under an already-published pack's GLSL, so the compute push-constant path is pinned to its
     * own, independently-stable 32-byte base instead of following {@link #BUFFER_SIZE}. See {@code
     * ComputePipelineBuilder#build}/{@code ComputePassRunner#run}, the only consumers.
     */
    public static final int PUSH_CONSTANT_BASE_SIZE = 32;

    private static final float[] ZERO_RECT = {0f, 0f, 0f, 0f};
    private static final ThreadLocal<PassParams> REUSABLE = ThreadLocal.withInitial(PassParams::new);

    private float texelSizeX;
    private float texelSizeY;
    private float param2;
    private float param3;
    private float sunDirX;
    private float sunDirY;
    private float sunDirZ;
    /**
     * True sun elevation, carried in the padding word a vec3 already costs.
     *
     * <p>u_SunDirection is the ACTIVE light -- sun by day, moon by night -- so its own height cannot
     * tell a shader whether it is day. Reading it that way lit midnight exactly like noon, because
     * the moon at midnight sits where the sun sits at noon. A vec3 occupies 16 bytes in std140 and
     * uses 12, so this rides in bytes 28..31 free: no layout change, no buffer growth, and shaders
     * that declare vec3 keep working unchanged.
     */
    private float trueSunHeight;
    private float[] sunSpriteRect = ZERO_RECT;
    private float[] moonSpriteRect = ZERO_RECT;

    private PassParams() {
    }

    public static PassParams of(int outputWidth, int outputHeight) {
        return new PassParams().reset(outputWidth, outputHeight);
    }

    /**
     * Returns the current thread's scratch instance reset to defaults. Graph execution consumes a
     * parameter block synchronously before computing the next pass, so one render-thread object
     * replaces the former record-plus-copy chain without extending any value's lifetime.
     */
    static PassParams reusable(int outputWidth, int outputHeight) {
        return REUSABLE.get().reset(outputWidth, outputHeight);
    }

    private PassParams reset(int outputWidth, int outputHeight) {
        texelSizeX = 1.0f / Math.max(1, outputWidth);
        texelSizeY = 1.0f / Math.max(1, outputHeight);
        param2 = 0.0f;
        param3 = 0.0f;
        sunDirX = 0.0f;
        sunDirY = 0.0f;
        sunDirZ = 0.0f;
        trueSunHeight = 1.0f;
        sunSpriteRect = ZERO_RECT;
        moonSpriteRect = ZERO_RECT;
        return this;
    }

    public PassParams withParam2(float value) {
        param2 = value;
        return this;
    }

    public PassParams withParam3(float value) {
        param3 = value;
        return this;
    }

    public PassParams withTrueSunHeight(float height) {
        trueSunHeight = height;
        return this;
    }

    public float trueSunHeight() {
        return trueSunHeight;
    }

    public PassParams withSunDirection(float x, float y, float z) {
        sunDirX = x;
        sunDirY = y;
        sunDirZ = z;
        return this;
    }

    /** {@code rect} must be {@code {u0, v0, u1, v1}} -- see {@code CelestialSprites.sunRect()}. */
    public PassParams withSunSpriteRect(float[] rect) {
        sunSpriteRect = rect;
        return this;
    }

    /** {@code rect} must be {@code {u0, v0, u1, v1}} -- see {@code CelestialSprites.moonPhaseRect(int)}. */
    public PassParams withMoonSpriteRect(float[] rect) {
        moonSpriteRect = rect;
        return this;
    }

    public float texelSizeX() {
        return texelSizeX;
    }

    public float texelSizeY() {
        return texelSizeY;
    }

    public float param2() {
        return param2;
    }

    public float param3() {
        return param3;
    }

    public float sunDirX() {
        return sunDirX;
    }

    public float sunDirY() {
        return sunDirY;
    }

    public float sunDirZ() {
        return sunDirZ;
    }

    public float[] sunSpriteRect() {
        return sunSpriteRect;
    }

    public float[] moonSpriteRect() {
        return moonSpriteRect;
    }
}
