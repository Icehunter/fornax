#version 330

uniform sampler2D u_Source;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(u_Source, texCoord).rgb, 1.0);
}
