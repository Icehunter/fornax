#version 330

// Engine-owned temporal reconstruct. FP32 throughout (MoltenVK-safe; no FP16 dependency).
// Reversed-Z aware: depth is only used for disocclusion via absolute-difference, no ordering assumed.
//
// At ratio 1.0 (TAA) this pass is functionally EQUIVALENT to the retired taa_blend.fsh, verified
// by line-by-line diff against it: point sample of the current frame at texCoord (no un-jitter,
// no kernel), 3x3 neighborhood clamp around texCoord, point motion fetch at texCoord, the same
// bounds + depth-similarity validity test with the same 0.05 threshold, and the same
// mix(current, clampedHistory, blendFactor) at steady state. Deliberate divergences from the old
// pass, each justified inline: the confidence ramp (only ever LOWERS the history weight below the
// old fixed factor), the clamp-event age reset (storage only -- this frame's blend is unchanged),
// and the sky motion vector (which the old pass got wrong at every ratio -- see skyMotion).
//
// This pass renders INTO sceneHistory's write slot: its output is the UNSHARPENED temporal
// accumulation (rgb) plus the accumulation age (a). Sharpening happens in the separate
// presentation pass (reconstruct_sharpen.fsh), deliberately OUTSIDE this feedback loop -- a
// sharpened output re-entering the accumulator re-applies edge enhancement to its own output
// every frame at the blend's ~0.9 recycle rate, which diverges into saturated chromatic speckle
// on high-frequency content (live-caught as red/rainbow webs on distant foliage).
uniform sampler2D u_Source;     // low-res resolved scene color (this frame), post-resolve builtin.output
uniform sampler2D u_History;    // native sceneHistory, POST-SWAP CURRENT slot = previous frame's accumulation (see ReconstructPass)
uniform sampler2D u_Motion;     // render-res gMotion (currentUV - previousUV, jitter-corrected in terrain.vsh)
uniform sampler2D u_Depth;      // render-res G-BUFFER depth (reversed-Z, terrain only -- no hand/overlays)
uniform sampler2D u_SceneDepth; // render-res MAIN TARGET depth (reversed-Z): cleared far, then vanilla wrote the first-person hand/screen overlays into it AFTER the engine depth copyback

layout(std140) uniform u_ReconstructSettings {
    vec2  u_SourceTexelSize;
    vec2  u_OutputTexelSize;
    vec2  u_JitterOffsetNdc;
    float u_BlendFactor;
    float u_Sharpen;
    float u_RatioIsOne;
    // This frame's NDC -> previous-frame NDC map for INFINITELY DISTANT content, in the upper-left
    // 3x3 (carried in a mat4 for a proven std140 upload path -- see SkyReprojection.java, which
    // derives it and documents the whole mechanism). Identity on the first frame after a pack load.
    mat4 u_SkyReprojection;
};

in vec2 texCoord;
out vec4 fragColor;

#define DISOCCLUSION_DEPTH_THRESHOLD 0.05

// First-person (responsive-pixel) mask, two conditions ANDed -- both in reversed-Z depth units
// (depth ~ near/viewZ, near plane 0.05; "nearer" = LARGER depth, the SS 12 law).
//
// 1. Delta: scene depth exceeds the terrain-only G-buffer depth by more than the epsilon (the
//    hand/held items/overlays were vanilla-drawn into the scene depth AFTER the engine's
//    copyback; elsewhere the scene depth matches terrain or is the far clear, delta <= 0).
// 2. Proximity: the scene depth itself is within the first-person volume -- nearer than 2.5m,
//    i.e. depth > 0.05/2.5 = 0.02. Without this bound the delta alone also fires on WATER
//    (live-caught: the translucent water surface writes scene depth nearer than the seafloor's
//    G-buffer depth -- the identical delta signature as the hand -- flattening all water to
//    unaccumulated current samples). Hand/held items always satisfy proximity; water beyond
//    arm's reach never does. Standing chest-deep, water pixels inside 2.5m do mask -- a tiny
//    fully-current region, visually fine, and strictly better than masking every water pixel.
#define HAND_DEPTH_EPSILON 0.005
#define FIRST_PERSON_PROXIMITY_DEPTH 0.02

