package dev.icehunter.fornax.metalfx.objc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal {@code java.lang.foreign} (FFM) bridge to the Objective-C runtime plus the Metal and
 * MetalFX frameworks — the pure-Java alternative to a handwritten JNI dylib (MetalFX spike M0; see
 * the approved plan's Q2 rationale: no native toolchain, no macOS-only artifact to codesign, one
 * {@code os.name}/{@code os.arch} guard instead of a library-loading path).
 *
 * <p>LOAD GUARDING IS THE CONTRACT OF THIS CLASS: every symbol lookup and downcall handle lives in
 * the static initializer, and the initializer links NOTHING unless {@link #PLATFORM_SUPPORTED}
 * (macOS + aarch64). Callers must consult {@link #isLoaded()} before any other accessor; the
 * accessors throw {@link IllegalStateException} rather than NPE if misused. Off-platform, this
 * class is safe to <em>initialize</em> (the guard short-circuits before any restricted FFM call)
 * but callers should still prefer checking the platform themselves first so the class never even
 * loads on Windows/Linux — {@code MetalFxSupport} does exactly that.
 *
 * <p>ARM64-ONLY CALLING CONVENTION: on Apple silicon there is a single {@code objc_msgSend} entry
 * point for every message shape, with no {@code _fpret}/{@code _stret} variants. The AArch64 ABI
 * returns a small struct in registers and a large one through a hidden pointer argument in x8; a
 * struct argument larger than 16 bytes (such as the {@code MTLSize} pair {@link
 * #dispatchThreadgroups} sends) is passed by reference to a caller-owned copy instead of in
 * registers. FFM's {@link Linker} models all of this from the {@link FunctionDescriptor} alone.
 * Each distinctly-shaped message this bridge sends gets its own downcall handle over the same
 * symbol; add new shapes as later milestones need them rather than genericizing early.
 *
 * <p>RESTRICTED NATIVE ACCESS: {@code SymbolLookup.libraryLookup} and {@code Linker.downcallHandle}
 * are JEP 472 restricted methods — on JDK 24+ they emit a one-time warning without
 * {@code --enable-native-access} and are planned to hard-fail in a future JDK. The M3 milestone
 * adds the launch flag; until then the warning is expected and harmless (same situation the test
 * task already opts out of via its own {@code --enable-native-access=ALL-UNNAMED}).
 */
public final class Objc {
    /** {@code struct MTLSize { NSUInteger width, height, depth; }}. Three 8-byte words, no
     * padding, matching the Metal header on this machine. Passed by value in shapes like
     * {@code dispatchThreadgroups:threadsPerThreadgroup:}. */
    private static final StructLayout MTL_SIZE = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("width"),
            ValueLayout.JAVA_LONG.withName("height"),
            ValueLayout.JAVA_LONG.withName("depth"));

    /** {@code struct MTLRegion { MTLOrigin origin; MTLSize size; }} (MTLTypes.h on this machine):
     * an {@code MTLOrigin} (x, y, z) followed by an {@code MTLSize} (width, height, depth), six
     * 8-byte NSUInteger words, no padding. Passed by value to {@code
     * getBytes:bytesPerRow:fromRegion:mipmapLevel:}. */
    private static final StructLayout MTL_REGION = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("originX"),
            ValueLayout.JAVA_LONG.withName("originY"),
            ValueLayout.JAVA_LONG.withName("originZ"),
            ValueLayout.JAVA_LONG.withName("width"),
            ValueLayout.JAVA_LONG.withName("height"),
            ValueLayout.JAVA_LONG.withName("depth"));

    /** {@code struct MTLAccelerationStructureSizes { NSUInteger accelerationStructureSize,
     * buildScratchBufferSize, refitScratchBufferSize; }} (MTLDevice.h on this machine): three
     * 8-byte NSUInteger words, no padding. Returned BY VALUE from {@code
     * accelerationStructureSizesWithDescriptor:}. Unlike {@link #MTL_SIZE}, which is only ever
     * passed as an argument, a struct-typed RETURN layout makes {@link Linker} add a leading
     * {@link java.lang.foreign.SegmentAllocator} parameter to the downcall handle, which supplies
     * the memory the returned struct is copied into (whether the ABI passed it in registers or
     * through the hidden x8 pointer is exactly what {@link FunctionDescriptor}'s struct return
     * layout lets the linker decide; this bridge never has to know which). */
    private static final StructLayout MTL_ACCELERATION_STRUCTURE_SIZES = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("accelerationStructureSize"),
            ValueLayout.JAVA_LONG.withName("buildScratchBufferSize"),
            ValueLayout.JAVA_LONG.withName("refitScratchBufferSize"));

    // Selectors are a fixed set the ObjC runtime interns forever, so a resolved SEL is valid for
    // the rest of the process; caching it here skips sel_registerName's own lookup on every call
    // this bridge already makes several times a frame. Declared before the static block below so
    // the field is never null while that block runs, even if a future change calls selector()
    // from inside it.
    private static final ConcurrentHashMap<String, Long> SELECTOR_CACHE = new ConcurrentHashMap<>();

    /** True only on macOS/aarch64 — the sole configuration whose static init links anything. */
    public static final boolean PLATFORM_SUPPORTED;

    private static final boolean LOADED;
    private static final String LOAD_FAILURE; // human-readable reason when PLATFORM_SUPPORTED but !LOADED

    private static final MethodHandle OBJC_GET_CLASS;
    private static final MethodHandle SEL_REGISTER_NAME;
    private static final MethodHandle MSG_SEND_ID;          // id objc_msgSend(id, SEL)
    private static final MethodHandle MSG_SEND_ID_ID;       // id objc_msgSend(id, SEL, id)
    private static final MethodHandle MSG_SEND_ID_ID_ID;    // id objc_msgSend(id, SEL, id, id)
    private static final MethodHandle MSG_SEND_BOOL_ID;     // BOOL objc_msgSend(id, SEL, id)
    private static final MethodHandle MSG_SEND_BOOL;        // BOOL objc_msgSend(id, SEL)
    private static final MethodHandle MSG_SEND_VOID;        // void objc_msgSend(id, SEL)
    private static final MethodHandle MSG_SEND_VOID_ID;     // void objc_msgSend(id, SEL, id)
    private static final MethodHandle MSG_SEND_VOID_ID_ID;  // void objc_msgSend(id, SEL, id, id)
    private static final MethodHandle MSG_SEND_VOID_LONG;   // void objc_msgSend(id, SEL, NSUInteger)
    private static final MethodHandle MSG_SEND_VOID_FLOAT;  // void objc_msgSend(id, SEL, float)
    private static final MethodHandle MSG_SEND_VOID_BOOL;   // void objc_msgSend(id, SEL, BOOL)
    private static final MethodHandle MSG_SEND_VOID_ID_LONG; // void objc_msgSend(id, SEL, id, uint64)
    private static final MethodHandle MSG_SEND_LONG;        // NSUInteger objc_msgSend(id, SEL)
    private static final MethodHandle MSG_SEND_DOUBLE;      // CFTimeInterval objc_msgSend(id, SEL)
    private static final MethodHandle MSG_SEND_ID_LONG_LONG; // id objc_msgSend(id, SEL, NSUInteger, NSUInteger)
    private static final MethodHandle MSG_SEND_VOID_ID_LONG_LONG; // void objc_msgSend(id, SEL, id, NSUInteger, NSUInteger)
    private static final MethodHandle MSG_SEND_ID_ID_PTR;    // id objc_msgSend(id, SEL, id, ptr)
    private static final MethodHandle MSG_SEND_ID_ID_ID_PTR; // id objc_msgSend(id, SEL, id, id, ptr)
    private static final MethodHandle MSG_SEND_VOID_SIZE_SIZE; // void objc_msgSend(id, SEL, MTLSize, MTLSize)
    private static final MethodHandle MSG_SEND_ID_PTR_LONG;   // id objc_msgSend(id, SEL, ptr, NSUInteger)
    private static final MethodHandle MSG_SEND_VOID_ID_ID_ID_LONG; // void objc_msgSend(id, SEL, id, id, id, NSUInteger)
    private static final MethodHandle MSG_SEND_VOID_PTR_LONG_REGION_LONG; // void objc_msgSend(id, SEL, ptr, NSUInteger, MTLRegion, NSUInteger)
    private static final MethodHandle MSG_SEND_ACCEL_SIZES;   // MTLAccelerationStructureSizes objc_msgSend(id, SEL, id)
    private static final MethodHandle AUTORELEASE_POOL_PUSH; // void* objc_autoreleasePoolPush()
    private static final MethodHandle AUTORELEASE_POOL_POP;  // void objc_autoreleasePoolPop(void*)
    private static final MethodHandle MTL_CREATE_SYSTEM_DEFAULT_DEVICE;

    // One process-lifetime arena: the looked-up libraries and every downcall stub live as long as
    // the JVM, exactly like a System.loadLibrary would. Nothing here is ever unloaded.
    private static final Arena ARENA;

    static {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        PLATFORM_SUPPORTED = os.contains("mac") && arch.equals("aarch64");

        boolean loaded = false;
        String failure = null;
        Arena arena = null;
        MethodHandle objcGetClass = null;
        MethodHandle selRegisterName = null;
        MethodHandle msgSendId = null;
        MethodHandle msgSendIdId = null;
        MethodHandle msgSendIdIdId = null;
        MethodHandle msgSendBoolId = null;
        MethodHandle msgSendBool = null;
        MethodHandle msgSendVoid = null;
        MethodHandle msgSendVoidId = null;
        MethodHandle msgSendVoidIdId = null;
        MethodHandle msgSendVoidLong = null;
        MethodHandle msgSendVoidFloat = null;
        MethodHandle msgSendVoidBool = null;
        MethodHandle msgSendVoidIdLong = null;
        MethodHandle msgSendLong = null;
        MethodHandle msgSendDouble = null;
        MethodHandle msgSendIdLongLong = null;
        MethodHandle msgSendVoidIdLongLong = null;
        MethodHandle msgSendIdIdPtr = null;
        MethodHandle msgSendIdIdIdPtr = null;
        MethodHandle msgSendVoidSizeSize = null;
        MethodHandle msgSendIdPtrLong = null;
        MethodHandle msgSendVoidIdIdIdLong = null;
        MethodHandle msgSendVoidPtrLongRegionLong = null;
        MethodHandle msgSendAccelSizes = null;
        MethodHandle poolPush = null;
        MethodHandle poolPop = null;
        MethodHandle mtlCreateDevice = null;

        if (PLATFORM_SUPPORTED) {
            try {
                arena = Arena.ofShared();
                Linker linker = Linker.nativeLinker();
                SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena);
                // Loading a framework's binary through libraryLookup both registers its ObjC
                // classes with the runtime (so objc_getClass finds them) and exposes its C symbols
                // (MTLCreateSystemDefaultDevice). MetalFX.framework may be absent on very old
                // macOS; treat that as a clean "not loaded", not a crash.
                SymbolLookup metal = SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/Metal.framework/Metal", arena);
                SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/MetalFX.framework/MetalFX", arena);
                // CAMetalLayer (MetalHudControl's sole target) lives here. AppKit/GLFW load this
                // framework themselves before any window exists, so objc_getClass("CAMetalLayer")
                // has always resolved in practice -- but that made it an unstated dependency on
                // load order outside this class's control. Loading it explicitly, like Metal and
                // MetalFX above, makes class registration this class's own guarantee instead.
                SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/QuartzCore.framework/QuartzCore", arena);

                objcGetClass = linker.downcallHandle(
                        find(objc, "objc_getClass"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                selRegisterName = linker.downcallHandle(
                        find(objc, "sel_registerName"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                MemorySegment msgSendSym = find(objc, "objc_msgSend");
                msgSendId = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                msgSendIdId = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                msgSendIdIdId = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
                msgSendBoolId = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                msgSendBool = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                msgSendVoid = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                msgSendVoidId = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
                msgSendVoidIdId = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                msgSendVoidLong = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG));
                msgSendVoidFloat = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_FLOAT));
                msgSendVoidBool = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_BOOLEAN));
                msgSendVoidIdLong = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                msgSendLong = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                msgSendDouble = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                msgSendIdLongLong = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
                msgSendVoidIdLongLong = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
                msgSendIdIdPtr = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
                msgSendIdIdIdPtr = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                msgSendVoidSizeSize = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                MTL_SIZE, MTL_SIZE));
                msgSendIdPtrLong = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG));
                msgSendVoidIdIdIdLong = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG));
                msgSendVoidPtrLongRegionLong = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, MTL_REGION,
                                ValueLayout.JAVA_LONG));
                msgSendAccelSizes = linker.downcallHandle(msgSendSym,
                        FunctionDescriptor.of(MTL_ACCELERATION_STRUCTURE_SIZES,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                poolPush = linker.downcallHandle(
                        find(objc, "objc_autoreleasePoolPush"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
                poolPop = linker.downcallHandle(
                        find(objc, "objc_autoreleasePoolPop"),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                mtlCreateDevice = linker.downcallHandle(
                        find(metal, "MTLCreateSystemDefaultDevice"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
                loaded = true;
            } catch (Throwable t) {
                // IllegalArgumentException (missing library), NoSuchElementException (missing
                // symbol), IllegalCallerException (native access denied by a future JDK), ...
                failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }

        ARENA = arena;
        LOADED = loaded;
        LOAD_FAILURE = failure;
        OBJC_GET_CLASS = objcGetClass;
        SEL_REGISTER_NAME = selRegisterName;
        MSG_SEND_ID = msgSendId;
        MSG_SEND_ID_ID = msgSendIdId;
        MSG_SEND_ID_ID_ID = msgSendIdIdId;
        MSG_SEND_BOOL_ID = msgSendBoolId;
        MSG_SEND_BOOL = msgSendBool;
        MSG_SEND_VOID = msgSendVoid;
        MSG_SEND_VOID_ID = msgSendVoidId;
        MSG_SEND_VOID_ID_ID = msgSendVoidIdId;
        MSG_SEND_VOID_LONG = msgSendVoidLong;
        MSG_SEND_VOID_FLOAT = msgSendVoidFloat;
        MSG_SEND_VOID_BOOL = msgSendVoidBool;
        MSG_SEND_VOID_ID_LONG = msgSendVoidIdLong;
        MSG_SEND_LONG = msgSendLong;
        MSG_SEND_DOUBLE = msgSendDouble;
        MSG_SEND_ID_LONG_LONG = msgSendIdLongLong;
        MSG_SEND_VOID_ID_LONG_LONG = msgSendVoidIdLongLong;
        MSG_SEND_ID_ID_PTR = msgSendIdIdPtr;
        MSG_SEND_ID_ID_ID_PTR = msgSendIdIdIdPtr;
        MSG_SEND_VOID_SIZE_SIZE = msgSendVoidSizeSize;
        MSG_SEND_ID_PTR_LONG = msgSendIdPtrLong;
        MSG_SEND_VOID_ID_ID_ID_LONG = msgSendVoidIdIdIdLong;
        MSG_SEND_VOID_PTR_LONG_REGION_LONG = msgSendVoidPtrLongRegionLong;
        MSG_SEND_ACCEL_SIZES = msgSendAccelSizes;
        AUTORELEASE_POOL_PUSH = poolPush;
        AUTORELEASE_POOL_POP = poolPop;
        MTL_CREATE_SYSTEM_DEFAULT_DEVICE = mtlCreateDevice;
    }

    private Objc() {}

    private static MemorySegment find(SymbolLookup lookup, String name) {
        Optional<MemorySegment> sym = lookup.find(name);
        if (sym.isEmpty()) {
            throw new IllegalStateException("symbol not found: " + name);
        }
        return sym.get();
    }

    /** Whether the bridge linked successfully (implies {@link #PLATFORM_SUPPORTED}). */
    public static boolean isLoaded() {
        return LOADED;
    }

    /** Human-readable link-failure reason, or null (only meaningful when supported-but-unloaded). */
    public static String loadFailure() {
        return LOAD_FAILURE;
    }

    private static void requireLoaded() {
        if (!LOADED) {
            throw new IllegalStateException("Objc bridge not loaded"
                    + (LOAD_FAILURE != null ? " (" + LOAD_FAILURE + ")" : ""));
        }
    }

    /** {@code objc_getClass(name)} — 0 (nil) when the class doesn't exist on this OS build. */
    public static long getClass(String name) {
        requireLoaded();
        try (Arena local = Arena.ofConfined()) {
            MemorySegment cName = local.allocateFrom(name);
            return ((MemorySegment) OBJC_GET_CLASS.invokeExact(cName)).address();
        } catch (Throwable t) {
            throw new RuntimeException("objc_getClass(" + name + ")", t);
        }
    }

    /** {@code sel_registerName(name)}. Never nil for a well-formed selector string. Cached. */
    public static long selector(String name) {
        requireLoaded();
        Long cached = SELECTOR_CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        try (Arena local = Arena.ofConfined()) {
            MemorySegment cName = local.allocateFrom(name);
            long sel = ((MemorySegment) SEL_REGISTER_NAME.invokeExact(cName)).address();
            SELECTOR_CACHE.put(name, sel);
            return sel;
        } catch (Throwable t) {
            throw new RuntimeException("sel_registerName(" + name + ")", t);
        }
    }

    /** {@code [receiver sel]} returning an object pointer (or nil = 0). */
    public static long msgSendId(long receiver, long sel) {
        requireLoaded();
        try {
            return ((MemorySegment) MSG_SEND_ID.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel))).address();
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(id)", t);
        }
    }

    /** {@code [receiver sel:arg]} returning an object pointer (or nil = 0). */
    public static long msgSendId(long receiver, long sel, long arg) {
        requireLoaded();
        try {
            return ((MemorySegment) MSG_SEND_ID_ID.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel),
                    MemorySegment.ofAddress(arg))).address();
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(id, id)", t);
        }
    }

    /** {@code [receiver sel:a arg2:b]} returning an object pointer (or nil = 0; two object args --
     * e.g. {@code +[NSDictionary dictionaryWithObject:forKey:]}). */
    public static long msgSendId(long receiver, long sel, long a, long b) {
        requireLoaded();
        try {
            return ((MemorySegment) MSG_SEND_ID_ID_ID.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel),
                    MemorySegment.ofAddress(a), MemorySegment.ofAddress(b))).address();
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(id, id, id)", t);
        }
    }

    /** {@code [receiver sel]} returning BOOL (no argument, for example {@code supportsRaytracing}). */
    public static boolean msgSendBool(long receiver, long sel) {
        requireLoaded();
        try {
            return (boolean) MSG_SEND_BOOL.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel));
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(BOOL)", t);
        }
    }

    /**
     * {@code [receiver sel:arg]} returning BOOL. {@code arg} sits in an ADDRESS-typed slot, so
     * this shape also covers a selector whose argument is a plain NSInteger rather than an object,
     * such as {@code supportsFamily:}: an integer and a pointer occupy the same general-purpose
     * register on arm64, so the raw value lands correctly through the pointer-typed slot.
     */
    public static boolean msgSendBool(long receiver, long sel, long arg) {
        requireLoaded();
        try {
            return (boolean) MSG_SEND_BOOL_ID.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel),
                    MemorySegment.ofAddress(arg));
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(BOOL, id)", t);
        }
    }

    /** {@code [receiver sel]} returning void. */
    public static void msgSendVoid(long receiver, long sel) {
        requireLoaded();
        try {
            MSG_SEND_VOID.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel));
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(void)", t);
        }
    }

    /** {@code [receiver sel:a b:b]} returning void (two object args). */
    public static void msgSendVoid(long receiver, long sel, long a, long b) {
        requireLoaded();
        try {
            MSG_SEND_VOID_ID_ID.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel),
                    MemorySegment.ofAddress(a), MemorySegment.ofAddress(b));
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(void, id, id)", t);
        }
    }

    /** {@code [receiver sel:arg]} returning void (one object arg -- property setters). */
    public static void msgSendVoid(long receiver, long sel, long arg) {
        requireLoaded();
        try {
            MSG_SEND_VOID_ID.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel),
                    MemorySegment.ofAddress(arg));
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(void, id)", t);
        }
    }

    /** {@code [receiver sel:value]} returning void (NSUInteger/NSInteger arg). */
    public static void msgSendVoidLong(long receiver, long sel, long value) {
        requireLoaded();
        try {
            MSG_SEND_VOID_LONG.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel), value);
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(void, long)", t);
        }
    }

    /** {@code [receiver sel:value]} returning void (float arg). */
    public static void msgSendVoidFloat(long receiver, long sel, float value) {
        requireLoaded();
        try {
            MSG_SEND_VOID_FLOAT.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel), value);
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(void, float)", t);
        }
    }

    /** {@code [receiver sel:value]} returning void (BOOL arg). */
    public static void msgSendVoidBool(long receiver, long sel, boolean value) {
        requireLoaded();
        try {
            MSG_SEND_VOID_BOOL.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel), value);
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(void, bool)", t);
        }
    }

    /** {@code [receiver sel:obj value:v]} returning void (object + uint64 args — MTLSharedEvent waits/signals). */
    public static void msgSendVoidIdLong(long receiver, long sel, long obj, long value) {
        requireLoaded();
        try {
            MSG_SEND_VOID_ID_LONG.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel),
                    MemorySegment.ofAddress(obj), value);
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(void, id, long)", t);
        }
    }

    /** {@code [receiver sel]} returning NSUInteger/NSInteger (64-bit on arm64). */
    public static long msgSendLong(long receiver, long sel) {
        requireLoaded();
        try {
            return (long) MSG_SEND_LONG.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel));
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(long)", t);
        }
    }

    /**
     * {@code [receiver sel]} returning a CFTimeInterval, for example a command buffer's
     * {@code GPUStartTime}. A double comes back in d0 on arm64, so it needs its own descriptor:
     * reading one through the long-returning handle gives the bit pattern as an integer.
     */
    public static double msgSendDouble(long receiver, long sel) {
        requireLoaded();
        try {
            return (double) MSG_SEND_DOUBLE.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel));
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(double)", t);
        }
    }

    /** {@code [receiver sel:a b:b]} returning an object pointer (or nil = 0; two NSUInteger args,
     * for example {@code newBufferWithLength:options:}). */
    public static long msgSendIdLongLong(long receiver, long sel, long a, long b) {
        requireLoaded();
        try {
            return ((MemorySegment) MSG_SEND_ID_LONG_LONG.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel), a, b))
                    .address();
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(id, long, long)", t);
        }
    }

    /** {@code [receiver sel:obj a:a b:b]} returning void (one object arg, two NSUInteger args,
     * for example {@code setBuffer:offset:atIndex:}). */
    public static void msgSendVoidIdLongLong(long receiver, long sel, long obj, long a, long b) {
        requireLoaded();
        try {
            MSG_SEND_VOID_ID_LONG_LONG.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel),
                    MemorySegment.ofAddress(obj), a, b);
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(void, id, long, long)", t);
        }
    }

    /**
     * The pair an ObjC "may fail" message returns through an {@code NSError**} out-parameter.
     * Success is tested by {@code id != 0}, never by whether {@code error} is null: Metal can set
     * the NSError to compiler warnings even when the call otherwise succeeds, so a nonzero id can
     * still come back paired with a non-null error. This bridge always reads the NSError's
     * {@code localizedDescription} when the out-parameter was filled, on success or failure alike,
     * so a caller that wants the warning text on a successful call can read it; a caller that only
     * cares whether the call worked checks {@code id}, not {@code error}.
     */
    public record Result(long id, String error) {}

    /**
     * {@code [receiver sel:a error:&err]}. One object arg plus an {@code NSError**} out-param
     * (for example {@code newComputePipelineStateWithFunction:error:}).
     */
    public static Result msgSendIdIdErr(long receiver, long sel, long a) {
        requireLoaded();
        try (Arena local = Arena.ofConfined()) {
            MemorySegment errOut = local.allocate(ValueLayout.JAVA_LONG);
            errOut.set(ValueLayout.JAVA_LONG, 0, 0L);
            long id = ((MemorySegment) MSG_SEND_ID_ID_PTR.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel),
                    MemorySegment.ofAddress(a), errOut)).address();
            return new Result(id, readErrorDescription(errOut));
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(id, id, ptr)", t);
        }
    }

    /**
     * {@code [receiver sel:a arg2:b error:&err]}. Two object args plus an {@code NSError**}
     * out-param (for example {@code newLibraryWithSource:options:error:}).
     */
    public static Result msgSendIdIdIdErr(long receiver, long sel, long a, long b) {
        requireLoaded();
        try (Arena local = Arena.ofConfined()) {
            MemorySegment errOut = local.allocate(ValueLayout.JAVA_LONG);
            errOut.set(ValueLayout.JAVA_LONG, 0, 0L);
            long id = ((MemorySegment) MSG_SEND_ID_ID_ID_PTR.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel),
                    MemorySegment.ofAddress(a), MemorySegment.ofAddress(b), errOut)).address();
            return new Result(id, readErrorDescription(errOut));
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(id, id, id, ptr)", t);
        }
    }

    /** Reads the {@code NSError*} an out-param slot was filled with, or null if it stayed nil. */
    private static String readErrorDescription(MemorySegment errOut) {
        long errPtr = errOut.get(ValueLayout.JAVA_LONG, 0);
        if (errPtr == 0) {
            return null;
        }
        return nsStringToJava(msgSendId(errPtr, selector("localizedDescription")));
    }

    /**
     * {@code [encoder dispatchThreadgroups:{gx,gy,gz} threadsPerThreadgroup:{tx,ty,tz}]}. The two
     * {@code MTLSize} structs are 24 bytes each, over the 16-byte threshold where the AArch64 ABI
     * stops passing a struct argument in registers: each one instead goes by reference to a
     * caller-owned copy. FFM derives this from the {@link StructLayout} in the downcall's
     * descriptor.
     */
    public static void dispatchThreadgroups(long encoder, long gx, long gy, long gz,
            long tx, long ty, long tz) {
        requireLoaded();
        try (Arena local = Arena.ofConfined()) {
            MemorySegment groups = sizeStruct(local, gx, gy, gz);
            MemorySegment threads = sizeStruct(local, tx, ty, tz);
            MSG_SEND_VOID_SIZE_SIZE.invokeExact(
                    MemorySegment.ofAddress(encoder),
                    MemorySegment.ofAddress(selector("dispatchThreadgroups:threadsPerThreadgroup:")),
                    groups, threads);
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(void, MTLSize, MTLSize)", t);
        }
    }

    private static MemorySegment sizeStruct(Arena arena, long width, long height, long depth) {
        MemorySegment segment = arena.allocate(MTL_SIZE);
        segment.set(ValueLayout.JAVA_LONG, 0, width);
        segment.set(ValueLayout.JAVA_LONG, 8, height);
        segment.set(ValueLayout.JAVA_LONG, 16, depth);
        return segment;
    }

    /** {@code [receiver sel:ptr count:count]} returning an object pointer (or nil = 0). For
     * example {@code +[NSArray arrayWithObjects:count:]}, where {@code ptr} is the address of a
     * caller-owned C array of {@code id} values. */
    public static long msgSendIdPtrLong(long receiver, long sel, long ptr, long count) {
        requireLoaded();
        try {
            return ((MemorySegment) MSG_SEND_ID_PTR_LONG.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel),
                    MemorySegment.ofAddress(ptr), count)).address();
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(id, ptr, long)", t);
        }
    }

    /** {@code [receiver sel:a b:b c:c d:d]} returning void (three object args, one NSUInteger arg,
     * for example {@code buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:}). */
    public static void msgSendVoidIdIdIdLong(long receiver, long sel, long a, long b, long c, long d) {
        requireLoaded();
        try {
            MSG_SEND_VOID_ID_ID_ID_LONG.invokeExact(
                    MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(sel),
                    MemorySegment.ofAddress(a), MemorySegment.ofAddress(b),
                    MemorySegment.ofAddress(c), d);
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(void, id, id, id, long)", t);
        }
    }

    /**
     * {@code [texture getBytes:bytesOut bytesPerRow:bytesPerRow fromRegion:{origin,size}
     * mipmapLevel:0]} reads a texture's pixels back to host memory. {@code MTLRegion} (48
     * bytes: an {@code MTLOrigin} then an {@code MTLSize}, six NSUInteger words) is over the
     * 16-byte by-reference threshold, same reasoning as {@link #dispatchThreadgroups}'s two
     * {@code MTLSize} arguments.
     */
    public static void getBytesFromRegion(long texture, long bytesOut, long bytesPerRow,
            long originX, long originY, long originZ, long width, long height, long depth,
            long mipmapLevel) {
        requireLoaded();
        try (Arena local = Arena.ofConfined()) {
            MemorySegment region = local.allocate(MTL_REGION);
            region.set(ValueLayout.JAVA_LONG, 0, originX);
            region.set(ValueLayout.JAVA_LONG, 8, originY);
            region.set(ValueLayout.JAVA_LONG, 16, originZ);
            region.set(ValueLayout.JAVA_LONG, 24, width);
            region.set(ValueLayout.JAVA_LONG, 32, height);
            region.set(ValueLayout.JAVA_LONG, 40, depth);
            MSG_SEND_VOID_PTR_LONG_REGION_LONG.invokeExact(
                    MemorySegment.ofAddress(texture),
                    MemorySegment.ofAddress(selector("getBytes:bytesPerRow:fromRegion:mipmapLevel:")),
                    MemorySegment.ofAddress(bytesOut), bytesPerRow, region, mipmapLevel);
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(void, ptr, long, MTLRegion, long)", t);
        }
    }

    /** The build/refit/final byte sizes an acceleration structure descriptor needs, as returned
     * by {@code accelerationStructureSizesWithDescriptor:}. */
    public record AccelerationStructureSizes(
            long accelerationStructureSize, long buildScratchBufferSize, long refitScratchBufferSize) {
    }

    /** {@code [device accelerationStructureSizesWithDescriptor:descriptor]} is a struct-by-value
     * return, so this downcall needs a {@link java.lang.foreign.SegmentAllocator} to receive it: a confined arena
     * scoped to this one call supplies it and is freed immediately after the three longs are
     * copied out. */
    public static AccelerationStructureSizes accelerationStructureSizes(long device, long descriptor) {
        requireLoaded();
        try (Arena local = Arena.ofConfined()) {
            // invokeExact requires the STATIC argument type to match the handle's descriptor
            // exactly: the struct-return handle's first parameter is SegmentAllocator, and
            // Arena (which implements it) does not satisfy that without this cast, even though
            // it would satisfy a normal (non-exact) call.
            MemorySegment result = (MemorySegment) MSG_SEND_ACCEL_SIZES.invokeExact((SegmentAllocator) local,
                    MemorySegment.ofAddress(device),
                    MemorySegment.ofAddress(selector("accelerationStructureSizesWithDescriptor:")),
                    MemorySegment.ofAddress(descriptor));
            return new AccelerationStructureSizes(
                    result.get(ValueLayout.JAVA_LONG, 0),
                    result.get(ValueLayout.JAVA_LONG, 8),
                    result.get(ValueLayout.JAVA_LONG, 16));
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(MTLAccelerationStructureSizes)", t);
        }
    }

    /**
     * {@code objc_autoreleasePoolPush()} — REQUIRED around any per-frame message that returns an
     * autoreleased object (e.g. {@code [MTLCommandQueue commandBuffer]}): this JVM render thread
     * has no ambient NSAutoreleasePool, so without an explicit push/pop pair every autoreleased
     * Metal object would leak for the process lifetime. Pair with {@link #autoreleasePoolPop}.
     */
    public static long autoreleasePoolPush() {
        requireLoaded();
        try {
            return ((MemorySegment) AUTORELEASE_POOL_PUSH.invokeExact()).address();
        } catch (Throwable t) {
            throw new RuntimeException("objc_autoreleasePoolPush()", t);
        }
    }

    /** {@code objc_autoreleasePoolPop(pool)} — see {@link #autoreleasePoolPush}. */
    public static void autoreleasePoolPop(long pool) {
        requireLoaded();
        try {
            AUTORELEASE_POOL_POP.invokeExact(MemorySegment.ofAddress(pool));
        } catch (Throwable t) {
            throw new RuntimeException("objc_autoreleasePoolPop()", t);
        }
    }

    /** {@code MTLCreateSystemDefaultDevice()} — a retained MTLDevice, or 0 when no Metal GPU. */
    public static long createSystemDefaultMetalDevice() {
        requireLoaded();
        try {
            return ((MemorySegment) MTL_CREATE_SYSTEM_DEFAULT_DEVICE.invokeExact()).address();
        } catch (Throwable t) {
            throw new RuntimeException("MTLCreateSystemDefaultDevice()", t);
        }
    }

    /**
     * Reads an {@code NSString*} as a Java string via {@code -UTF8String} (the returned C pointer
     * is interior to the NSString's autoreleased/owned storage — copied into Java immediately, so
     * no lifetime is retained here). Returns "" for nil.
     */
    public static String nsStringToJava(long nsString) {
        requireLoaded();
        if (nsString == 0) {
            return "";
        }
        long utf8Ptr = msgSendId(nsString, selector("UTF8String"));
        if (utf8Ptr == 0) {
            return "";
        }
        // The returned ADDRESS has zero length; reinterpret to read the NUL-terminated bytes.
        return MemorySegment.ofAddress(utf8Ptr).reinterpret(Long.MAX_VALUE).getString(0);
    }

    /**
     * {@code [NSString stringWithUTF8String:cString]} -- an autoreleased {@code NSString*} built
     * from a Java string (UTF-8, NUL-terminated). Companion to {@link #nsStringToJava}, for callers
     * that need to hand ObjC an NSString-valued argument (e.g. an {@code NSDictionary} key/value)
     * rather than just reading one back. The C string lives in a confined arena scoped to this call
     * only -- {@code stringWithUTF8String:} copies the bytes into the NSString's own storage before
     * returning, so the backing memory is safe to free the moment the call returns.
     */
    public static long nsString(String value) {
        requireLoaded();
        try (Arena local = Arena.ofConfined()) {
            MemorySegment cString = local.allocateFrom(value);
            long nsStringClass = getClass("NSString");
            if (nsStringClass == 0) {
                throw new IllegalStateException("NSString class not found");
            }
            return msgSendId(nsStringClass, selector("stringWithUTF8String:"), cString.address());
        }
    }
}
