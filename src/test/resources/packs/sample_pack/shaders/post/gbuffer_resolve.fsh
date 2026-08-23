#version 330

// Paired with vanilla's core/screenquad.vsh (gl_VertexID-driven full-screen triangle, no vertex
// buffer), the same technique every other fullscreen pass in this pack reuses. Matches that pairing's
// plain "#version 330" (no "core" suffix), since screenquad.vsh itself declares no "core" profile.

#moj_import <fornax:globals.glsl>
#moj_import <sodium:fog.glsl>

// This pass independently reproduces terrain.fsh's own bump/AO lighting math for the deferred path
// (a full-screen pass has no per-vertex varyings, only G-buffer textures to read back), so it needs
// its own copies of u_BumpStrength/u_AOStrength -- OptionScanner requires identical declarations to
// merge an option declared in more than one file, so these two lines are byte-for-byte the same as
// terrain.fsh's.
#define u_BumpStrength 0.5 //[0.0..1.0] runtime "Bump Strength"
#define u_AOStrength 1.0 //[0.0..1.0] runtime "AO Strength"

#define u_SsaoStrength 1.0 //[0.0..1.0] runtime "SSAO Strength"
#define u_SsrStrength 1.0 //[0.0..1.0] runtime "Reflection Strength"

// Emissive glow strength -- multiplies gMaterialOut.a (LabPBR emissive data). Reserved: see the
// composite code below for why it isn't applied to anything yet (matches the original build exactly).
#define u_EmissiveStrength 1.0 //[0.0..1.0] runtime "Emissive Strength"

// Duplicated, byte-identical to their canonical declarations (ssao.fsh / ssr_trace.fsh) -- this
// pass needs its own compile-time gate on each so it never composites from a target whose
// producing pass didn't run this session (ssao/ssr targets are always allocated, per graph.toml,
// so their content would otherwise be stale/uninitialized rather than absent).
#define SSAO_ENABLED //[] compile "Ambient Occlusion"
#define SSR_QUALITY 1 //[0 1 2] compile "Reflections" {0="Off" 1="Fancy" 2="Fast"}

uniform sampler2D u_Input0; // builtin.gNormal
uniform sampler2D u_Input1; // builtin.gAlbedo
uniform sampler2D u_Input2; // builtin.gMaterial
uniform sampler2D u_Input3; // builtin.gAo
uniform sampler2D u_Input4; // builtin.gMotion
uniform sampler2D u_Input5; // builtin.depth
uniform sampler2D u_Input6; // ssao (this frame's blended AO -- ssao_blur already ran this frame)
uniform sampler2D u_Input7; // sceneHistory.history -- engine-written last frame's final color (see the debug view below)
uniform sampler2D u_Input8; // ssr (this frame's blended reflections -- ssr_blur already ran this frame)

layout(std140) uniform u_PassParams {
    vec2 u_PassTexelSize;
    float u_Param2; // unused by this pass
    float u_Param3;  // G-buffer debug view ordinal (see GraphRunner.computeParams's resolve special case)
    vec3 u_SunDirection; // per-frame sun direction (see PassParams' own doc comment)
};

// Matches chunk_vertex.glsl's FORNAX_FACE_NORMALS exactly (Direction ordinals: DOWN=0, UP=1,
// NORTH=2, SOUTH=3, WEST=4, EAST=5) -- duplicated here rather than #moj_import-ing chunk_vertex.glsl,
// since that file also declares vertex-attribute-decoding functions/state that don't apply to a
// full-screen fragment shader. Keep this array in sync if chunk_vertex.glsl's ever changes.
const vec3 FACE_NORMALS[6] = vec3[](
    vec3(0.0, -1.0, 0.0),
    vec3(0.0,  1.0, 0.0),
    vec3(0.0,  0.0, -1.0),
    vec3(0.0,  0.0,  1.0),
    vec3(-1.0, 0.0, 0.0),
    vec3(1.0,  0.0, 0.0)
);

in vec2 texCoord;
out vec4 fragColor;

