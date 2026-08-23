package dev.icehunter.fornax.pack;

/**
 * A shaderpack failed to load. Carries the offending file, the dotted key path (may be empty when the
 * failure is file-level), and a human-readable reason, so the error screen can name file/key/reason exactly.
 */
public final class FornaxPackError extends RuntimeException {
    private final String file;
    private final String key;
    private final String reason;

    public FornaxPackError(String file, String key, String reason) {
        super("[" + file + (key.isEmpty() ? "" : " @ " + key) + "] " + reason);
        this.file = file;
        this.key = key;
        this.reason = reason;
    }

    public String file() { return file; }
    public String key() { return key; }
    public String reason() { return reason; }
}
