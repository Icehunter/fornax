#version 330

uniform sampler2D u_SceneDepth;
uniform sampler2D u_GBufferDepth;

in vec2 texCoord;
out float fragMask;

#define HAND_DEPTH_EPSILON 0.005
#define FIRST_PERSON_PROXIMITY_DEPTH 0.02
#define TRANSLUCENT_REACTIVE_STRENGTH 0.5

void main() {
    float sceneDepth = texture(u_SceneDepth, texCoord).r;
    float gbufferDepth = texture(u_GBufferDepth, texCoord).r;
    float translucentOverlay = step(HAND_DEPTH_EPSILON, sceneDepth - gbufferDepth);
    float firstPerson = translucentOverlay * step(FIRST_PERSON_PROXIMITY_DEPTH, sceneDepth);
    fragMask = mix(translucentOverlay * TRANSLUCENT_REACTIVE_STRENGTH, 1.0, firstPerson);
}
