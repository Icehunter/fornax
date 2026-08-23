package dev.icehunter.fornax.compat;

import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link SodiumConfigEntry#registerConfigLate} has no test-visible Minecraft/Sodium runtime to
 * exercise end to end, but {@link ConfigBuilder} is a plain interface, so a throwing proxy stands
 * in for "Sodium's API shape changed under us" -- pinning that the method degrades instead of
 * taking the whole video-settings screen down with it.
 */
class SodiumConfigEntryTest {
    @Test
    void registrationFailureIsCaughtRatherThanPropagated() {
        ConfigBuilder brokenBuilder = (ConfigBuilder) Proxy.newProxyInstance(
                ConfigBuilder.class.getClassLoader(),
                new Class<?>[] {ConfigBuilder.class},
                (InvocationHandler) (proxy, method, args) -> {
                    throw new NoSuchMethodError("simulated Sodium config API change");
                });

        assertDoesNotThrow(() -> new SodiumConfigEntry().registerConfigLate(brokenBuilder));
    }
}