// Tier-2 history-weight cap for TRANSLUCENT-OVERLAY regions -- any pixel whose scene depth beats
// the terrain-only G-buffer depth by the epsilon at ANY distance (water, glass, every forward-pass
// surface: they carry no motion/material data, so the depth delta is their detector). Their
// surface textures can be ANIMATED (scrolling water waves) while their motion vectors are the
// SEAFLOOR's static ones, so full-strength accumulation averages the animation to flat color
// (live-caught on water; the retired native-res taa_blend got away with it because its 3x3 clamp
// box spanned 3 NATIVE pixels -- under TAAU at ratio 0.67 the same 3-source-texel box spans ~4.5
// native pixels and the low-res source attenuates wave amplitude, so the clamp stops displacing
// history and the wash dominates). Capping the weight at 0.5 keeps each frame's animation at
// least half-strength while still smoothing edges. The whole tier is TAAU-ONLY: at ratio 1.0 the
// native-res clamp box already displaces history each frame (the mechanism above collapses), and
// capping there would break this pass's ratio-1.0 equivalence with the retired taa_blend, which
// blended translucents at the full factor. Age is NOT reset here -- zeroing is tier 3's
// (first-person proximity) job alone.
#define TRANSLUCENT_OVERLAY_HISTORY_CAP 0.5

// Confidence rides the history alpha as an accumulation count normalized to this cap (rgba8 alpha
// quantizes it to 256 steps -- plenty for 32 values). The cap only bounds what the count can store;
// the effective history weight saturates at u_BlendFactor well before it (count 10 at the 0.9
// default), so raising this widens storage range without changing convergence speed.
#define CONFIDENCE_FRAMES 32.0

// Reversed-Z clears depth to 0.0 = FAR, so "nothing was drawn here" is depth == 0. Same value and
// same meaning as post/framegen_sky_fill.fsh's SKY_DEPTH_EPSILON: distant terrain still writes a
// nonzero depth, only genuinely untouched sky/void pixels sit at the exact clear.
#define SKY_DEPTH_EPSILON 0.0000001

// Where an infinitely distant screen point was last frame. gMotion is a G-BUFFER attachment, so a
// pixel with no geometry carries the CLEARED zero -- "this pixel did not move" -- and the history
// fetch lands on the same screen pixel while the camera turns the sky out from under it. At the 0.9
// default blend that recycles 90% of a stale pixel every frame; the disocclusion guard cannot catch
// it either, because both of its depth taps are the same cleared far value and their difference is
// exactly 0.0. Content at effective infinity has no translational parallax, so its reprojection is
// purely rotational and computable with no depth value at all -- which is precisely why the engine
// can supply it here for EVERY pack rather than asking packs to write sky motion vectors (the cloud
// pass writes a pack target, not gMotion, so it could not write them anyway).
//
// Rotation-only, and that is the CORRECT model rather than a limitation: u_SkyReprojection is built
// from columns 0..2 of each frame's view-projection, which is what a w=0 homogeneous direction
// reads. The water motion vectors needed a separate u_CameraDelta lane for the opposite reason --
// a water surface is at a FINITE distance, so how far the eye TRAVELLED moves it on screen. Sky
// does not move under translation, and adding the camera delta here would slide it sideways with
// every footstep. View bob is excluded for free by the same argument: it lives in the projection's
// 4th column, a w-proportional clip offset that is zero at infinity.
vec2 skyMotion(vec2 uv) {
    vec3 prevH = mat3(u_SkyReprojection) * vec3(uv * 2.0 - 1.0, 1.0);
    // w <= 0 means this direction was BEHIND the previous frame's eye (only reachable on a very
    // fast flick). Return a motion that puts prevUv at -2, so main's existing bounds test rejects
    // the reprojection and the pixel renders fresh -- the honest answer for content that was
    // off-screen.
    if (prevH.z <= 0.0) {
        return uv + 2.0;
    }
    return uv - ((prevH.xy / prevH.z) * 0.5 + 0.5);
}

// Depth-dilated motion: pick the motion vector at the closest-depth texel of the 3x3 neighborhood
// (reversed-Z: closest = largest depth), which kills edge ghosting cheaply. Reports the depth it
// picked so the caller can tell "this motion vector came from real geometry" from "every texel in
// the neighborhood is sky, so this is the cleared zero".
vec2 dilatedMotion(vec2 uv, out float bestDepth) {
    vec2 bestUv = uv;
    bestDepth = -1.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 t = uv + vec2(float(x), float(y)) * u_SourceTexelSize;
            float d = texture(u_Depth, t).r;
            if (d > bestDepth) { bestDepth = d; bestUv = t; }
        }
    }
    return texture(u_Motion, bestUv).rg;
}

