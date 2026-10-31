package com.scaleguard.server.functions;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Method names of the host API objects (req, res, ctx, log, http). The run-time guard still checks each call against the receiver's own class. */
final class SandboxApiNames {
    private static final Set<String> NAMES = Arrays.stream(FunctionApi.class.getDeclaredClasses())
            .filter(c -> SandboxApi.class.isAssignableFrom(c))
            .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
            .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()).map(Method::getName).collect(Collectors.toSet());

    private SandboxApiNames() {}

    static boolean has(String name) {
        return NAMES.contains(name);
    }
}