// Standard HSV-to-RGB conversion, used only by the MOTION debug view below to turn a motion
// vector's direction (hue) and magnitude (brightness) into a displayable color.
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
    int debugView = int(round(u_Param3));
    float depth = texture(u_Input5, texCoord).r;

    // This engine uses reversed-Z depth (DepthStencilState.DEFAULT is CompareOp.GREATER_THAN_OR_EQUAL;
    // RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE is 0.0, i.e. "far", not the forward-Z convention's 1.0).
    // SodiumWorldRenderer clears the G-buffer's depth attachment to that same far value at the start
    // of every frame's opaque pass, before SOLID/CUTOUT terrain draws into it -- so any pixel no
    // opaque terrain fragment touched this frame (sky, void, beyond render distance) is still
    // exactly 0.0 here. Discard those: this pass's own color attachment is bound with LOAD semantics,
    // specifically so a discarded fragment leaves whatever vanilla's sky pass already drew to the
    // main render target at this pixel untouched, instead of stomping it with meaningless default
    // G-buffer content.
    if (depth <= 0.0) {
        discard;
    }

    vec4 normalSample = texture(u_Input0, texCoord);
    vec4 albedo = texture(u_Input1, texCoord);
    vec4 materialSample = texture(u_Input2, texCoord);

    if (debugView == 1) {
        fragColor = vec4(normalSample.rgb * 0.5 + 0.5, 1.0); // gNormal is RGBA16_SNORM, stored signed [-1,1] directly; remap to [0,1] here only for display purposes
        return;
    } else if (debugView == 2) {
        fragColor = vec4(albedo.rgb, 1.0);
        return;
    } else if (debugView == 3) {
        // Real LabPBR _s data: R=smoothness, G=F0/reflectance, B=porosity/SSS.
        fragColor = vec4(materialSample.rgb, 1.0);
        return;
    } else if (debugView == 4) {
        vec2 motion = texture(u_Input4, texCoord).rg;
        float magnitude = length(motion);
        float angle = atan(motion.y, motion.x); // [-pi, pi]
        float hue = angle / (2.0 * 3.14159265) + 0.5; // [0, 1]
        // Motion deltas are tiny UV-space fractions per frame at normal camera speeds -- scale
        // magnitude up so the debug view is visually legible instead of appearing uniformly black.
        // 50.0 is a tuning constant, not a derived value; increase it if panning still reads too dim.
        float brightness = clamp(magnitude * 50.0, 0.0, 1.0);
        fragColor = vec4(hsv2rgb(vec3(hue, 1.0, brightness)), 1.0);
        return;
    } else if (debugView == 5) {
        float ssao = texture(u_Input6, texCoord).r;
        fragColor = vec4(vec3(ssao), 1.0);
        return;
    } else if (debugView == 6) {
        // Samples sceneHistory.history, the engine-guaranteed previous-frame final color -- the
        // engine's own copy hook writes THIS frame's sceneHistory only after renderLevel returns
        // (see GameRendererMixin/SceneHistory), well after this pass runs, so history still holds
        // whatever the engine wrote at the end of the PREVIOUS frame. This debug view is always
        // one frame behind what's actually displayed on screen -- fine for visually confirming the
        // history buffer is populated, not for pixel-exact comparison.
        fragColor = vec4(texture(u_Input7, texCoord).rgb, 1.0);
        return;
    } else if (debugView == 7) {
        float ao = texture(u_Input3, texCoord).r;
        fragColor = vec4(vec3(ao), 1.0);
        return;
    } else if (debugView == 8) {
        // Block-light emission signal (grayscale) -- gMaterialOut.a now carries the interpolated
        // block-light level (see terrain.fsh). Light-emitting blocks (glowstone, lava,
        // lanterns -- block-light 15) and surfaces near them read bright; daytime skylit terrain
        // (block-light 0) reads black. This is what drives the AO/SSAO exemption below.
        float blockLight = texture(u_Input2, texCoord).a;
        fragColor = vec4(vec3(blockLight), 1.0);
        return;
    } else if (debugView == 9) {
        // SSR buffer: reflected color at hit confidence; misses show black.
        vec4 ssr = texture(u_Input8, texCoord);
        fragColor = vec4(ssr.rgb * ssr.a, 1.0);
        return;
    }

    // Default: reproduce the forward shader's exact bump/AO lighting math (terrain.fsh's
    // forward/non-USE_DEFERRED branch), now sourced from G-buffer reads instead of computed inline.
    // gNormalOut was written raw (RGBA16_SNORM stores signed [-1,1] directly, no *0.5+0.5 remap --
    // see terrain.fsh), so read it back as-is here too: no *2.0-1.0 decode. The flat face
    // normal is recovered from the 0-5 index packed into gNormalOut.a.
    vec3 worldNormal = normalize(normalSample.rgb);
    int faceIndex = int(round(normalSample.a * 5.0));
    vec3 flatFaceNormal = FACE_NORMALS[faceIndex];
    float ambientOcclusion = texture(u_Input3, texCoord).r;

    // This full-screen resolve pass has no v_FragDistance varying like the forward terrain shader,
    // so the camera-relative world position is reconstructed from the depth buffer instead of
    // adding a 5th G-buffer channel: unproject this fragment's NDC position through u_InvProjModelView
    // (the same per-frame uniform the opaque/translucent terrain passes already populate this frame),
    // then feed the result into fog.glsl's own getFragDistance() below, exactly like terrain.vsh does
    // with its vertex position. This worldPos is also reused for the SSR composite's NdotV further
    // down. This engine calls glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE) unconditionally, so both
    // backends use [0,1] NDC-z with reversed-Z depth -- depth IS ndcZ directly here, no *2.0-1.0
    // remap (that remap only applies to legacy NEGATIVE_ONE_TO_ONE clip space).
    float ndcZ = depth;
    vec4 clipPos = vec4(texCoord * 2.0 - 1.0, ndcZ, 1.0);
    vec4 worldPos4 = u_InvProjModelView * clipPos;
    vec3 worldPos = worldPos4.xyz / worldPos4.w;

    float bumpNdotL = max(dot(worldNormal, normalize(u_SunDirection)), 0.0);
    float flatNdotL = max(dot(flatFaceNormal, normalize(u_SunDirection)), 0.0);
    float bumpFactor = 1.0 + (bumpNdotL - flatNdotL) * u_BumpStrength;
    float aoFactor = mix(1.0, ambientOcclusion, u_AOStrength);

    // Screen-space AO multiplies on top of the baked LabPBR AO above -- baked AO still darkens
    // crevices textures alone encode; SSAO adds real geometric contact shadowing on top, including
    // on blocks with no _n map at all.
