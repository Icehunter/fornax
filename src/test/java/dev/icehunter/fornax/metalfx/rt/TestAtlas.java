package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;

/**
 * A fully opaque block atlas for tests that trace cutout-capable geometry.
 *
 * <p>{@code rt_ray_query} alpha-tests any record carrying UVs, so a mesh fixture with no atlas
 * bound samples zero and every ray reads as passing through the geometry. That is a real failure
 * mode for a pack, not a test artifact, which is why the fixture supplies a real texture rather
 * than the kernel tolerating a missing one.
 *
 * <p>Filled by a compute dispatch because writing a texture from the host needs a
 * {@code replaceRegion} binding this bridge does not carry, and a one-pixel kernel is smaller than
 * adding one.
 */
final class TestAtlas {

    private TestAtlas() {
    }

    /** A 1x1 RGBA32F texture with alpha 1, so every UV lands on an opaque texel. */
    static long opaque(long device, long queue) {
        long texture = create(device);
        long library = MetalRtShaders.compileSource(device, "test_atlas", """
                #include <metal_stdlib>
                using namespace metal;
                kernel void fill(texture2d<float, access::write> out [[texture(0)]],
                        uint2 gid [[thread_position_in_grid]]) {
                    out.write(float4(1.0, 1.0, 1.0, 1.0), gid);
                }
                """);
        try {
            long function = Objc.msgSendId(library, Objc.selector("newFunctionWithName:"), Objc.nsString("fill"));
            long pipeline = Objc.msgSendIdIdErr(device,
                    Objc.selector("newComputePipelineStateWithFunction:error:"), function).id();
            try {
                long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long encoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
                Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), pipeline);
                Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), texture, 0L);
                Objc.dispatchThreadgroups(encoder, 1L, 1L, 1L, 1L, 1L, 1L);
                Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(cb, Objc.selector("commit"));
                Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
            } finally {
                Objc.msgSendVoid(pipeline, Objc.selector("release"));
                Objc.msgSendVoid(function, Objc.selector("release"));
            }
        } finally {
            Objc.msgSendVoid(library, Objc.selector("release"));
        }
        return texture;
    }

    private static long create(long device) {
        long desc = Objc.msgSendId(Objc.getClass("MTLTextureDescriptor"), Objc.selector("new"));
        try {
            // MTLTextureType2D=2, RGBA32Float=125, read|write=3, shared storage=0.
            Objc.msgSendVoidLong(desc, Objc.selector("setTextureType:"), 2);
            Objc.msgSendVoidLong(desc, Objc.selector("setPixelFormat:"), 125);
            Objc.msgSendVoidLong(desc, Objc.selector("setWidth:"), 1);
            Objc.msgSendVoidLong(desc, Objc.selector("setHeight:"), 1);
            Objc.msgSendVoidLong(desc, Objc.selector("setUsage:"), 3);
            Objc.msgSendVoidLong(desc, Objc.selector("setStorageMode:"), 0);
            return Objc.msgSendId(device, Objc.selector("newTextureWithDescriptor:"), desc);
        } finally {
            Objc.msgSendVoid(desc, Objc.selector("release"));
        }
    }
}
