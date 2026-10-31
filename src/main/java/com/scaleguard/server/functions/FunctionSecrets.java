package com.scaleguard.server.functions;

import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway-wide secrets (API keys a function may need). Values are write-only in the admin API; a function can read only
 * the names its settings list. Stored in system_properties as {@code functions.secret.NAME}, so they replicate with the
 * cluster ({@code system_properties/functions} scope).
 */
public final class FunctionSecrets {
    static final String PREFIX = "functions.secret.";
    private static volatile Map<String, String> values = new ConcurrentHashMap<>();

    private FunctionSecrets() {}

    static boolean validName(String n) {
        return n != null && n.matches("[A-Za-z][A-Za-z0-9_]{0,63}");
    }

    public static synchronized void reload() {
        Map<String, String> m = new ConcurrentHashMap<>();
        for (SystemProperty p : SystemPropertyDB.getInstance().readAll()) {
            if (p.getId() != null && p.getId().startsWith(PREFIX) && p.getValue() != null) m.put(p.getId().substring(PREFIX.length()), p.getValue());
        }
        values = m;
    }

    public static List<String> names() {
        return new ArrayList<>(new TreeMap<>(values).keySet());
    }

    static String get(String name) {
        return name == null ? null : values.get(name);
    }

    public static void set(String name, String value) throws Exception {
        if (!validName(name)) throw new IllegalArgumentException("secret names are letters, digits and _ (max 64), starting with a letter");
        SystemProperty p = new SystemProperty();
        p.setId(PREFIX + name);
        p.setName(PREFIX + name);
        p.setGroupname("functions");
        p.setValue(value);
        p.setMts(System.currentTimeMillis());
        p.setUts(System.currentTimeMillis());
        SystemPropertyDB.getInstance().save(List.of(p));
        values.put(name, value);
    }

    public static void delete(String name) throws Exception {
        SystemPropertyDB.getInstance().delete(PREFIX + name);
        values.remove(name);
    }
}
