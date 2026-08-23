package dev.icehunter.fornax.atlas;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/** Exact source-file ownership attached to the decoded sprite generation that consumed it. */
public final class LabPbrAtlasProvenance {
    private static final Map<Object, Identifier> SOURCE_BY_RESOURCE = new WeakHashMap<>();
    private static final Map<Object, Identifier> SOURCE_BY_CONTENTS = new WeakHashMap<>();

    private LabPbrAtlasProvenance() {
    }

    public static void rememberSource(Resource resource, Identifier sourceFile) {
        rememberSource((Object) resource, sourceFile);
    }

    static synchronized void rememberSource(Object resource, Identifier sourceFile) {
        SOURCE_BY_RESOURCE.put(resource, sourceFile);
    }

    public static void attachContents(SpriteContents contents, Resource resource) {
        attachContents((Object) contents, resource);
    }

    /**
     * Adds a resource-backed sprite through a generation-local loader that attaches the exact
     * source owner to the decoded contents. Generated loaders never use this path and stay neutral.
     */
    public static void addExactSource(SpriteSource.Output output, Identifier spriteId,
                                      Resource resource) {
        output.add(spriteId, loader -> {
            SpriteContents contents = loader.loadSprite(spriteId, resource);
            if (contents != null) {
                attachContents(contents, resource);
            }
            return contents;
        });
    }

    /** Wraps only resource-backed additions while preserving generated loaders and removals. */
    public static SpriteSource.Output trackingOutput(SpriteSource.Output delegate) {
        return new SpriteSource.Output() {
            @Override
            public void add(Identifier spriteId, Resource resource) {
                addExactSource(delegate, spriteId, resource);
            }

            @Override
            public void add(Identifier spriteId, SpriteSource.DiscardableLoader loader) {
                delegate.add(spriteId, loader);
            }

            @Override
            public void removeAll(java.util.function.Predicate<Identifier> predicate) {
                delegate.removeAll(predicate);
            }
        };
    }

    static synchronized void attachContents(Object contents, Object resource) {
        Identifier source = SOURCE_BY_RESOURCE.get(resource);
        if (source != null) {
            SOURCE_BY_CONTENTS.put(contents, source);
        }
    }

    public static Optional<Identifier> resolve(SpriteContents contents) {
        return resolveContents(contents);
    }

    static synchronized Optional<Identifier> resolveContents(Object contents) {
        return Optional.ofNullable(SOURCE_BY_CONTENTS.get(contents));
    }

    /** Reverses DirectoryLister's configured sprite prefix to its exact enumerated texture file. */
    public static Optional<Identifier> directorySourceFile(String sourcePath, String idPrefix,
                                                            Identifier spriteId) {
        String path = spriteId.getPath();
        if (!path.startsWith(idPrefix)) {
            return Optional.empty();
        }
        Identifier sourceId = Identifier.fromNamespaceAndPath(spriteId.getNamespace(),
                path.substring(idPrefix.length()));
        return Optional.of(new FileToIdConverter(
                "textures/" + sourcePath, ".png").idToFile(sourceId));
    }

    static synchronized void clear() {
        SOURCE_BY_RESOURCE.clear();
        SOURCE_BY_CONTENTS.clear();
    }
}
