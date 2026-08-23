package dev.icehunter.fornax.pack.material;

import dev.icehunter.fornax.pack.CategorySpec;
import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.PackModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads each category's optional tier-3 {@code glsl} snippet file off disk, pack-relative. Shared by
 * {@code PackDiscovery.loadFrom} (initial load) and {@code GraphRunner.rebuild} (live recompile) so
 * both splice the same snippet bodies into {@link MaterialInclude#generate}.
 */
public final class MaterialSnippets {
    private MaterialSnippets() {}

    public static Map<String, String> read(PackModel pack) {
        Path root = pack.root();
        Map<String, String> snippetBodies = new LinkedHashMap<>();
        for (CategorySpec c : pack.blocks().categories().values()) {
            if (c.glsl() == null) continue;
            Path snip = root.resolve(c.glsl());
            if (!Files.exists(snip)) {
                throw new FornaxPackError("blocks.toml", "categories." + c.name() + ".glsl",
                        "snippet file not found: " + c.glsl());
            }
            try {
                snippetBodies.put(c.name(), Files.readString(snip));
            } catch (IOException e) {
                throw new FornaxPackError(c.glsl(), "", "unreadable snippet: " + e.getMessage());
            }
        }
        return snippetBodies;
    }
}
