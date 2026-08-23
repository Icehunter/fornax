package dev.icehunter.fornax.pack;

import com.electronwill.nightconfig.core.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict typed readers over a night-config {@link Config}: every miss or type error is a {@link FornaxPackError}. */
final class TomlSupport {
    private TomlSupport() {}

    static String requireString(Config c, String key, String file) {
        Object v = require(c, key, file);
        if (!(v instanceof String s)) {
            throw new FornaxPackError(file, key, "expected a string, got " + typeName(v));
        }
        return s;
    }

    static int requireInt(Config c, String key, String file) {
        Object v = require(c, key, file);
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return Math.toIntExact(l);
        throw new FornaxPackError(file, key, "expected an integer, got " + typeName(v));
    }

    static double getDouble(Config c, String key, double fallback, String file) {
        if (!c.contains(key)) return fallback;
        Object v = c.get(key);
        if (v instanceof Number n) return n.doubleValue();
        throw new FornaxPackError(file, key, "expected a number, got " + typeName(v));
    }

    static boolean getBoolean(Config c, String key, boolean fallback, String file) {
        if (!c.contains(key)) return fallback;
        Object v = c.get(key);
        if (v instanceof Boolean b) return b;
        throw new FornaxPackError(file, key, "expected a boolean, got " + typeName(v));
    }

    static String getStringOrNull(Config c, String key, String file) {
        if (!c.contains(key)) return null;
        return requireString(c, key, file);
    }

    static List<String> getStringList(Config c, String key, String file) {
        if (!c.contains(key)) return List.of();
        Object v = c.get(key);
        if (!(v instanceof List<?> raw)) {
            throw new FornaxPackError(file, key, "expected a list of strings, got " + typeName(v));
        }
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (!(o instanceof String s)) {
                throw new FornaxPackError(file, key, "list must contain only strings, found " + typeName(o));
            }
            out.add(s);
        }
        return out;
    }

    static List<Integer> getIntList(Config c, String key, String file) {
        if (!c.contains(key)) return List.of();
        Object v = c.get(key);
        if (!(v instanceof List<?> raw)) {
            throw new FornaxPackError(file, key, "expected a list of integers, got " + typeName(v));
        }
        List<Integer> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof Integer i) {
                out.add(i);
            } else if (o instanceof Long l) {
                try {
                    out.add(Math.toIntExact(l));
                } catch (ArithmeticException e) {
                    throw new FornaxPackError(file, key, "dispatch value out of range: " + l);
                }
            } else {
                throw new FornaxPackError(file, key, "list must contain only integers, found " + typeName(o));
            }
        }
        return out;
    }

    static void rejectUnknownKeys(Config c, Set<String> allowed, String file) {
        for (Config.Entry e : c.entrySet()) {
            if (!allowed.contains(e.getKey())) {
                throw new FornaxPackError(file, e.getKey(), "unknown key '" + e.getKey() + "'");
            }
        }
    }

    private static Object require(Config c, String key, String file) {
        if (!c.contains(key)) {
            throw new FornaxPackError(file, key, "missing required key '" + key + "'");
        }
        return c.get(key);
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }
}