#ifdef SSAO_ENABLED
    float ssaoValue = texture(u_Input6, texCoord).r;
    float ssaoFactor = mix(1.0, ssaoValue, u_SsaoStrength);
#else
    float ssaoFactor = 1.0;
#endif

    // Emission signal: the block-light level, written into gMaterialOut's alpha by terrain.fsh
    // (NOT LabPBR's own _s alpha channel, which many texture packs leave unauthored, so vanilla
    // glowing blocks glow via Minecraft's block-light system instead). A block-light of ~1.0 means
    // a fully-lit / light-emitting surface (glowstone, lava, lanterns all emit block-light 15);
    // ~0.0 means an unlit surface (daytime terrain is skylit, block-light 0).
    float blockLight = materialSample.a;

    // Light-EMITTING surfaces must not be darkened by ambient occlusion (baked _n AO + screen-space
    // SSAO) -- AO models occlusion of INCOMING ambient light, which a self-emitter does not depend
    // on. Without this exemption, lava (an opaque SOLID-layer block, NOT translucent) visibly
    // darkens under SSAO/AO. The protection is AUTOMATIC -- no slider.
    //
    // The exemption is THRESHOLDED to self-emitters, not proportional to block-light: scaling
    // occlusion directly by blockLight (mix(occlusion, 1.0, blockLight)) would wash AO/SSAO off
    // ENTIRE torch/lantern-lit areas uniformly -- not every lit block is an emitter, and contact
    // shadows should hold except on the emitting surface itself. An emitting block's own surface
    // sits at block-light 15 (15/16 = 0.9375 on the lightmap axis, per chunk_vertex.glsl's
    // a_LightAndData.xy/16.0 packing); even its DIRECT neighbor attenuates to 14 (0.875). smoothstep
    // between those levels passes emitters at ~full protection while leaving lit-but-not-emitting
    // surfaces properly occluded. Sub-15 emitters (torches: 14 at their own surface) get only
    // partial protection.
    float occlusion = aoFactor * ssaoFactor;
    float selfEmitter = smoothstep(0.85, 0.93, blockLight);
    occlusion = mix(occlusion, 1.0, selfEmitter);

    vec3 litColor = albedo.rgb * clamp(bumpFactor, 0.4, 1.6) * occlusion;

    // NO additive emissive glow here, deliberately: a level-15 surface already renders at full
    // lightmap brightness, so adding `albedo * selfEmit * u_EmissiveStrength` on top only pushes
    // channels past 1.0, where this LDR pipeline clamps them -- desaturating toward WHITE and
    // destroying the texture's authored color patterns (e.g. the sea lantern's animated glow). Real
    // emissive glow needs HDR headroom plus a bloom post-pass, and light CAST by emitters needs a
    // voxel/ray lighting pass; u_EmissiveStrength stays a declared option, reserved for that future
    // bloom phase.

    // --- Screen-space reflections -----------------------------------------------------------
    // LabPBR _s decode at the point of consumption: R = perceptual smoothness (roughness =
    // (1-s)^2, textbook curve); G = F0 with the categorical metal split: 0-229/255 linear
    // dielectric F0, 230-254/255 predefined metals (albedo-tinted reflection, F0 ~= 1 -- no
    // per-metal constant table needed), 255 generic metal (albedo IS F0 -- tint covers it
    // identically here).
