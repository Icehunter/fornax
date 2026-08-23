#version 330 core

// Fornax FALLBACK terrain fragment stage -- see terrain.vsh's header for why the engine keeps its
// own no-pack terrain shader. In fallback mode this compiles without USE_DEFERRED (forward path
// only); the active-pack equivalent of this file lives in the pack itself.
//
// Fornax terrain fragment stage. Two output shapes share this file, selected by USE_DEFERRED:
// opaque/cutout terrain writes the 5 G-buffer attachments the deferred resolve pass later reads
// (normal/albedo/material/ao/motion); translucent terrain computes a final lit, fogged color
// directly, the same as vanilla forward rendering.

#moj_import <fornax:globals.glsl>
#moj_import <sodium:fog.glsl>

in vec4 v_Color;
in vec2 v_TexCoord;
in vec2 v_FragDistance;
in float v_FadeFactor;

// Approximate world-space sun direction, forwarded from the vertex stage's push-constant block
// (Vulkan allows only one push_constant block per stage, so it can't be declared here directly).
in vec3 v_SunDirection;

in vec3 v_FaceNormal;

// Only read by the deferred branch below, but always forwarded -- an unused flat varying is legal
// GLSL, and keeping the vertex stage's output set identical between branches avoids interface
// mismatches between the two pipeline variants that share this fragment shader.
flat in int v_FaceNormalIndex;
in vec2 v_MotionVector;
in float v_BlockLight;

uniform sampler2D u_BlockTex;
uniform sampler2D u_NormalTex;   // LabPBR tangent-space normal map, atlas-aligned with u_BlockTex
uniform sampler2D u_MaterialTex; // LabPBR _s material map, atlas-aligned with u_BlockTex

// Reserved geometry-input slots (see GeometryInputs.RESERVED) appended to Sodium's shared terrain
// bind group -- declared here so the no-pack fallback shader compiles against the widened set-0
// layout every terrain draw shares. Never sampled by the fallback; belt-and-braces compile-compat
// only (Round A Task 1 spike).
uniform sampler2D u_GeomInput0;
uniform sampler2D u_GeomInput1;
uniform sampler2D u_GeomInput2;
uniform sampler2D u_GeomInput3;

layout(std140) uniform u_PbrSettings {
    float u_BumpStrength;
    float u_AOStrength;
};

#ifdef USE_DEFERRED
// Attachment order matches the G-buffer descriptor terrain draws are redirected into for
// SOLID/CUTOUT passes (see ShaderChunkRendererDeferredPipelineMixin/
// DefaultChunkRendererRenderPassMixin) -- normal/albedo/material/ao/motion.
layout(location = 0) out vec4 gNormalOut;
layout(location = 1) out vec4 gAlbedoOut;
layout(location = 2) out vec4 gMaterialOut;
layout(location = 3) out float gAoOut; // R8_UNORM: single channel, real per-pixel AO from the _n map's blue channel
layout(location = 4) out vec2 gMotionOut; // RG16_FLOAT: matches v_MotionVector's screen-space UV delta
#else
out vec4 fragColor;
#endif

// Re-centers an atlas UV on the nearest texel and clamps the offset by that texel's on-screen
// footprint, so a sample never crosses into a neighboring sprite's edge even under minification.
vec4 sampleTexelSnapped(sampler2D atlas, vec2 uv, vec2 texelSize, vec2 du, vec2 dv, vec2 screenTexelSize) {
    vec2 texelSpace = uv / texelSize;
    vec2 texelCenter = round(texelSpace) - 0.5;
    vec2 offsetFromCenter = texelSpace - texelCenter;

    offsetFromCenter = (offsetFromCenter - 0.5) * texelSize / screenTexelSize + 0.5;
    offsetFromCenter = clamp(offsetFromCenter, 0.0, 1.0);

    return textureGrad(atlas, (texelCenter + offsetFromCenter) * texelSize, du, dv);
}

vec4 sampleTexelSnapped(sampler2D atlas, vec2 uv, vec2 texelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    return sampleTexelSnapped(atlas, uv, texelSize, du, dv, sqrt(du * du + dv * dv));
}

// 4-tap rotated-grid supersample, cross-fading to a single texel-snapped tap as the on-screen
// texel footprint grows past about 2 screen pixels (where supersampling stops buying anything and
// a plain snapped sample is both cheaper and sharper).
vec4 sampleAtlasRGSS(sampler2D atlas, vec2 uv, vec2 texelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 screenTexelSize = sqrt(du * du + dv * dv);

    float footprint = max(screenTexelSize.x, screenTexelSize.y);
    float minTexel = min(texelSize.x, texelSize.y);
    float blend = smoothstep(minTexel, minTexel * 2.0, footprint);

    float minDerivative = min(length(du), length(dv));
    float maxDerivative = max(length(du), length(dv));
    float mipLevel = max(0.0, log2(sqrt(minDerivative * maxDerivative) / minTexel));

    const vec2 tapOffsets[4] = vec2[](
        vec2(0.125, 0.375),
        vec2(-0.125, -0.375),
        vec2(0.375, -0.125),
        vec2(-0.375, 0.125)
    );

    vec4 supersampled = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        supersampled += textureLod(atlas, uv + tapOffsets[i] * texelSize, mipLevel);
    }
    supersampled *= 0.25;

    vec4 snapped = sampleTexelSnapped(atlas, uv, texelSize, du, dv, screenTexelSize);
    return mix(snapped, supersampled, blend);
}

