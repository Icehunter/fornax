package dev.icehunter.fornax.pack;

import java.util.List;

/** Complete scalar dependencies asserted by a deterministic compute kernel's author. */
public record ComputeReuseSpec(List<String> runtime, List<String> globals, List<String> push) {
    public ComputeReuseSpec {
        runtime = List.copyOf(runtime);
        globals = List.copyOf(globals);
        push = List.copyOf(push);
    }

    public ComputeReuseSpec(List<String> runtime, List<String> globals) {
        this(runtime, globals, List.of());
    }
}
