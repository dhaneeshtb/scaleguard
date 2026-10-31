package com.scaleguard.server.functions;

import groovy.lang.Closure;
import groovy.lang.GString;
import groovy.lang.Range;
import groovy.lang.Script;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The allowlists behind the Groovy sandbox. The same lists are enforced twice: at compile time (SandboxCompiler)
 * and at run time on every dynamic dispatch (GuardedMetaClass), so a trick that hides a name or a class from the
 * compiler (computed keys, GString indexes, values smuggled through collections) still hits the run-time check.
 */
final class SandboxPolicy {

    private static final boolean DEBUG = Boolean.getBoolean("scaleguard.functions.debug");

    /** True only on a thread that is running a function; Groovy-implemented library code (the compiler) must not be guarded. */
    static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    static boolean active() {
        return ACTIVE.get() != null;
    }

    private SandboxPolicy() {}

    private static Set<String> names(String... n) {
        return Set.copyOf(new java.util.HashSet<>(Arrays.asList(n)));
    }

    /** Classes a script may name in static calls (Math.max) and use as types. */
    static final Set<String> STATIC_TYPES = Set.of(
            "java.lang.Math", "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
            "java.lang.Short", "java.lang.Byte", "java.lang.Boolean", "java.lang.Character", "java.math.BigDecimal",
            "java.math.BigInteger", "java.util.Collections", "java.util.UUID", "java.util.List", "java.util.Map", "java.util.Set",
            "groovy.json.JsonOutput", "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.ZonedDateTime",
            "java.time.Duration", "java.time.ZoneId", "java.time.ZoneOffset", "java.time.format.DateTimeFormatter");

    /** Classes a script may instantiate. */
    static final Set<String> NEW_TYPES = Set.of(
            "java.util.ArrayList", "java.util.LinkedList", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet", "java.lang.StringBuilder",
            "java.math.BigDecimal", "java.math.BigInteger", "groovy.json.JsonSlurper");

    /** Packages whose classes may be star-imported (an import only makes a name resolvable; use is still checked against the lists above). */
    static final Set<String> IMPORT_PACKAGES = Set.of("groovy.json", "java.time", "java.time.format", "java.math", "java.util");

    /** Classes allowed in declarations and casts (superset of the above). */
    static final Set<String> TYPES = Set.of(
            "java.lang.Object", "java.lang.Number", "java.lang.CharSequence", "java.lang.Iterable", "java.util.Collection",
            "java.util.Map$Entry", "groovy.lang.Closure", "groovy.lang.GString", "groovy.lang.Range", "void");

