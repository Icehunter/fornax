#version 330

// Declares the granular options the [metas.*] / [screens.quality] pages assign, where no other
// shader in this pack already declares them (SSAO_ENABLED/SSR_QUALITY/u_SsrTraceQuality already live in ssao.fsh/
// gbuffer_resolve.fsh/ssr_trace.fsh -- reused as-is, not redeclared here, since OptionScanner
// requires byte-identical redeclarations and this file has no other reason to touch them). Never
// referenced by any pass, so it has no effect on graph behavior -- OptionScanner walks every
// .fsh/.vsh/.glsl/.comp under shaders/ regardless of whether a pass references it (see
// PackDiscovery.readShaderSources), purely to build the merged option table SamplePackScreensParseTest
// and MetaValidator need.

#define SSR_SURFACE_MODE 2 //[0 1 2] compile "Surface Reflections" {0="Off" 1="Highlights" 2="Full"}
#define SSR_WATER_MODE 3 //[0 1 2 3] compile "Water Reflections" {0="Off" 1="Highlights" 2="Traced" 3="High"}

#define SSAO_TAPS 16 //[4 8 16] compile "AO Samples" {4="Fast" 8="Balanced" 16="Rich"}

void main() {}