// Nine-tap optimized Catmull-Rom (5 bilinear fetch groups) of the low-res current frame -- TAAU
// only; the ratio-1.0 path never calls this (it point-samples at texCoord, exactly like the old
// taa_blend, because "un-jittering" a native-res frame can only bilinearly smear texel centers).
vec3 sampleCurrentCatmullRom(vec2 uv) {
    vec2 texSize = 1.0 / u_SourceTexelSize;
    vec2 samplePos = uv * texSize;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;
    vec2 texPos0  = (texPos1 - 1.0) * u_SourceTexelSize;
    vec2 texPos3  = (texPos1 + 2.0) * u_SourceTexelSize;
    vec2 texPos12 = (texPos1 + offset12) * u_SourceTexelSize;
    vec3 result = vec3(0.0);
    result += texture(u_Source, vec2(texPos0.x,  texPos0.y )).rgb * w0.x  * w0.y;
    result += texture(u_Source, vec2(texPos12.x, texPos0.y )).rgb * w12.x * w0.y;
    result += texture(u_Source, vec2(texPos3.x,  texPos0.y )).rgb * w3.x  * w0.y;
    result += texture(u_Source, vec2(texPos0.x,  texPos12.y)).rgb * w0.x  * w12.y;
    result += texture(u_Source, vec2(texPos12.x, texPos12.y)).rgb * w12.x * w12.y;
    result += texture(u_Source, vec2(texPos3.x,  texPos12.y)).rgb * w3.x  * w12.y;
    result += texture(u_Source, vec2(texPos0.x,  texPos3.y )).rgb * w0.x  * w3.y;
    result += texture(u_Source, vec2(texPos12.x, texPos3.y )).rgb * w12.x * w3.y;
    result += texture(u_Source, vec2(texPos3.x,  texPos3.y )).rgb * w3.x  * w3.y;
    return max(result, vec3(0.0));
}