    /** Names a script may call on any allowed object. Everything else (getClass, invokeMethod, execute, evaluate, with, wait …) is refused. */
    static final Set<String> METHODS = names(
            // objects
            "toString", "equals", "hashCode", "compareTo", "asBoolean", "is", "isCase", "inspect",
            // strings
            "length", "size", "isEmpty", "charAt", "substring", "indexOf", "lastIndexOf", "contains", "startsWith", "endsWith",
            "toUpperCase", "toLowerCase", "trim", "strip", "replace", "replaceAll", "replaceFirst", "split", "tokenize", "join",
            "matches", "format", "formatted", "capitalize", "reverse", "padLeft", "padRight", "center", "take", "drop", "takeWhile",
            "dropWhile", "toInteger", "toLong", "toDouble", "toBigDecimal", "toBigInteger", "toBoolean", "isNumber", "isInteger",
            "isLong", "isDouble", "isBigDecimal", "isBlank", "chars", "bytes", "trim", "stripIndent", "readLines", "lines", "append",
            "insert", "setLength", "deleteCharAt", "delete", "repeat", "codePointAt", "equalsIgnoreCase", "compareToIgnoreCase",
            "plus", "minus", "multiply", "div", "mod", "power", "negate", "next", "previous", "leftShift", "intdiv", "abs",
            // numbers / Math
            "max", "min", "round", "floor", "ceil", "sqrt", "pow", "random", "intValue", "longValue", "doubleValue", "floatValue",
            "setScale", "signum", "valueOf", "parseInt", "parseLong", "parseDouble", "toPlainString", "compare", "sum", "average",
            // collections
            "add", "addAll", "remove", "removeAll", "retainAll", "clear", "get", "getAt", "put", "putAll", "putAt", "containsKey",
            "containsValue", "keySet", "values", "entrySet", "getKey", "getValue", "key", "value", "iterator", "hasNext", "toList",
            "toSet", "toSorted", "sort", "unique", "flatten", "first", "last", "head", "tail", "init", "findAll", "find", "findResult",
            "each", "eachWithIndex", "collect", "collectEntries", "collectMany", "inject", "any", "every", "count", "countBy",
            "groupBy", "indexed", "withIndex", "sum", "min", "max", "reverse", "subList", "intersect", "disjoint", "toUnique",
            "asImmutable", "asList", "withDefault", "findIndexOf", "grep", "split", "partition", "collate", "transpose", "combinations",
            "removeIf", "putIfAbsent", "getOrDefault", "computeIfAbsent", "merge", "subMap", "sort", "entry", "of", "copyOf", "emptyList",
            "emptyMap", "unmodifiableList", "unmodifiableMap", "singletonList", "randomUUID", "mapValues",
            // closures / ranges
            "parseText", "parse", "toJson", "prettyPrint", "now", "ofEpochMilli", "ofEpochSecond", "toEpochMilli", "getEpochSecond",
            "plusSeconds", "plusMinutes", "plusHours", "plusDays", "minusSeconds", "minusMinutes", "minusHours", "minusDays", "between",
            "ofSeconds", "ofMillis", "ofMinutes", "ofHours", "ofDays", "toMillis", "toSeconds", "atZone", "toLocalDate", "toInstant",
            "withZone", "ofPattern", "toSecondOfDay", "call", "doCall", "curry", "rcurry", "memoize", "times", "upto", "downto", "step", "getFrom", "getTo", "contains");

    /** Property names that never reach a Java object (they expose Class, ClassLoader, MetaClass, the script binding …). Map keys are data and exempt. */
    static final Set<String> DENIED_PROPERTIES = Set.of(
            "class", "metaClass", "binding", "owner", "delegate", "thisObject", "classLoader", "declaringClass", "protectionDomain",
            "codeSource", "module", "properties", "declaredFields", "declaredMethods", "methods", "fields", "constructors",
            "resolveStrategy", "directive", "parameterTypes", "annotations", "superclass", "interfaces", "package", "runtime",
            "out", "err", "in", "env", "accessible");

    /** Property names refused at compile time on any receiver (a Map key with these names is reachable as m['class']). */
    static final Set<String> NEVER_PROPERTIES = Set.of("class", "metaClass", "classLoader", "binding", "declaringClass", "protectionDomain");

    /** Names that may not be used as free (binding) variables. */
    static final Set<String> DENIED_VARIABLES = Set.of("binding", "metaClass", "class", "this", "super", "args", "out", "err");

    private static final Set<Class<?>> VALUE_BASES = Set.of(CharSequence.class, Number.class, Boolean.class, Character.class,
            Collection.class, Map.class, Map.Entry.class, Iterator.class, Closure.class, GString.class, Range.class,
            groovy.json.JsonSlurper.class, java.time.temporal.Temporal.class, java.time.Duration.class, java.time.ZoneId.class,
            java.time.ZoneOffset.class, java.time.format.DateTimeFormatter.class);

    /** Script classes registered after compilation, with the methods the author declared. */
    private static final class Holder { volatile Set<String> methods; }

    /** ClassValue: lock-free on the hot path and released with the class when a function version is replaced. */
    private static final ClassValue<Holder> SCRIPTS = new ClassValue<>() {
        @Override protected Holder computeValue(Class<?> type) { return new Holder(); }
    };
    private static final Map<Class<?>, Set<String>> API_NAMES = new ConcurrentHashMap<>();

    static void registerScript(Class<?> c, Set<String> userMethods) {
        SCRIPTS.get(c).methods = userMethods;
    }

