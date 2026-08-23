#version 330

// Frame generation UI-layer composite (Task 5): the captured HUD (drawn into UiLayerCapture's
// transparent-background native-res target by vanilla's own GuiRenderer) is sampled and output
// unmodified -- compositing over whatever destination this pipeline is bound to is handled entirely
// by the pipeline's own blend state (BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA: ONE /
// ONE_MINUS_SRC_ALPHA -- the captured buffer is premultiplied, since vanilla's own translucent HUD
// draws already blended straight-alpha against the transparent-cleared background), not here.
// Mirrors metalfx_reactive_mask.fsh/water_prepass_debug_blit.fsh's screenquad + single-sampler shape.
uniform sampler2D u_UiLayer;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    fragColor = texture(u_UiLayer, texCoord);
}
