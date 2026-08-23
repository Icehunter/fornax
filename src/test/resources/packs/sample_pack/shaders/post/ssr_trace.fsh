#version 330

#moj_import <fornax:globals.glsl>

// Reflection quality tier -- OFF (0) skips this pack's whole SSR chain (see graph.toml's enabled_if
// on the ssr_trace_*/ssr_blur_* passes); FANCY (1) traces at full display resolution; FAST (2) traces
// at half resolution (see graph.toml's ssrRaw/ssrRawFull target split). Not branched on internally --
// this same shader text runs for both resolutions, resolution-agnostic via textureSize().
#define SSR_QUALITY 1 //[0 1 2] compile "Reflections" {0="Off" 1="Fancy" 2="Fast"}

#define u_SsrMaxDistance 64.0 //[16.0..128.0 step 4.0] runtime "SSR Max Distance"
#define u_SsrTraceQuality 48.0 //[16.0..96.0 step 4.0] runtime "SSR Trace Quality"

uniform sampler2D u_Input0; // builtin.gNormal
uniform sampler2D u_Input1; // builtin.depth
uniform sampler2D u_Input2; // builtin.gMaterial -- r = smoothness (jitter cone width)
uniform sampler2D u_Input3; // builtin.gMotion -- reproject hit -> last frame's UV
uniform sampler2D u_Input4; // sceneHistory.history -- last frame's final color, engine-written every frame under every AA/upscale method (the reflection source)
uniform sampler2D u_Input5; // hiz -- full-mip-chain pyramid view; explicit textureLod/texelFetch levels

layout(std140) uniform u_PassParams {
    vec2 u_PassTexelSize;
    float u_Param2; // Hi-Z level count (see GraphRunner.computeParams's ssr_trace special case)
    float u_Param3;
};

in vec2 texCoord;
out vec4 fragColor; // rgb = reflected color, a = hit confidence [0,1]

vec3 reconstructPosition(vec2 uv, float ndcDepth) {
    vec4 posH = u_InvProjModelView * vec4(uv * 2.0 - 1.0, ndcDepth, 1.0);
    return posH.xyz / posH.w; // camera-relative world-space (the resolve/ssao convention)
}

// Project a camera-relative position to (uv.xy, ndcZ) screen space.
vec3 projectToScreen(vec3 pos) {
    vec4 clip = u_ProjectionMatrix * u_ModelViewMatrix * vec4(pos, 1.0);
    return vec3((clip.xy / clip.w) * 0.5 + 0.5, clip.z / clip.w);
}

// Per-pixel ray-jitter rotation, replacing the old hardcoded pass's borrowed SsaoManager noise
// texture (no equivalent slot in the generic pack pipeline -- see ssao.fsh's own procedural
// replacement for the same reasoning). [0,1)^2, temporally stable per pixel (no time input).
vec2 rayJitterNoise(vec2 fragCoord) {
    float a = fract(sin(dot(fragCoord, vec2(12.9898, 78.233))) * 43758.5453);
    float b = fract(sin(dot(fragCoord, vec2(39.3468, 11.135))) * 24634.6345);
    return vec2(a, b);
}

