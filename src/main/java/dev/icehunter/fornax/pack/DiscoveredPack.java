package dev.icehunter.fornax.pack;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** A pack found under {@code shaderpacks/}, either a plain folder or a zip mounted via an NIO {@link FileSystem}. */
public record DiscoveredPack(String name, Path root, boolean zip, @Nullable FileSystem fileSystem)
        implements AutoCloseable {
    @Override public void close() throws IOException {
        if (fileSystem != null) fileSystem.close();
    }
}