vec4 sampleAtlas(sampler2D atlas, vec2 uv) {
    return u_UseRGSS ? sampleAtlasRGSS(atlas, uv, u_TexelSize) : sampleTexelSnapped(atlas, uv, u_TexelSize);
}

void main() {
    vec4 texel = sampleAtlas(u_BlockTex, v_TexCoord);

#ifdef ALPHA_CUTOUT
    // Tested on the TEXTURE's own alpha, before the vertex colour is applied. A cutout texture's
    // alpha is its SHAPE, and v_Color.a is not opacity: it carries vanilla's per-face directional
    // shade multiplied by ambient occlusion (see FornaxChunkVertex on why tint and shade are
    // carried separately). Testing the product discards opaque texels for being DARK, which is how
    // leaves lost every face except the top one: vanilla shades the top at 1.0 and the underside at
    // 0.5, then AO takes it lower still, so a fully opaque leaf texel on a shaded face fell under
    // the threshold and was thrown away.
    if (texel.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    vec4 color = texel * v_Color;

    // LabPBR tangent-space normal: R/G store the X/Y perturbation, Z is reconstructed assuming a
    // unit-length, outward-facing normal. The freed blue channel carries per-pixel ambient
    // occlusion instead of a stored Z. Green follows LabPBR's DirectX-style "Y-down" convention,
    // so it is inverted here to match the OpenGL tangent-space basis built below.
    //
    // Sampled through the same texel-snapped/RGSS path as the albedo (not a plain texture() call)
    // since the normal atlas shares u_BlockTex's exact sprite layout -- an unsnapped sample would
    // bleed a neighboring sprite's normal/AO data in at tile edges.
    vec4 normalSample = sampleAtlas(u_NormalTex, v_TexCoord);

    vec2 tangentXY = vec2(normalSample.r, 1.0 - normalSample.g) * 2.0 - 1.0;
    float tangentZ = sqrt(max(1.0 - dot(tangentXY, tangentXY), 0.0));
    float bakedAo = normalSample.b;

    vec3 up = abs(v_FaceNormal.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(up, v_FaceNormal));
    vec3 bitangent = cross(v_FaceNormal, tangent);
    vec3 worldNormal = normalize(tangent * tangentXY.x + bitangent * tangentXY.y + v_FaceNormal * tangentZ);

#ifdef USE_DEFERRED
    // gNormalOut is RGBA16_SNORM (signed [-1,1] stored directly, no *0.5+0.5 remap needed); its
    // alpha channel carries the flat face index (normalized to [0,1] by /5.0) since the resolve
    // pass has no varying to read it from otherwise.
    gNormalOut = vec4(worldNormal, float(v_FaceNormalIndex) / 5.0);
    gAlbedoOut = color;

    // LabPBR _s: R=smoothness, G=F0/reflectance, B=porosity/SSS. G has a categorical cliff at
    // 230/255 (metal-ID sentinels) -- RGSS-averaging across a metal/dielectric texel boundary would
    // manufacture a garbage F0 value, so G is re-sampled with a plain texel-snap regardless of
    // u_UseRGSS to keep that byte intact; R/B stay through whichever path sampleAtlas took above.
    vec4 materialSample = sampleAtlas(u_MaterialTex, v_TexCoord);
    materialSample.g = sampleTexelSnapped(u_MaterialTex, v_TexCoord, u_TexelSize).g;

    // The alpha channel is LabPBR's emission channel, but many texture packs leave it unauthored,
    // so vanilla glow (lava, glowstone, lanterns) has to come from Minecraft's own block light
    // instead. Block light rides in gMaterialOut.a so the resolve pass can tell emitters apart
    // from ordinary lit terrain without a 6th attachment.
    gMaterialOut = vec4(materialSample.rgb, v_BlockLight);
    gAoOut = bakedAo;
    gMotionOut = v_MotionVector;
#else
    float bumpedLight = max(dot(worldNormal, normalize(v_SunDirection)), 0.0);
    float flatLight = max(dot(v_FaceNormal, normalize(v_SunDirection)), 0.0);

    // Only the bump's delta from flat-face lighting is applied, so a surface with no normal map
    // is lit identically to a flat face. Live-tunable via the PBR options page.
    float bumpFactor = 1.0 + (bumpedLight - flatLight) * u_BumpStrength;
    color.rgb *= clamp(bumpFactor, 0.4, 1.6);

    // Ambient occlusion darkens independently of sun angle, giving top/bottom faces real contrast
    // the bump term alone cannot (a face already near-maximally sunlit has no headroom left for
    // the bump term to add, since a dot product tops out at 1.0).
    color.rgb *= mix(1.0, bakedAo, u_AOStrength);

    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, v_FadeFactor);
#endif
}