void main() {
    float depth = texture(u_Input1, texCoord).r;
    if (depth <= 0.0) { fragColor = vec4(0.0); return; } // sky: nothing to reflect off

    vec4 materialSample = texture(u_Input2, texCoord);
    float smoothness = materialSample.r;
    // Cheap early-out: surfaces the resolve will never show reflections on skip the trace.
    if (smoothness < 0.1) { fragColor = vec4(0.0); return; }

    vec3 normal = normalize(texture(u_Input0, texCoord).rgb);
    vec3 origin = reconstructPosition(texCoord, depth);
    vec3 viewDir = normalize(origin); // camera at the origin in camera-relative space

    // Mirror direction with a SMALL roughness jitter, not a full roughness cone: a wide per-pixel
    // cone combined with the STATIC per-pixel noise tile would give adjacent pixels permanently
    // different rays -> permanently different hits -> a stable pixel-level speckle on
    // mid-smoothness stone instead of a smooth glossy reflection. Shooting a pure MIRROR ray and
    // getting the glossy spread from blurring the RESULT by roughness instead avoids that --
    // ssr_blur already does exactly that (roughness-driven radius), so the ray jitter's only
    // job here is breaking up banding, not carrying the whole cone: cap it at ~4deg (cos ~0.998)
    // and let the blur pass own the spread.
    vec3 mirror = reflect(viewDir, normal);
    vec2 xi = rayJitterNoise(gl_FragCoord.xy); // [0,1)^2, temporally stable per pixel
    float alphaR = (1.0 - smoothness) * (1.0 - smoothness);
    vec3 up = abs(mirror.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 t = normalize(cross(up, mirror));
    vec3 b = cross(mirror, t);
    float cosCone = mix(1.0, 0.998, alphaR);              // max cone half-angle ~4deg at roughest
    float cosTheta = mix(1.0, cosCone, xi.x);
    float sinTheta = sqrt(max(1.0 - cosTheta * cosTheta, 0.0));
    float phi = xi.y * 6.2831853;
    vec3 rayDir = normalize(t * (sinTheta * cos(phi)) + b * (sinTheta * sin(phi)) + mirror * cosTheta);

    // Rays toward the camera can't be resolved in screen space -- bail with zero confidence.
    if (dot(rayDir, viewDir) < -0.9) { fragColor = vec4(0.0); return; }

    // ---- Hi-Z traversal in screen space -------------------------------------------------------
    // March P = ssOrigin + s * ssDir where s parameterizes the ray to its max-distance endpoint.
    // Pyramid stores per-tile MAX depth (reversed-Z: the CLOSEST surface). A segment whose ndcZ
    // stays STRICTLY CLOSER than a tile's closest surface (rayZ > tileMax) cannot hit inside the
    // tile -> skip the whole tile and coarsen. Otherwise refine downward; a level-0 potential hit
    // is verified with a LINEAR-distance thickness check (global constraint: never raw NDC).
    // Near-plane clip BEFORE projecting the endpoint: a ray endpoint behind
    // the camera projects with negative clip-space w, and the perspective divide then flips its
    // NDC position -- corrupting ssDir (and therefore the whole march) for any ray angled back
    // toward the camera's hemisphere (e.g. floor reflections seen from above). Shorten the ray so
    // its endpoint keeps a safely positive w. w_clip is linear along the world-space ray, so the
    // shortening fraction comes from a simple linear solve.
    float rayLen = u_SsrMaxDistance;
    {
        float wOrigin = (u_ProjectionMatrix * u_ModelViewMatrix * vec4(origin, 1.0)).w;
        float wEnd = (u_ProjectionMatrix * u_ModelViewMatrix * vec4(origin + rayDir * rayLen, 1.0)).w;
        const float W_MIN = 0.1;
        if (wEnd < W_MIN) {
            float tw = (wOrigin - W_MIN) / max(wOrigin - wEnd, 1e-5);
            rayLen *= clamp(tw, 0.02, 1.0);
        }
    }
    vec3 endWorld = origin + rayDir * rayLen;
    vec3 ssOrigin = vec3(texCoord, depth);
    vec3 ssEnd = projectToScreen(endWorld);
    vec3 ssDir = ssEnd - ssOrigin;
    // Nudge off the surface so the first cell test doesn't self-hit.
    float s = 2.0 / max(abs(ssDir.x) * float(textureSize(u_Input1, 0).x),
                        abs(ssDir.y) * float(textureSize(u_Input1, 0).y)); // ~2 texels along the ray
    int level = 0;
    float hitS = -1.0;
    int levelCount = int(u_Param2);
    for (int i = 0; i < int(u_SsrTraceQuality); i++) {
        if (s >= 1.0) break;
        vec3 p = ssOrigin + ssDir * s;
        if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0 || p.z <= 0.0) break; // left screen / passed far plane
        ivec2 levelSize = textureSize(u_Input5, level);
        // min() guards the p.xy == 1.0 edge: the bounds check above admits exactly-1.0, which would
        // otherwise index one past the last texel (texelFetch OOB is undefined by spec).
        ivec2 cell = min(ivec2(p.xy * vec2(levelSize)), levelSize - 1);
        float tileClosest = texelFetch(u_Input5, cell, level).r;
        if (p.z > tileClosest) {
            // Strictly in front of everything in this tile: advance to the tile's exit boundary.
            vec2 cellMin = vec2(cell) / vec2(levelSize);
            vec2 cellMax = (vec2(cell) + 1.0) / vec2(levelSize);
            vec2 tNext;
            tNext.x = ssDir.x != 0.0 ? ((ssDir.x > 0.0 ? cellMax.x : cellMin.x) - ssOrigin.x) / ssDir.x : 1e30;
            tNext.y = ssDir.y != 0.0 ? ((ssDir.y > 0.0 ? cellMax.y : cellMin.y) - ssOrigin.y) / ssDir.y : 1e30;
            s = min(tNext.x, tNext.y) + 1e-5; // epsilon pushes into the next cell
            level = min(level + 1, levelCount - 1);
        } else {
            // Potential occupancy: refine, or verify at the finest level.
            if (level == 0) {
                float sceneDepth = texelFetch(u_Input5, cell, 0).r;
                vec3 scenePos = reconstructPosition(p.xy, sceneDepth);
                vec3 rayPos = reconstructPosition(p.xy, p.z);
                // Hit test: a hit means the ray has crossed BEHIND the surface, i.e. the ray point
                // is FARTHER from the camera than the scene surface at the same pixel, by more than
                // a self-intersection bias and less than an assumed surface thickness. All in
                // linear camera-relative blocks (standing rule: never raw NDC deltas). The
                // origin-travel guard (distance(rayPos, origin) > 0.15) rejects self-hits on the
                // ray's own starting surface, where neighboring texels sit at near-identical depth
                // and would otherwise register as an immediate false hit.
                float behind = length(rayPos) - length(scenePos); // >0: ray passed behind the surface (blocks)
                if (behind > 0.02 && behind < 1.0 && distance(rayPos, origin) > 0.15) { hitS = s; }
                if (hitS >= 0.0) break;
                // Not a valid hit (in front of surface, too thick a jump, or still at the origin):
                // step one fine texel forward and keep marching.
                s += 1.0 / max(float(max(levelSize.x, levelSize.y)), 1.0);
            } else {
                level--;
            }
        }
    }

    if (hitS < 0.0) { fragColor = vec4(0.0); return; }

    vec3 hit = ssOrigin + ssDir * hitS;
    // Reject hits on surfaces facing the same way as the ray (backface reflections).
    vec3 hitNormal = normalize(texture(u_Input0, hit.xy).rgb);
    if (dot(hitNormal, rayDir) > 0.0) { fragColor = vec4(0.0); return; }

    // Reflection color: LAST frame's finished image at the hit surface's previous screen position.
    vec2 hitMotion = texture(u_Input3, hit.xy).rg;
    vec2 historyUv = hit.xy - hitMotion;
    if (historyUv.x < 0.0 || historyUv.x > 1.0 || historyUv.y < 0.0 || historyUv.y > 1.0) {
        fragColor = vec4(0.0); return;
    }
    vec3 color = texture(u_Input4, historyUv).rgb;

    // Confidence: screen-edge fade (steep power ramp near borders hides the harder cutoff where a
    // ray leaves the visible screen) x ray-length fade.
    vec2 cdist = abs(hit.xy - 0.5) * 2.0;
    float edgeFade = clamp(1.0 - pow(max(cdist.x, cdist.y), 8.0), 0.0, 1.0);
    float lengthFade = 1.0 - clamp(hitS, 0.0, 1.0) * 0.35;
    fragColor = vec4(color, edgeFade * lengthFade);
}
