package dev.icehunter.fornax.pack;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.graph.GraphValidator;
import dev.icehunter.fornax.pack.graph.VramReport;
import dev.icehunter.fornax.pack.material.MaterialCategories;
import dev.icehunter.fornax.pack.material.MaterialInclude;
import dev.icehunter.fornax.pack.option.OptionScanner;
import dev.icehunter.fornax.pack.option.PackOption;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Discovers and loads shaderpacks from the game dir's {@code shaderpacks/} directory. */
public final class PackDiscovery {
    /** The only pack format version this Fornax build understands. */
    public static final int SUPPORTED_FORMAT = 1;

    private PackDiscovery() {}

    public static Path shaderpacksDir() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            FornaxMod.LOGGER.error("[Fornax] Could not create shaderpacks dir {}", dir, e);
        }
        return dir;
    }

    public static List<DiscoveredPack> discover() {
        return discoverIn(shaderpacksDir());
    }

    /** Directory-based scan (test entry point; no Fabric bootstrap required). */
    static List<DiscoveredPack> discoverIn(Path dir) {
        List<DiscoveredPack> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (Files.isDirectory(entry)) {
                    if (Files.exists(entry.resolve("pack.toml"))) {
                        found.add(new DiscoveredPack(entry.getFileName().toString(), entry, false, null));
                    }
                } else if (entry.getFileName().toString().toLowerCase().endsWith(".zip")) {
                    openZip(entry).ifPresent(found::add);
                }
            }
        } catch (IOException e) {
            FornaxMod.LOGGER.error("[Fornax] Failed to list {}", dir, e);
        }
        return found;
    }

    private static Optional<DiscoveredPack> openZip(Path zip) {
        try {
            FileSystem fs = FileSystems.newFileSystem(zip, (ClassLoader) null);
            Path root = fs.getPath("/");
            if (!Files.exists(root.resolve("pack.toml"))) {
                fs.close();
                return Optional.empty();
            }
            String name = zip.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
            return Optional.of(new DiscoveredPack(name, root, true, fs));
        } catch (IOException e) {
            FornaxMod.LOGGER.error("[Fornax] Failed to open zip pack {}", zip, e);
            return Optional.empty();
        }
    }

    public static PackModel load(DiscoveredPack pack, int renderWidth, int renderHeight) {
        return loadFrom(pack.root(), renderWidth, renderHeight);
    }

    /**
     * Re-reads a loaded pack's raw shader sources from its root (disk directory or mounted zip
     * {@link java.nio.file.FileSystem}) for {@code GraphRunner.rebuild}, which needs them fresh on
     * every compile-option change -- {@link PackModel} itself retains only the scanned option table,
     * not the GLSL text. The pack settings UI is the only caller today.
     */
    public static Map<String, String> loadShaderSources(Path packRoot) {
        return readShaderSources(packRoot.resolve("shaders"));
    }

    /**
     * Reads a pack's binary vanilla-asset overrides (e.g. {@code textures/vanilla/celestial/sun.png})
     * for {@link dev.icehunter.fornax.pack.layout.VanillaAssetOverrides#extract} -- the binary
     * counterpart to {@link #loadShaderSources}. Absent {@code textures/} directory is not an error
     * (most packs ship none): returns an empty map rather than throwing.
     */
    public static Map<String, byte[]> readTextureOverrides(Path packRoot) {
        return readBinaryAssets(packRoot.resolve("textures"));
    }

    /** Root-based loader (test entry point; no Fabric bootstrap required). */
    static PackModel loadFrom(Path root, int renderWidth, int renderHeight) {
        PackMeta meta = read(root.resolve("pack.toml"), "pack.toml", PackTomlLoader::loadMeta);
        if (meta.format() != SUPPORTED_FORMAT) {
            throw new FornaxPackError("pack.toml", "format",
                    "unsupported pack format " + meta.format() + " (this build supports " + SUPPORTED_FORMAT + ")");
        }
        GraphSpec graph = read(root.resolve("graph.toml"), "graph.toml", PackTomlLoader::loadGraph);
        validateTextureAssets(root, graph);
        ScreensSpec screens = read(root.resolve("screens.toml"), "screens.toml", PackTomlLoader::loadScreens);

        Path blocksPath = root.resolve("blocks.toml");
        BlocksSpec blocks = Files.exists(blocksPath)
                ? read(blocksPath, "blocks.toml", PackTomlLoader::loadBlocks)
                : BlocksSpec.empty();

        Map<String, String> shaderSources = readShaderSources(root.resolve("shaders"));

        MaterialCategories cats = MaterialCategories.from(blocks);
        Map<String, String> snippetBodies = new LinkedHashMap<>();
        for (CategorySpec c : blocks.categories().values()) {
            if (c.glsl() == null) continue;
            Path snip = root.resolve(c.glsl());
            if (!Files.exists(snip)) {
                throw new FornaxPackError("blocks.toml", "categories." + c.name() + ".glsl",
                        "snippet file not found: " + c.glsl());
            }
            try { snippetBodies.put(c.name(), Files.readString(snip)); }
            catch (IOException e) { throw new FornaxPackError(c.glsl(), "", "unreadable snippet: " + e.getMessage()); }
        }
        shaderSources.put(MaterialInclude.PATH, MaterialInclude.generate(cats, snippetBodies));

        // Fail loud at load time on an unresolvable #moj_import -- blaze3d silently splices an error
        // string into the composed GLSL instead (see ShaderImports), which only surfaces as a broken
        // pipeline compile mid-frame.
        ShaderImports.validate(shaderSources);
        PaletteStrideContract.validate(shaderSources);
        Map<String, PackOption> options = OptionScanner.scan(shaderSources);

        VramReport report = GraphValidator.validate(graph, options, renderWidth, renderHeight);
        FornaxMod.LOGGER.info("[Fornax] Pack '{}' targets total ~{} MB VRAM at {}x{}",
                meta.name(), String.format("%.1f", report.totalBytes() / (1024.0 * 1024.0)), renderWidth, renderHeight);
        for (String line : report.lines()) FornaxMod.LOGGER.info("[Fornax]   {}", line);

        ProfileValidator.warnUnknownProfileKeys(screens, options);
        MetaValidator.validate(screens, options);

        return new PackModel(root, meta, graph, screens, options, blocks);
    }

    /**
     * Eagerly proves every {@code [textures.*]} declaration in {@code graph.toml} names a real,
     * decodable image file, at pack-load time -- the same "fail loud, never a silent black" law
     * {@code blocks.toml}'s {@code categories.*.glsl} snippet check and the {@code #moj_import}
     * validation above already follow. This is a pure decode-and-discard probe (no GPU device
     * needed, unlike the real upload): {@code PackTextureRegistry} re-reads and re-decodes the same
     * file at GPU-upload time (mirroring {@code readShaderSources}' own "re-read from disk on every
     * rebuild" shape), so a corrupt file surfaces here as a clear {@link FornaxPackError} naming the
     * declaration, never as a deferred failure discovered mid-frame.
     */
    private static void validateTextureAssets(Path root, GraphSpec graph) {
        for (PackTextureSpec tex : graph.textures().values()) {
            Path file = root.resolve(tex.file());
            if (!Files.exists(file)) {
                throw new FornaxPackError("graph.toml", "textures." + tex.name() + ".file",
                        "declared texture file not found: " + tex.file());
            }
            try (InputStream in = Files.newInputStream(file);
                 NativeImage probe = NativeImage.read(NativeImage.Format.RGBA, in)) {
                // Decoded successfully -- discarded; PackTextureRegistry re-decodes at GPU-upload time.
            } catch (IOException e) {
                throw new FornaxPackError("graph.toml", "textures." + tex.name() + ".file",
                        "failed to decode texture '" + tex.file() + "': " + e.getMessage());
            }
        }
    }

    private static Map<String, String> readShaderSources(Path shadersDir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(shadersDir)) return out;
        try (Stream<Path> walk = Files.walk(shadersDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        // .comp (compute shaders) is included alongside the raster stages so a
                        // compute pass's shader (PassSpec.shader(), e.g. "shaders/compute/x.comp")
                        // actually lands in this map -- ComputePassRunner.build() resolves it the
                        // same way a fullscreen/geometry pass resolves .fsh/.vsh, via
                        // RuntimeShaderPack.sourceOrNull(spec.shader()). Without it, no compute
                        // pass's shader could ever resolve: ComputePassRunner.build() would always
                        // throw "no composed source" for a real pack.
                        return n.endsWith(".fsh") || n.endsWith(".vsh") || n.endsWith(".glsl") || n.endsWith(".comp");
                    })
                    .sorted()
                    .forEach(p -> {
                        // Keyed pack-root-relative ("shaders/post/ssao.fsh"), NOT shaders-dir-relative:
                        // these keys are served verbatim as fornax_runtime resource paths by
                        // RuntimeShaderPack, and blaze3d's ShaderType.idConverter() is
                        // FileToIdConverter("shaders", ".vsh"/".fsh") -- a pipeline asking for
                        // fornax_runtime:blocks/terrain looks up the resource path
                        // "shaders/blocks/terrain.vsh" (javap-confirmed against the real 26.2 client
                        // jar). Dropping the prefix here made every pack shader unresolvable: terrain
                        // pipelines failed to compile and the world rendered black (live-caught).
                        // Matches PassSpec.shader()'s own "shaders/..." path convention exactly.
                        try { out.put("shaders/" + shadersDir.relativize(p).toString().replace('\\', '/'), Files.readString(p)); }
                        catch (IOException e) { throw new FornaxPackError(p.toString(), "", "unreadable shader: " + e.getMessage()); }
                    });
        } catch (IOException e) {
            throw new FornaxPackError(shadersDir.toString(), "", "failed to walk shaders/: " + e.getMessage());
        }
        return out;
    }

    private static Map<String, byte[]> readBinaryAssets(Path assetsDir) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (!Files.exists(assetsDir)) return out;
        try (Stream<Path> walk = Files.walk(assetsDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted()
                    .forEach(p -> {
                        // Keyed pack-root-relative ("textures/vanilla/celestial/sun.png"), mirroring
                        // readShaderSources' own "shaders/..." convention.
                        String key = assetsDir.getFileName() + "/"
                                + assetsDir.relativize(p).toString().replace('\\', '/');
                        try { out.put(key, Files.readAllBytes(p)); }
                        catch (IOException e) { throw new FornaxPackError(p.toString(), "", "unreadable texture: " + e.getMessage()); }
                    });
        } catch (IOException e) {
            throw new FornaxPackError(assetsDir.toString(), "", "failed to walk " + assetsDir.getFileName() + "/: " + e.getMessage());
        }
        return out;
    }

    private interface TomlReader<T> { T read(Reader r, String file); }

    private static <T> T read(Path path, String file, TomlReader<T> reader) {
        if (!Files.exists(path)) throw new FornaxPackError(file, "", "missing required manifest " + file);
        try (Reader r = Files.newBufferedReader(path)) {
            return reader.read(r, file);
        } catch (IOException e) {
            throw new FornaxPackError(file, "", "failed to read " + file + ": " + e.getMessage());
        }
    }
}
