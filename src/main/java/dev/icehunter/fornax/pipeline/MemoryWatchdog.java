package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.FornaxMod;

import java.lang.management.ManagementFactory;

/**
 * Periodic process-memory sampling, kept resident to diagnose Apple-Silicon device-loss crashes
 * ({@code VK_ERROR_DEVICE_LOST} / {@code kIOGPUCommandBufferCallbackErrorOutOfMemory}): a monotonic
 * climb sampled here narrows a multi-minute crash-to-repro cycle to a measurement of seconds,
 * turning what allocation is growing into a bisectable question instead of a guess against crash
 * report timing that cannot be trusted to point at the responsible commit.
 *
 * <p>So the question this answers is the one that has to be answered first, and cannot be answered
 * by reading code: <b>does the process's memory climb, or is it flat and simply too high?</b>
 * A monotonic climb means a leak, and the rate in MB/s combined with what is on screen at the time
 * narrows the source enormously. A flat line means the budget is being over-committed up front and
 * the crash is a threshold, not a leak -- an entirely different investigation. Guessing between
 * those two costs more than one instrumented session.
 *
 * <p>{@code getCommittedVirtualMemorySize} is the PROCESS's own committed virtual size, which is
 * the number that matters here and is deliberately not the one Minecraft's crash report prints --
 * that report's "Virtual memory used" comes from OSHI and is SYSTEM-WIDE, so it moves with whatever
 * else is running on the machine and cannot be attributed to this process at all. On Apple Silicon
 * the GPU's memory is unified with the CPU's, so Metal allocations land in this figure too, which
 * is exactly why it is the right probe for a GPU out-of-memory.
 *
 * <p>Sampling is per frame and gated on a wall-clock interval, mirroring
 * {@code FrameGenPresenter.maybeLogCadence}. Diagnostic only -- it allocates nothing per frame and
 * logs one line every {@value #INTERVAL_SECONDS} seconds.
 */
public final class MemoryWatchdog {
    private static final long INTERVAL_SECONDS = 5L;
    private static final long INTERVAL_NANOS = INTERVAL_SECONDS * 1_000_000_000L;
    private static final double MIB = 1024.0 * 1024.0;

    private static long lastSampleNanos;
    private static long firstCommitted;
    private static long previousCommitted;
    private static long firstSampleNanos;
    private static boolean reportedUnavailable;

    private MemoryWatchdog() {
    }

    /** Samples if the interval has elapsed. Safe to call every frame from the render thread. */
    public static void sample() {
        long now = System.nanoTime();
        if (lastSampleNanos == 0L) {
            lastSampleNanos = now;
            firstSampleNanos = now;
            return;
        }
        if (now - lastSampleNanos < INTERVAL_NANOS) {
            return;
        }
        lastSampleNanos = now;

        long committed = committedVirtualBytes();
        if (committed < 0L) {
            // Loudly, once. The first version of this returned silently here, and since the probe
            // itself was broken the result was a diagnostic that ran thirty times across a session
            // and produced not one line -- indistinguishable from "the watchdog never ran" and a
            // wasted crash. A diagnostic that cannot measure has to SAY so.
            if (!reportedUnavailable) {
                reportedUnavailable = true;
                FornaxMod.LOGGER.error("[Fornax][mem] cannot read committed virtual memory on this"
                        + " JVM -- memory diagnostics are unavailable this session");
            }
            return;
        }
        if (firstCommitted == 0L) {
            firstCommitted = committed;
            previousCommitted = committed;
        }

        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();

        double sinceStartSeconds = (now - firstSampleNanos) / 1.0e9;
        double deltaMib = (committed - previousCommitted) / MIB;
        double totalGrowthMib = (committed - firstCommitted) / MIB;
        // The headline number: sustained MB/s of process growth. Near zero means the crash is a
        // threshold rather than a leak, and this whole line of investigation is the wrong one.
        double ratePerSecond = sinceStartSeconds > 0.0 ? totalGrowthMib / sinceStartSeconds : 0.0;

        previousCommitted = committed;

        FornaxMod.LOGGER.info(
                "[Fornax][mem] t={}s committed={}MiB (delta {}MiB, total +{}MiB, {}MiB/s) heap={}MiB",
                String.format("%.0f", sinceStartSeconds),
                String.format("%.0f", committed / MIB),
                String.format("%+.1f", deltaMib),
                String.format("%.1f", totalGrowthMib),
                String.format("%.2f", ratePerSecond),
                String.format("%.0f", heapUsed / MIB));
    }

    /**
     * The process's committed virtual memory in bytes, or -1 when the running JVM does not expose
     * the HotSpot extension.
     *
     * <p>An {@code instanceof} test against the exported {@code com.sun.management} INTERFACE, not
     * reflection over the returned object's class. The first version did the latter, on the
     * reasoning that the type might be absent on some JVM -- and it failed on every call:
     * {@link ManagementFactory#getOperatingSystemMXBean()} returns
     * {@code com.sun.management.internal.OperatingSystemImpl}, whose package the {@code
     * java.management} module does not export, so {@code setAccessible(true)} throws
     * {@code InaccessibleObjectException} under the module system regardless of what the method
     * itself is. The interface is exported and the implementation always implements it, so a plain
     * cast both works and needs no reflection at all. {@code instanceof} keeps the safety the
     * reflection was reaching for: on a JVM without the extension the pattern simply does not
     * match.
     */
    static long committedVirtualBytes() {
        return ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean bean
                ? bean.getCommittedVirtualMemorySize()
                : -1L;
    }
}
