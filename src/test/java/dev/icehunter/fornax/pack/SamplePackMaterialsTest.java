package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.material.MaterialCategories;
import dev.icehunter.fornax.pack.material.MaterialInclude;
import dev.icehunter.fornax.pack.material.MaterialSnippets;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards {@code blocks.toml} loading against a snapshot of a real pack ({@code packs/sample_pack})
 * -- the same fixture
 * {@link dev.icehunter.fornax.pack.PackShaderIdResolutionTest} uses for the render graph, here also
 * carrying the pack's {@code blocks.toml} and one tier-3 snippet.
 */
class SamplePackMaterialsTest {

    @Test
    void samplePackLoadsWithTenCategories() {
        PackModel pack = PackDiscovery.loadFrom(fixtureRoot(), 1920, 1080);
        assertEquals(10, pack.blocks().categories().size());
    }

    @Test
    void copperIsTheEighthDeclaredCategory() {
        PackModel pack = PackDiscovery.loadFrom(fixtureRoot(), 1920, 1080);
        assertEquals(8, MaterialCategories.from(pack.blocks()).idOf("copper"));
    }

    @Test
    void generatedIncludeSplicesTheGemSnippetAtItsDenseId() {
        PackModel pack = PackDiscovery.loadFrom(fixtureRoot(), 1920, 1080);
        MaterialCategories cats = MaterialCategories.from(pack.blocks());
        assertEquals(6, cats.idOf("gem_block"));

        String materialsGlsl = MaterialInclude.generate(cats, MaterialSnippets.read(pack));

        assertTrue(materialsGlsl.contains("if (mid == 6u)"),
                "gem_block's dense id (6) is not guarded in the generated hook");
        assertTrue(materialsGlsl.contains("smoothness = max(smoothness, 0.85);"),
                "gem.glsl snippet body missing from the generated include");
        assertTrue(materialsGlsl.contains("f0 = max(f0, 0.17);"),
                "gem.glsl snippet body missing from the generated include");
    }

    private static Path fixtureRoot() {
        var url = SamplePackMaterialsTest.class.getResource("/packs/sample_pack");
        assertNotNull(url, "missing test fixture: packs/sample_pack");
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