    /** True if instances of this class may be touched at all by sandboxed code. */
    static boolean valueType(Class<?> c) {
        if (c.isArray()) {
            Class<?> t = c.getComponentType();
            return t.isPrimitive() || t == String.class || t == Object.class;
        }
        if (c.isPrimitive() || c == org.codehaus.groovy.runtime.NullObject.class || SandboxApi.class.isAssignableFrom(c) || SCRIPTS.get(c).methods != null) return true;
        // Class, ClassLoader, Thread, Method, Field, Process … are none of the value bases
        for (Class<?> b : VALUE_BASES) if (b.isAssignableFrom(c) && !isInternal(c)) return true;
        return false;
    }

    /** Guards against a script subclassing its way into a "value" type that is really host code. */
    private static boolean isInternal(Class<?> c) {
        String n = c.getName();
        return n.startsWith("com.scaleguard.") && !SandboxApi.class.isAssignableFrom(c) || n.startsWith("io.netty.")
                || n.startsWith("org.codehaus.groovy.tools.") || n.startsWith("groovy.lang.GroovyClassLoader");
    }

    private static Set<String> apiNames(Class<?> c) {
        return API_NAMES.computeIfAbsent(c, k -> Arrays.stream(k.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()).map(Method::getName).collect(Collectors.toSet()));
    }

    static void checkCall(Object receiver, Class<?> theClass, String name) {
        if (receiver instanceof Class<?> c) {            // static call, or an instance-style call on a Class value
            if (!STATIC_TYPES.contains(c.getName()) && !NEW_TYPES.contains(c.getName())) throw denied("class " + c.getName());
            if (!METHODS.contains(name)) throw denied("method " + name);
            return;
        }
        Class<?> c = receiver == null ? theClass : receiver.getClass();
        if (!valueType(c)) throw denied("type " + c.getName());
        Set<String> own = SCRIPTS.get(c).methods;
        if (own != null) {
            if (!own.contains(name) && !METHODS.contains(name)) throw denied("method " + name);
        } else if (SandboxApi.class.isAssignableFrom(c)) {
            if (!apiNames(c).contains(name)) throw denied("method " + name);
        } else if (!METHODS.contains(name)) {
            throw denied("method " + name);
        }
    }

    static void checkProperty(Object receiver, Class<?> theClass, String name, boolean write) {
        if (receiver instanceof Class<?> c) {
            // constants only (Integer.MAX_VALUE); never Math.classLoader, String.module …
            if (!STATIC_TYPES.contains(c.getName()) || write || !name.matches("[A-Z][A-Z0-9_]*")) throw denied("property " + name + " of " + c.getName());
            return;
        }
        Class<?> c = receiver == null ? theClass : receiver.getClass();
        if (!valueType(c)) throw denied("type " + c.getName());
        if (receiver instanceof Map) return;              // keys are data
        if (SCRIPTS.get(c).methods != null) {                     // free variables of the script (req, res, ctx, the author's own)
            if (DENIED_VARIABLES.contains(name)) throw denied("variable " + name);
            return;
        }
        if (DENIED_PROPERTIES.contains(name)) throw denied("property " + name);
        if (SandboxApi.class.isAssignableFrom(c)) {
            String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            Set<String> n = apiNames(c);
            if (!(n.contains((write ? "set" : "get") + cap) || (!write && n.contains("is" + cap)) || n.contains(name))) throw denied("property " + name);
        }
    }

    static void checkConstruct(Class<?> c) {
        if (!NEW_TYPES.contains(c.getName())) throw denied("new " + c.getName());
    }

    static SecurityException denied(String what) {
        SecurityException e = new SecurityException("not allowed in a cloud function: " + what);
        if (DEBUG) e.printStackTrace();
        return e;
    }

    static boolean isAllowedStatic(String type) { return STATIC_TYPES.contains(type); }

    static boolean isAllowedType(String type) {
        return STATIC_TYPES.contains(type) || NEW_TYPES.contains(type) || TYPES.contains(type) || type.equals("groovy.lang.Script");
    }

    static String importHint() {
        java.util.TreeSet<String> all = new java.util.TreeSet<>(STATIC_TYPES);
        all.addAll(NEW_TYPES);
        return String.join(", ", all) + "; packages " + String.join(".*, ", new java.util.TreeSet<>(IMPORT_PACKAGES)) + ".*";
    }

    static Class<?> scriptBase() { return Script.class; }
}
