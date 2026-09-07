package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.ComputeReuseSpec;
import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.pipeline.FrameUniformValues;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Admission for scalar-driven, exclusively owned image kernels and their reusable predecessors. */
final class ComputeReuseValidation {
    private ComputeReuseValidation() {}

    static void validate(GraphSpec graph, Map<String, PackOption> options) {
        for (PassSpec pass : graph.passes()) {
            ComputeReuseSpec reuse = pass.reuseWhenUnchanged();
            if (reuse == null) continue;
            if (pass.type() != PassType.COMPUTE || GraphRunner.isPreOpaqueLightingComputePass(pass)
                    || pass.runtimeEnabledIf() != null) {
                fail(pass, "reuse requires an ordinary compute pass without runtime_enabled_if");
            }
            requireUnique(pass, reuse.runtime());
            requireUnique(pass, reuse.globals());
            requireUnique(pass, reuse.push());
            for (String lane : reuse.push()) {
                if (!ComputeReuseState.supportsPush(lane)) fail(pass, "unsupported push lane '" + lane + "'");
            }
            for (String key : reuse.runtime()) {
                PackOption option = options.get(key);
                if (option == null || option.type() != OptionType.RUNTIME)
                    fail(pass, "unknown or non-runtime option '" + key + "'");
                if (!pass.inputs().contains(ComputePassRunner.PACK_OPTIONS_INPUT))
                    fail(pass, "runtime keys require the packOptions input");
            }
            for (String lane : reuse.globals()) {
                if (!FrameUniformValues.supports(lane)) fail(pass, "unsupported global lane '" + lane + "'");
                if (!pass.inputs().contains(ParticlePassRunner.GLOBALS_INPUT))
                    fail(pass, "global lanes require the globals input");
            }
            for (String output : pass.outputs()) {
                requireImage(pass, output, graph);
                for (PassSpec other : graph.passes()) {
                    if (other != pass && writes(other, output))
                        fail(pass, "output '" + output + "' has another writer '" + other.name() + "'");
                }
            }
            for (String input : pass.inputs()) {
                if (input.equals(ComputePassRunner.PACK_OPTIONS_INPUT)
                        || input.equals(ParticlePassRunner.GLOBALS_INPUT)) continue;
                requireImage(pass, input, graph);
                if (pass.outputs().contains(input)) fail(pass, "in-place input '" + input + "' is not reusable");
                PassSpec producer = null;
                for (PassSpec earlier : graph.passes()) {
                    if (earlier == pass) break;
                    if (earlier.outputs().contains(input)) producer = earlier;
                }
                if (producer == null || producer.reuseWhenUnchanged() == null)
                    fail(pass, "input '" + input + "' requires an earlier reusable compute producer");
                if (producer.enabledIf() != null && !Objects.equals(producer.enabledIf(), pass.enabledIf()))
                    fail(pass, "input '" + input + "' requires the producer's identical enabled_if");
            }
        }
    }

    private static boolean writes(PassSpec pass, String target) {
        return pass.outputs().contains(target) || target.equals(pass.target());
    }

    private static void requireUnique(PassSpec pass, List<String> keys) {
        if (new HashSet<>(keys).size() != keys.size()) fail(pass, "duplicate dependency key");
    }

    private static void requireImage(PassSpec pass, String name, GraphSpec graph) {
        TargetSpec target = graph.targets().get(name);
        if (target == null || target.kind() != TargetKind.TEXTURE || !target.storage() || target.history())
            fail(pass, "dependency '" + name + "' must be a declared storage image without history");
    }

    private static void fail(PassSpec pass, String detail) {
        throw new FornaxPackError("graph.toml", "pass." + pass.name() + ".reuse_when_unchanged", detail);
    }
}
