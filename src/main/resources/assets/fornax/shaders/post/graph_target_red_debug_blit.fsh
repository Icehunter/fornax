#version 330

uniform sampler2D u_Source;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float value = texture(u_Source, texCoord).r;
    fragColor = vec4(vec3(value), 1.0);
}
