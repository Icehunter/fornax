package dev.icehunter.fornax.pack;
import java.util.List;
public record PackMeta(String name, String version, List<String> authors, String license, int format) {}
