package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Guards the probe {@link MemoryWatchdog} is built around.
 *
 * <p>Written because the first version of that probe used reflection over the OS MXBean's
 * implementation class, which the module system blocks -- so it returned -1 on every call and the
 * watchdog logged nothing at all through a whole crashed session. The failure was invisible: a
 * diagnostic producing no output looks exactly like a diagnostic that never ran. This asserts the
 * probe actually returns a number on the JVM the mod runs on.
 */
class MemoryWatchdogTest {

    @Test
    void committedVirtualMemoryIsReadableOnThisJvm() {
        long committed = MemoryWatchdog.committedVirtualBytes();
        assertTrue(committed > 0L,
                "committed virtual memory must be readable -- got " + committed
                        + ", which means the watchdog would silently report nothing all session");
    }

    @Test
    void committedVirtualMemoryIsAPlausibleProcessSize() {
        // A JVM running a test suite occupies well over 64 MiB of virtual address space; anything
        // under that means the probe is reading the wrong thing rather than failing outright.
        assertTrue(MemoryWatchdog.committedVirtualBytes() > 64L * 1024 * 1024);
    }
}
