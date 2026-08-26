package dev.icehunter.fornax.pack.layout;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A synthetic, in-memory {@link PackResources} that serves {@link DefineRewriter}-rewritten GLSL source
 * strings under the {@code fornax_runtime} namespace as {@code fornax_runtime:shaders/<path>.fsh|.vsh|.glsl}.
 *
 * <p>There is no backing directory or jar entry for any of this content -- it is generated at runtime from
 * the current pack's shader templates plus the player's current compile-option and runtime-option values,
 * and changes every time those values change ({@link #reload(Map)}).
 *
 * <p><b>Registration is intentionally not done here.</b> Fabric API 0.152.1+26.2's only public "programmatic
 * resource pack" entry points ({@code ResourceLoader.registerBuiltinPack} /
 * {@code ResourceManagerHelper.registerBuiltinResourcePack}) are hard-wired to {@code ModNioPackResources}:
 * real files under a sub-path of the mod's own jar/dev root (confirmed by reading
 * {@code ResourceLoaderImpl.registerBuiltinPack}, decompiled from the real
 * {@code fabric-resource-loader-v1-2.0.13} jar on this project's classpath). There is no public Fabric API
 * for adding an arbitrary, non-file-backed {@link PackResources} to the client's real
 * {@code PackRepository} in this version -- that requires a mixin into wherever the client's
 * {@code PackRepository} is constructed, adding this pack's {@code Pack} via a
 * {@code RepositorySource}. That wiring (and the mixin it needs) is exercised by {@code
 * MinecraftPackRepositoryMixin}; this class only needs the {@link PackResources} implementation
 * itself to exist and be correct.
 */
public final class RuntimeShaderPack implements PackResources {
    public static final String NAMESPACE = "fornax_runtime";

    /**
     * The single, long-lived instance registered into the client's real {@code PackRepository} by
     * {@code dev.icehunter.fornax.mixin.vanilla.MinecraftPackRepositoryMixin}. One instance
     * for the whole client session, not one per pack (re)load: {@link #reload} swaps its content and
     * asks blaze3d to recompile against the new sources, which is what lets {@code GraphRunner}
     * install a different pack's shaders without re-registering a new {@code RepositorySource} pack
     * entry (fabric-api / vanilla's {@code PackRepository} has no public API to add/remove a single
     * pack entry after construction -- only reload the whole repository against a fixed source list).
     */
    private static final RuntimeShaderPack INSTANCE = new RuntimeShaderPack(Map.of());

    public static RuntimeShaderPack getInstance() {
        return INSTANCE;
    }

    private final PackLocationInfo location;
    private volatile Map<String, String> sources;

    /**
     * The same sources with comments stripped ({@link GlslCommentStripper}), which is what is
     * SERVED to the resource manager -- and therefore what vanilla's {@code ShaderManager} feeds to
     * Mojang's {@code GlslPreprocessor}. See that class for why: its comment-detection regex
     * overflows the stack on sources this comment-dense, taking the whole reload task with it and
     * making Minecraft drop every selected resource pack. Computed once per reload rather than per
     * read. {@link #sourceOrNull} and {@link #sourcesSnapshot} deliberately keep the UNSTRIPPED
     * text, so compute-pass compilation and the vanilla-override extractor see the source as
     * authored.
     */
    private volatile Map<String, String> servedSources;

    /**
     * Vanilla asset path ({@code shaders/core/<name>.fsh}) -> rewritten source text, for whatever
     * vanilla core shaders the active pack currently overrides (see {@link VanillaShaderOverrides}).
     * Served under the {@code minecraft} namespace rather than {@link #NAMESPACE} -- these are NOT
     * fornax's own synthetic shader identifiers, they are literal replacements for vanilla's own
     * {@code assets/minecraft/shaders/core/*.fsh} files, so a lookup for e.g.
     * {@code minecraft:shaders/core/lightmap.fsh} must resolve here instead of to vanilla's real
     * pack. {@code MultiPackResourceManager} only ever consults a pack for a namespace it advertises
     * (see {@link #getNamespaces}), and this pack already sits at {@code Position.TOP} +
     * {@code fixedPosition = true} in the client's pack list (see {@code
     * MinecraftPackRepositoryMixin}) -- provably the highest-priority entry for any namespace it
     * claims (bytecode-verified in {@code .superpowers/sdd/lightmap-override-research.md}, Q1) -- so
     * once this map advertises {@code minecraft}, an override here always wins over vanilla's own
     * copy of the same path with no further mixin needed. Empty by default: with no active override,
     * this pack does not advertise the {@code minecraft} namespace at all and vanilla's own pack
     * resolves every {@code minecraft:*} lookup exactly as it always has.
     */
    private volatile Map<String, String> vanillaOverrides = Map.of();

    /**
     * Vanilla asset path ({@code textures/environment/<name>}) -> raw bytes, for whatever binary
     * vanilla assets the active pack currently overrides (see {@link VanillaAssetOverrides}) -- the
     * binary counterpart to {@link #vanillaOverrides}, served under the same {@code minecraft}
     * namespace for the same reason (a literal replacement for a real {@code assets/minecraft/...}
     * file, not a {@link #NAMESPACE} identifier). Kept as a separate map rather than folded into
     * {@link #vanillaOverrides} because PNG bytes are not UTF-8 text -- round-tripping them through a
     * {@code String} would corrupt the image data. Empty by default, exactly like {@link
     * #vanillaOverrides}: with no active override this pack does not contribute to whether the
     * {@code minecraft} namespace is advertised at all.
     */
    private volatile Map<String, byte[]> vanillaBinaryOverrides = Map.of();

    /**
     * Set once by {@code FornaxMod} via Fabric's {@code ClientLifecycleEvents.CLIENT_STARTED}.
     * Guarding {@link #reload} on {@code Minecraft.getInstance() != null} is NOT sufficient: Fabric
     * fires client entrypoints from inside {@code Minecraft}'s own constructor, after the singleton
     * instance is assigned but before its final fields (e.g. {@code gui}) exist -- so at bring-up
     * {@code getInstance()} is already non-null while {@code reloadResourcePacks()} would still NPE
     * on the half-constructed client. Only a real "the client finished starting" signal is safe
     * to gate on.
     */
    private static volatile boolean clientStarted;

    public RuntimeShaderPack(Map<String, String> initialSources) {
        this.location = new PackLocationInfo(NAMESPACE, Component.literal("Fornax Runtime Shaders"),
                PackSource.BUILT_IN, java.util.Optional.empty());
        this.sources = Map.copyOf(initialSources);
        this.servedSources = stripAll(this.sources);
    }

    /** Called once from {@code FornaxMod}'s {@code CLIENT_STARTED} listener -- see {@link #clientStarted}. */
    public static void markClientStarted() {
        clientStarted = true;
    }

    /**
     * Swaps in freshly rewritten sources and, once the client is fully started, asks it to reload
     * resource packs so blaze3d recompiles against the new text. The bootstrap call ({@code
     * GraphRunner.loadDevGraphFixture()} from the client entrypoint, mid-{@code Minecraft.<init>})
     * deliberately skips the reload: the swapped-in sources are already in place before the
     * constructor builds its real {@code PackRepository} (see {@code MinecraftPackRepositoryMixin})
     * and runs the initial resource load, so that first load picks them up with nothing to
     * invalidate. Only a later live pack switch/apply (always from a UI screen, long after {@code
     * CLIENT_STARTED}) needs the explicit reload.
     *
     * <p>Returns the resource reload's completion future (already-completed on the bootstrap path).
     * The reload is ASYNCHRONOUS: until it completes, the shader manager still resolves against the
     * previous resource snapshot, in which these sources may not exist at all. Any caller that
     * triggers a terrain pipeline recompile ({@code RendererReload.request()}) after installing a
     * pack MUST chain it on this future -- requesting it immediately compiles {@code
     * fornax_runtime:blocks/terrain} against the stale snapshot and hard-crashes the next chunk
     * draw with "Couldn't find source".
     *
     * <p>One-arg overload for existing callers that never install a vanilla-shader override --
     * delegates with an empty override map, so {@link #getNamespaces} keeps advertising only
     * {@link #NAMESPACE} and vanilla's own {@code minecraft:*} shaders are left completely alone.
     */
    public CompletableFuture<Void> reload(Map<String, String> newSources) {
        return reload(newSources, Map.of());
    }

    /**
     * As {@link #reload(Map)}, additionally publishing {@code vanillaOverrides} -- vanilla asset
     * path ({@code shaders/core/<name>.fsh}) -> source text, from {@code
     * VanillaShaderOverrides.extract} -- under the {@code minecraft} namespace (see {@link
     * #vanillaOverrides}'s own doc comment for why that resolves ahead of vanilla's real pack).
     *
     * <p>Two-arg overload for existing callers that never install a binary vanilla-asset override --
     * delegates with an empty binary override map, so {@link #getNamespaces} advertises {@code
     * minecraft} only if {@code vanillaOverrides} itself is non-empty.
     */
    public CompletableFuture<Void> reload(Map<String, String> newSources, Map<String, String> vanillaOverrides) {
        return reload(newSources, vanillaOverrides, Map.of());
    }

    /**
     * As {@link #reload(Map, Map)}, additionally publishing {@code vanillaBinaryOverrides} --
     * vanilla asset path ({@code textures/environment/<name>}) -> raw bytes, from {@code
     * VanillaAssetOverrides.extract} -- under the same {@code minecraft} namespace (see {@link
     * #vanillaBinaryOverrides}'s own doc comment).
     */
    public CompletableFuture<Void> reload(Map<String, String> newSources, Map<String, String> vanillaOverrides,
            Map<String, byte[]> vanillaBinaryOverrides) {
        this.sources = Map.copyOf(newSources);
        this.servedSources = stripAll(this.sources);
        // These are vanilla core shaders rewritten FROM THIS PACK, so they carry the pack's comment
        // density wholesale, and they are served straight to vanilla's ShaderManager under the
        // minecraft namespace -- the same preprocessor, the same regex, the same overflow risk as
        // fornax_runtime's own sources. Must be stripped here too, or this map is an untouched
        // second path to that crash.
        this.vanillaOverrides = stripAll(vanillaOverrides);
        this.vanillaBinaryOverrides = Map.copyOf(vanillaBinaryOverrides);
        // ecv2 instrumentation: ground-truth fingerprint of the terrain fragment source actually
        // installed for blaze3d to compile -- settles "is the compiler even seeing the new text"
        // without theorizing. Cheap (once per pack (re)load), keep.
        String terrainFsh = this.sources.get("shaders/blocks/terrain.fsh");
        if (terrainFsh == null) {
            dev.icehunter.fornax.FornaxMod.LOGGER.warn("[Fornax][diag] installed sources have NO shaders/blocks/terrain.fsh ({} entries)", this.sources.size());
        } else {
            dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][diag] terrain.fsh installed: {} chars, gAlbedoRawOut={}, location5={}",
                    terrainFsh.length(), terrainFsh.contains("gAlbedoRawOut"), terrainFsh.contains("location = 5"));
        }
        if (clientStarted) {
            return Minecraft.getInstance().reloadResourcePacks();
        }
        return CompletableFuture.completedFuture(null);
    }

    /** Raw composed GLSL text for {@code path} (pack-root-relative, e.g. {@code
     * "shaders/compute/voxel_probe_update.comp"}), or null if not present in the current sources --
     * the direct-string counterpart to {@link #getResource}, for callers (compute pass compilation)
     * that bypass Blaze3D's shader manager entirely and need the text itself, not an {@code
     * Identifier}-addressed {@link IoSupplier}. */
    private static Map<String, String> stripAll(Map<String, String> in) {
        Map<String, String> out = new java.util.HashMap<>(in.size());
        for (Map.Entry<String, String> e : in.entrySet()) {
            out.put(e.getKey(), GlslCommentStripper.strip(e.getValue()));
        }
        return Map.copyOf(out);
    }

    public String sourceOrNull(String path) {
        return sources.get(path);
    }

    /**
     * Read-only snapshot of the currently published {@code fornax_runtime} sources -- used by
     * {@code GraphRunner.republishVanillaOverride()} to recompute {@link #vanillaOverrides} (via
     * {@link VanillaShaderOverrides#extract}) without a full pack reload from disk, for the master
     * shaders-enabled toggle flipping back on with the active pack selection unchanged.
     */
    public Map<String, String> sourcesSnapshot() {
        return sources;
    }

    /**
     * Clears both {@link #vanillaOverrides} and {@link #vanillaBinaryOverrides}, leaving {@link
     * #sources} (this pack's own {@code fornax_runtime} shader text) untouched -- for the master
     * shaders-enabled toggle turning OFF with the active pack selection unchanged (see {@code
     * ShaderPacksScreen.applyChanges}): {@code GraphRunner} deliberately keeps the pack graph itself
     * loaded across that toggle (a fast re-enable that avoids re-reading the pack from disk), so
     * {@link #sources} must stay intact for whenever the toggle flips back on ({@code
     * GraphRunner.republishVanillaOverride()}) -- but a vanilla core-shader or binary asset override
     * (e.g. the curved lightmap, or a celestial texture) must stop being served the instant the
     * master switch goes off, or vanilla's always-active pipeline/asset lookup keeps resolving the
     * pack's override with the switch giving no way to disable it (the "invisible when off"
     * invariant this whole capability depends on -- see #getNamespaces). Both maps are cleared
     * together since they share that one invariant and the one {@code minecraft} namespace they're
     * jointly advertised under.
     */
    public CompletableFuture<Void> clearVanillaOverrides() {
        return reload(this.sources, Map.of(), Map.of());
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... segments) {
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        if (type != PackType.CLIENT_RESOURCES) {
            return null;
        }
        // "minecraft"-namespace lookups are vanilla asset overrides (see #vanillaOverrides' and
        // #vanillaBinaryOverrides' doc comments), served from completely separate maps/namespace
        // than this pack's own fornax_runtime sources. Binary is checked FIRST and returned raw (no
        // UTF-8 round-trip -- PNG bytes are not text and encoding/decoding them would corrupt the
        // image); a path absent from all three maps falls through to null (and thus to vanilla's own
        // pack) exactly as before.
        if ("minecraft".equals(id.getNamespace())) {
            byte[] overrideBytes = vanillaBinaryOverrides.get(id.getPath());
            if (overrideBytes != null) {
                return () -> new ByteArrayInputStream(overrideBytes);
            }
            String overrideText = vanillaOverrides.get(id.getPath());
            if (overrideText == null) {
                return null;
            }
            return () -> new ByteArrayInputStream(overrideText.getBytes(StandardCharsets.UTF_8));
        }
        if (!NAMESPACE.equals(id.getNamespace())) {
            return null;
        }
        String text = servedSources.get(id.getPath());
        if (text == null) {
            return null;
        }
        return () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void listResources(PackType type, String namespace, String pathStart, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES) {
            return;
        }
        if ("minecraft".equals(namespace)) {
            String prefix = pathStart.endsWith("/") ? pathStart : pathStart + "/";
            for (Map.Entry<String, String> e : vanillaOverrides.entrySet()) {
                String path = e.getKey();
                if (!path.equals(pathStart) && !path.startsWith(prefix)) continue;
                String text = e.getValue();
                output.accept(Identifier.fromNamespaceAndPath("minecraft", path),
                        () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            }
            for (Map.Entry<String, byte[]> e : vanillaBinaryOverrides.entrySet()) {
                String path = e.getKey();
                if (!path.equals(pathStart) && !path.startsWith(prefix)) continue;
                byte[] bytes = e.getValue();
                output.accept(Identifier.fromNamespaceAndPath("minecraft", path),
                        () -> new ByteArrayInputStream(bytes));
            }
            return;
        }
        if (!NAMESPACE.equals(namespace)) {
            return;
        }
        // servedSources, NOT sources -- vanilla's ShaderManager.prepare ENUMERATES shaders through
        // listResources and compiles what it finds here; it does not go through getResource. This is
        // the map that must carry the comment-stripped text for the preprocessor to see.
        String prefix = pathStart.endsWith("/") ? pathStart : pathStart + "/";
        for (Map.Entry<String, String> e : servedSources.entrySet()) {
            String path = e.getKey();
            if (!path.equals(pathStart) && !path.startsWith(prefix)) continue;
            String text = e.getValue();
            output.accept(Identifier.fromNamespaceAndPath(NAMESPACE, path),
                    () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type != PackType.CLIENT_RESOURCES) {
            return Set.of();
        }
        // Advertise "minecraft" only while an override is actually active -- MultiPackResourceManager
        // only ever consults a pack for a namespace it advertises here, so both maps empty (the
        // common case: no pack active, or the active pack ships no override) must not pull this pack
        // into the "minecraft" namespace's fallback chain at all; vanilla's own pack then resolves
        // every minecraft:* lookup exactly as it always has, with fornax entirely invisible to that
        // namespace (see lightmap-override-research.md Q1). EITHER map being non-empty is enough --
        // the text and binary overrides share the one namespace.
        return (vanillaOverrides.isEmpty() && vanillaBinaryOverrides.isEmpty())
                ? Set.of(NAMESPACE) : Set.of(NAMESPACE, "minecraft");
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionType<T> type) throws IOException {
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return location;
    }

    @Override
    public void close() {
        // No file handles or native resources -- the map is plain heap memory.
    }
}
