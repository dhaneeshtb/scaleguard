package com.scaleguard.server.functions;

import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import groovy.transform.ThreadInterrupt;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles a function's Groovy source into a Script class, once per version. Layers: (1) every annotation, import and
 * extra class is rejected before Groovy resolves anything (so @Grab/@ASTTest never run); (2) an allowlist visitor checks
 * every type, method name and property in the resolved tree; (3) interrupt checks are woven into loops, closures and
 * methods so the time limit can stop a spinning script; (4) the class is defined in a loader that cannot see the host.
 */
final class SandboxCompiler {

    private SandboxCompiler() {}

    static final class CompileException extends Exception {
        CompileException(String m) { super(m); }
    }

    /** Name-level blocklist for the script's own class loader (defence in depth; the allowlists are the real gate). Thread stays visible: the interrupt checks the compiler weaves in call Thread.currentThread().isInterrupted(). Resolves only what generated script code needs; host classes (com.scaleguard, netty, java.io …) are invisible by name. */
    private static final class ScriptLoader extends ClassLoader {
        ScriptLoader() { super(SandboxCompiler.class.getClassLoader()); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.scaleguard.") && !name.startsWith("com.scaleguard.server.functions.")
                    || name.startsWith("io.netty.") || name.startsWith("java.io.") || name.startsWith("java.nio.")
                    || name.startsWith("java.net.") && !name.equals("java.net.URL") || name.startsWith("java.lang.reflect.") || name.startsWith("java.lang.invoke.") && !name.equals("java.lang.invoke.MethodHandles") && !name.equals("java.lang.invoke.MethodHandles$Lookup")
                    || name.startsWith("sun.") || name.startsWith("jdk.") || name.startsWith("com.sun.")
                    || name.equals("java.lang.System") || name.equals("java.lang.Runtime")
                    || name.equals("java.lang.ClassLoader") || name.equals("java.lang.ProcessBuilder") || name.equals("java.lang.Process")) {
                throw new ClassNotFoundException(name + " is not available to cloud functions");
            }
            return super.loadClass(name, resolve);
        }
    }

    /**
     * Rejects any '@' outside a quoted string or comment. Annotations such as @Grab and @ASTTest are executed by the Groovy
     * compiler itself while it parses, before any customizer can inspect the tree, so they must never reach it.
     * Conservative: an '@' inside a /slashy/ string or a $/dollar-slashy/$ string is rejected too (use '…' or "…").
     */
    static void rejectAnnotations(String code) throws CompileException {
        int n = code.length();
        for (int i = 0; i < n; i++) {
            char c = code.charAt(i);
            if (c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                while (i < n && code.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                int e = code.indexOf("*/", i + 2);
                if (e < 0) return;
                i = e + 1;
            } else if (c == '\'' || c == '"') {
                boolean triple = code.startsWith(String.valueOf(c).repeat(3), i);
                String close = triple ? String.valueOf(c).repeat(3) : String.valueOf(c);
                i += triple ? 3 : 1;
                while (i < n && !code.startsWith(close, i)) {
                    if (code.charAt(i) == '\\') i++;
                    i++;
                }
                i += close.length() - 1;
            } else if (c == '@') {
                throw new CompileException("annotations (@) are not allowed");
            }
        }
    }

    static Class<? extends Script> compile(String code, String name) throws CompileException {
        rejectAnnotations(code);
        GuardedMetaClass.install();
        CompilerConfiguration cfg = new CompilerConfiguration();
        cfg.setScriptBaseClass(Script.class.getName());
        cfg.setDebug(false);
        // classic call sites (overridden in GuardedMetaClass) instead of invokedynamic method handles, which could cache past the guard
        cfg.getOptimizationOptions().put(CompilerConfiguration.INVOKEDYNAMIC, false);
        cfg.setTargetBytecode(CompilerConfiguration.JDK17);

        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setStaticImportsWhitelist(new ArrayList<>());      // imports themselves are vetted by EarlyCheck
        secure.setStaticStarImportsWhitelist(new ArrayList<>());
        secure.setPackageAllowed(false);
        secure.setMethodDefinitionAllowed(true);
        secure.setClosuresAllowed(true);

        Set<String> userMethods = new HashSet<>();
        cfg.addCompilationCustomizers(new EarlyCheck(), secure, new AllowlistCheck(userMethods),
                new ASTTransformationCustomizer(ThreadInterrupt.class));
        GroovyClassLoader gcl = new GroovyClassLoader(new ScriptLoader(), cfg);
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Script> c = (Class<? extends Script>) gcl.parseClass(code, "fn_" + name.replaceAll("[^A-Za-z0-9_]", "_") + ".groovy");
            SandboxPolicy.registerScript(c, userMethods);
            return c;
        } catch (MultipleCompilationErrorsException e) {
            List<String> msgs = new ArrayList<>();
            for (Object m : e.getErrorCollector().getErrors()) {
                if (m instanceof SyntaxErrorMessage s) msgs.add(s.getCause().getMessage().replaceAll("\\s+", " "));
                else if (m instanceof Message) msgs.add(m.toString());
                else if (m instanceof Throwable t) msgs.add(t.getMessage());
            }
            throw new CompileException(String.join("; ", msgs));
        } catch (CompilationFailedException e) {
            throw new CompileException(String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- stage 1: before resolution

    private static final class EarlyCheck extends CompilationCustomizer {
        EarlyCheck() { super(CompilePhase.CONVERSION); }

        @Override
        public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
            ModuleNode m = source.getAST();
            if (m.getClasses().size() != 1) throw fail(source, "declaring classes, interfaces or enums is not allowed");
            // an import only makes a name resolvable; every use is checked again. Only allowlisted classes / packages may be imported.
            if (!m.getStaticImports().isEmpty() || !m.getStaticStarImports().isEmpty()) throw fail(source, "static imports are not allowed");
            for (org.codehaus.groovy.ast.ImportNode i : m.getImports()) {
                if (!SandboxPolicy.isAllowedType(i.getClassName())) throw fail(source, "import " + i.getClassName() + " is not allowed (allowed: " + SandboxPolicy.importHint() + ")");
            }
            for (org.codehaus.groovy.ast.ImportNode i : m.getStarImports()) {
                String pkg = i.getPackageName().replaceAll("\\.$", "");
                if (!SandboxPolicy.IMPORT_PACKAGES.contains(pkg)) throw fail(source, "import " + i.getPackageName() + "* is not allowed (allowed: " + SandboxPolicy.importHint() + ")");
            }
            new ClassCodeVisitorSupport() {
                @Override protected SourceUnit getSourceUnit() { return source; }

                @Override
                public void visitAnnotations(org.codehaus.groovy.ast.AnnotatedNode node) {
                    if (!node.getAnnotations().isEmpty()) throw fail(source, "annotations are not allowed");
                    super.visitAnnotations(node);
                }
            }.visitClass(classNode);
            for (MethodNode mn : classNode.getMethods()) {
                for (AnnotationNode a : mn.getAnnotations()) throw fail(source, "annotations are not allowed (" + a.getClassNode().getName() + ")");
            }
        }
    }

    private static MultipleCompilationErrorsException fail(SourceUnit source, String msg) {
        source.addError(new org.codehaus.groovy.syntax.SyntaxException(msg, 0, 0));
        return new MultipleCompilationErrorsException(source.getErrorCollector());
    }

    // ---------------------------------------------------------------- stage 2: allowlist over the resolved tree

    private static final class AllowlistCheck extends CompilationCustomizer {
        private final Set<String> userMethods;

        AllowlistCheck(Set<String> userMethods) {
            super(CompilePhase.CANONICALIZATION);
            this.userMethods = userMethods;
        }

        @Override
        public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
            if (!classNode.isScript()) return;
            for (MethodNode mn : classNode.getMethods()) {
                if (!mn.getName().equals("run") && !mn.getName().equals("main") && !mn.isSynthetic()) userMethods.add(mn.getName());
            }
            Visitor v = new Visitor(source, userMethods);
            for (MethodNode mn : classNode.getMethods()) {
                if (mn.getName().equals("main") && mn.isStatic() && mn.getLineNumber() < 0) continue;
                v.visitMethod(mn);
            }   // constructors are generated, not author code
        }
    }

    private static final class Visitor extends ClassCodeVisitorSupport {
        private final SourceUnit source;
        private final Set<String> userMethods;

        Visitor(SourceUnit source, Set<String> userMethods) {
            this.source = source;
            this.userMethods = userMethods;
        }

        @Override protected SourceUnit getSourceUnit() { return source; }

        private RuntimeException no(org.codehaus.groovy.ast.ASTNode n, String what) {
            return fail(source, what + " is not allowed (line " + n.getLineNumber() + ")");
        }

        private void type(org.codehaus.groovy.ast.ASTNode at, ClassNode t) {
            while (t.isArray()) t = t.getComponentType();
            if (org.codehaus.groovy.ast.ClassHelper.isPrimitiveType(t)) return;
            if (!SandboxPolicy.isAllowedType(t.getName())) throw no(at, "type " + t.getName());
        }

        @Override
        public void visitMethod(MethodNode node) {
            for (Parameter p : node.getParameters()) type(node, p.getOriginType());
            type(node, node.getReturnType());
            super.visitMethod(node);
        }

        @Override
        public void visitClassExpression(ClassExpression e) {
            type(e, e.getType());
        }

        @Override
        public void visitCastExpression(CastExpression e) {
            type(e, e.getType());
            super.visitCastExpression(e);
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression e) {
            if (!e.isMultipleAssignmentDeclaration()) type(e, e.getVariableExpression().getOriginType());
            super.visitDeclarationExpression(e);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression e) {
            if (e.isSpecialCall()) throw no(e, "this()/super()");
            if (!SandboxPolicy.NEW_TYPES.contains(e.getType().getName())) throw no(e, "new " + e.getType().getName());
            super.visitConstructorCallExpression(e);
        }

        @Override
        public void visitArrayExpression(ArrayExpression e) {
            throw no(e, "creating arrays");
        }

        @Override
        public void visitAttributeExpression(AttributeExpression e) {
            throw no(e, "direct field access (.@)");
        }

        @Override
        public void visitMethodPointerExpression(MethodPointerExpression e) {
            throw no(e, "method pointers (.&)");
        }

        @Override
        public void visitMethodReferenceExpression(MethodReferenceExpression e) {
            throw no(e, "method references (::)");
        }

        @Override
        public void visitVariableExpression(VariableExpression e) {
            if (e.isThisExpression() || e.isSuperExpression()) throw no(e, "this/super");
            if (e.getAccessedVariable() instanceof org.codehaus.groovy.ast.DynamicVariable && SandboxPolicy.DENIED_VARIABLES.contains(e.getName())) {
                throw no(e, "variable " + e.getName());
            }
        }

        @Override
        public void visitPropertyExpression(PropertyExpression e) {
            if (!(e.getProperty() instanceof ConstantExpression c) || !(c.getValue() instanceof String)) {
                throw no(e, "a computed property name");
            }
            if (SandboxPolicy.NEVER_PROPERTIES.contains(c.getValue())) throw no(e, "property " + c.getValue());
            super.visitPropertyExpression(e);
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression e) {
            if (!SandboxPolicy.isAllowedStatic(e.getOwnerType().getName()) || !SandboxPolicy.METHODS.contains(e.getMethod())) {
                throw no(e, "calling " + e.getOwnerType().getName() + "." + e.getMethod());
            }
            super.visitStaticMethodCallExpression(e);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression e) {
            if (!(e.getMethod() instanceof ConstantExpression c) || !(c.getValue() instanceof String name)) {
                throw no(e, "a computed method name");
            }
            if (e.getObjectExpression() instanceof ClassExpression ce && !SandboxPolicy.isAllowedStatic(ce.getType().getName())) {
                throw no(e, "calling " + ce.getType().getName() + "." + name);
            }
            if (e.isImplicitThis()) {
                if (!userMethods.contains(name) && !SandboxPolicy.METHODS.contains(name)) throw no(e, "calling " + name + "()");
                e.getArguments().visit(this);
                return;
            }
            if (!SandboxPolicy.METHODS.contains(name) && !userMethods.contains(name) && !SandboxApiNames.has(name)) {
                throw no(e, "calling ." + name + "()");
            }
            super.visitMethodCallExpression(e);
        }
    }
}