void main() {
    // Current-frame color. Ratio 1.0: point sample at texCoord, byte-equivalent to the old
    // taa_blend (u_Source and the output are the same size, so texCoord lands exactly on texel
    // centers and the linear sampler degenerates to a raw fetch). TAAU: Catmull-Rom at the
    // UN-JITTERED position -- the jittered projection shifts rendered content by +jitter/2 in UV
    // (terrain.vsh subtracts the same u_JitterOffset to cancel it from motion vectors), so the
    // unjittered scene value for this output pixel lives at texCoord PLUS jitter/2, not minus.
    bool ratioIsOne = u_RatioIsOne > 0.5;
    vec2 currentUv = ratioIsOne ? texCoord : texCoord + u_JitterOffsetNdc * 0.5;
    vec3 currentColor = ratioIsOne ? texture(u_Source, texCoord).rgb : sampleCurrentCatmullRom(currentUv);

    // Neighborhood min/max of this frame's color for the history clamp, 3x3 around texCoord in
    // source texels -- same region, same space, and the same [0,1]-seeded bounds as the old
    // taa_blend (texCoord names the same surface point in the source's normalized UV space at any
    // ratio; the old pass's vec3(1.0)/vec3(0.0) seeds also bound the box to LDR range).
    vec3 nmin = vec3(1.0);
    vec3 nmax = vec3(0.0);
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec3 c = texture(u_Source, texCoord + vec2(float(x), float(y)) * u_SourceTexelSize).rgb;
            nmin = min(nmin, c);
            nmax = max(nmax, c);
        }
    }

    // Reproject history. Ratio 1.0: point motion fetch at texCoord, exactly the old taa_blend.
    // TAAU only gets the 3x3 depth-dilated fetch -- justified there because upscaling magnifies
    // edge ghosting the sub-pixel jitter can't cover, but a pure divergence at native res.
    //
    // Either way, remember the DEPTH the chosen motion vector came from: gMotion is written by
    // geometry passes alone, so a vector sourced from a texel at the far clear is not a measurement
    // of zero motion, it is the ABSENCE of a measurement. Both branches then fall back to the
    // camera-rotation-derived sky motion on exactly that condition, and only on it -- a pixel whose
    // dilated neighborhood does contain geometry keeps the dilated vector it gets today, so the
    // edge-ghosting behaviour near silhouettes is unchanged.
    float motionDepth;
    vec2 motion;
    if (ratioIsOne) {
        motionDepth = texture(u_Depth, texCoord).r;
        motion = texture(u_Motion, texCoord).rg;
    } else {
        motion = dilatedMotion(texCoord, motionDepth);
    }
    if (motionDepth <= SKY_DEPTH_EPSILON) {
        motion = skyMotion(texCoord);
    }

    vec2 prevUv = texCoord - motion;
    bool valid = prevUv.x >= 0.0 && prevUv.x <= 1.0 && prevUv.y >= 0.0 && prevUv.y <= 1.0;

    // Depth-similarity disocclusion, identical to the old taa_blend (both taps from THIS frame's
    // depth, threshold 0.05): walking-scale reprojection (~6px/frame at 4.3 blocks/s, 90fps,
    // 1080p) moves depth by ~0.0004 in reversed-Z on continuous surfaces, so this correctly
    // ACCEPTS during smooth motion -- anti-trailing there is the clamp's job, not validity's.
    //
    // On sky both taps are the far clear, so this test's difference is exactly 0.0 and it always
    // accepts. That used to hide the bug the sky motion above fixes; it is now simply CORRECT, and
    // deliberately left alone. Rejecting when both taps are cleared would throw away the sky's
    // history entirely -- the opposite of what is wanted, since the whole point is to let the sky
    // (and a pack's per-frame-dithered clouds) accumulate against a reprojection that is now right.
    // It would also mask a regression in that reprojection behind a full reset.
    if (valid) {
        float dFrag = texture(u_Depth, texCoord).r;
        float dPrev = texture(u_Depth, prevUv).r;
        if (abs(dFrag - dPrev) > DISOCCLUSION_DEPTH_THRESHOLD) valid = false;
    }

    // Temporal blend with a confidence-ramped weight. History alpha carries the frames-since-reset
    // age; the weight for THIS frame is min(age/(age+1), u_BlendFactor) -- a running 1/n average
    // while young (0 fresh, 0.5 second frame, 0.875 by the eighth), saturating at the old pass's
    // fixed factor by ~frame 10 at the 0.9 default. Steady state is therefore exactly the old
    // mix(current, clampedHistory, blendFactor); the ramp only ever lowers the weight below it.
    // Branchless validity: an invalid reprojection zeroes the age, which zeroes the weight, so the
    // mix degenerates to the pure current sample (u_History reads clamp-to-edge, so the discarded
    // out-of-range fetch is safe) -- the same fresh-sample reset the old pass's else-branch took.
    // Responsive-pixel mask (see the threshold comment block above): first-person content is
    // (nearer-than-terrain by the epsilon) AND (inside the 2.5m first-person volume). Such pixels
    // have no motion vectors or history identity, so they render fully from the current sample
    // every frame -- weight zeroed AND age reset via the same validF product, no ghost, no
    // accumulation, crisp at render-res. Water and other translucents beyond arm's reach fail the
    // proximity bound and accumulate normally, exactly like the retired taa_blend treated them.
    float sceneDepth = texture(u_SceneDepth, texCoord).r;
    float gbufferDepth = texture(u_Depth, texCoord).r;
    float translucentOverlay = step(HAND_DEPTH_EPSILON, sceneDepth - gbufferDepth);
    float firstPerson = translucentOverlay * step(FIRST_PERSON_PROXIMITY_DEPTH, sceneDepth);

    vec4 hist = texture(u_History, prevUv);
    vec3 clampedHist = clamp(hist.rgb, nmin, nmax);
    float validF = (valid ? 1.0 : 0.0) * (1.0 - firstPerson);
    float ageIn = hist.a * CONFIDENCE_FRAMES * validF;
    // Three-tier history weight: opaque surfaces ramp to the full u_BlendFactor; translucent
    // overlays cap at TRANSLUCENT_OVERLAY_HISTORY_CAP so their animation survives accumulation --
    // under TAAU only (see the cap's comment block; at ratio 1.0 this pass must match the retired
    // taa_blend, which blended translucents at the full factor); first-person pixels are already
    // at zero via validF (age reset included).
    float weightCap = mix(u_BlendFactor, TRANSLUCENT_OVERLAY_HISTORY_CAP,
            translucentOverlay * (1.0 - u_RatioIsOne));
    float weight = min(1.0 - 1.0 / (ageIn + 1.0), weightCap);
    vec3 outColor = mix(currentColor, clampedHist, weight);

    // Stored age additionally resets when the clamp MATERIALLY rewrote history (the box pulled it
    // by more than jitter-shimmer noise): the pixel's accumulated past just got invalidated by
    // content, so convergence must restart honestly instead of a stale age keeping later frames'
    // blend heavy. Storage-only on purpose -- this frame's weight above is untouched, preserving
    // old-pass equivalence at steady state (the old pass also blended the clamped value at the
    // full factor); the reset only accelerates the frames that FOLLOW a rewrite, which is what
    // makes stopping after motion sharpen in ~10 frames instead of ~30.
    float clampShift = smoothstep(0.02, 0.1, length(clampedHist - hist.rgb));
    float ageOut = min(ageIn * (1.0 - clampShift) + 1.0, CONFIDENCE_FRAMES);

    // No sharpen here, on purpose: this output IS next frame's history (and SSR's reflection
    // source). Alpha = accumulation age, private to sceneHistory -- the presentation pass writes
    // the screen's alpha back to 1.0.
    fragColor = vec4(outColor, ageOut / CONFIDENCE_FRAMES);
}