#if SSR_QUALITY != 0
    float smoothness = materialSample.r;
    float f0g = materialSample.g;
    bool isMetal = f0g >= 229.5 / 255.0;
    float F0 = isMetal ? 1.0 : f0g;
    vec3 metalTint = isMetal ? albedo.rgb : vec3(1.0);

    // Always a plain, single sample: the Fast preset's half-res raw trace is already upsampled by
    // ssr_blur into this pass's full-res "ssr" target (see graph.toml/ssr_blur.fsh), so this pass
    // never needs to know which quality tier is active.
    vec4 ssrSample = texture(u_Input8, texCoord);

    // viewDir points camera->fragment (worldPos is camera-relative); NdotV wants surface->camera.
    float NdotV = clamp(dot(worldNormal, -normalize(worldPos)), 0.0, 1.0);
    float fresnel = F0 + (1.0 - F0) * pow(1.0 - NdotV, 5.0); // Schlick

    // Smoothness-shaped fresnel falloff: raw Schlick goes to 1.0 at grazing angles for ANY F0,
    // which would turn even rough surfaces (bark, dirt paths seen edge-on) into grazing-angle
    // mirrors. Subtract a rough-surface threshold from the fresnel curve and rescale, so
    // low-smoothness surfaces only reflect at truly extreme grazing angles while high-smoothness/
    // metal surfaces keep their full fresnel response. k = 0.7 at smoothness 0 (kills most of the
    // curve), 0 at smoothness 1 (unshaped). Metals skip the shaping entirely (F0 = 1 -> real
    // mirrors reflect at all angles).
    float k = 0.7 * (1.0 - smoothness) * (isMetal ? 0.0 : 1.0);
    float shapedFresnel = max(fresnel - k, 0.0) / (1.0 - k);

    float smoothnessFade = smoothstep(0.1, 0.35, smoothness);  // dirt stays dead; ramp matches the trace's early-out floor
    // Additional smoothness scaling of the overall weight: the k-shaping above only moves the
    // fresnel curve's takeoff point, not its ceiling, so mid-smoothness surfaces would still read
    // too bright at shallow angles. sqrt keeps high-smoothness surfaces near full weight
    // (0.8 -> ~0.89) while meaningfully damping the mid range (0.4 -> ~0.63). Metals skip it.
    float smoothnessDamp = isMetal ? 1.0 : sqrt(smoothness);
    // Weight by hit confidence: missed rays leave the surface's normal lit color rather than
    // compositing the trace's fog-color miss fallback at full fresnel weight, which would read as
    // near-black mirrors at night (night fog is near-black). Reflecting a real sky model on miss
    // is a better long-term answer than fading out.
    vec3 reflColor = ssrSample.rgb * metalTint;
    litColor = mix(litColor, reflColor,
            clamp(shapedFresnel * smoothnessFade * smoothnessDamp * ssrSample.a * u_SsrStrength, 0.0, 1.0));
#endif

    // Fog distance derived from the camera-relative worldPos reconstructed above (also used by the
    // SSR composite's NdotV) via fog.glsl's getFragDistance(), the same technique terrain.vsh uses
    // with its vertex position.
    vec2 fragDistance = getFragDistance(worldPos);

    // fadeFactor (chunk pop-in fade) has no per-fragment equivalent here -- it's a per-section,
    // build-time animation value terrain.vsh reads from u_SectionTimeInfo per-region, and
    // this full-screen pass has no region/section context to look that up with. Using 1.0 (fully
    // faded in) is a deliberate, documented approximation: it only affects the brief window a newly-
    // built chunk is popping in (chunk-section-fade-in-time option), during which that chunk won't
    // additionally fade in through fog and will instead appear immediately at full fog-adjusted
    // brightness -- a minor, transient cosmetic difference, not a correctness regression for
    // already-settled terrain.
    fragColor = _linearFog(vec4(litColor, albedo.a), fragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, 1.0);
}
