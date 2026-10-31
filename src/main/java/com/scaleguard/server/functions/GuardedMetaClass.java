package com.scaleguard.server.functions;

import groovy.lang.GroovySystem;
import groovy.lang.MetaClass;
import groovy.lang.MetaClassImpl;
import groovy.lang.MetaClassRegistry;
import org.codehaus.groovy.runtime.callsite.CallSite;
import org.codehaus.groovy.runtime.callsite.MetaClassConstructorSite;
import org.codehaus.groovy.runtime.callsite.PogoMetaClassSite;
import org.codehaus.groovy.runtime.callsite.PojoMetaClassSite;
import org.codehaus.groovy.runtime.callsite.StaticMetaClassSite;

/**
 * Every dynamic dispatch of sandboxed code (method call, property, constructor, static call) is checked against
 * {@link SandboxPolicy} here. The call-site factories are overridden so Groovy cannot cache a direct MetaMethod
 * invocation that would skip the check. Groovy is only used for cloud functions in this process, so the creation
 * handle is installed JVM-wide.
 */
final class GuardedMetaClass extends MetaClassImpl {

    GuardedMetaClass(MetaClassRegistry registry, Class<?> theClass) {
        super(registry, theClass);
    }

    private static volatile boolean installed;

    static synchronized void install() {
        if (installed) return;
        GroovySystem.getMetaClassRegistry().setMetaClassCreationHandle(new MetaClassRegistry.MetaClassCreationHandle() {
            @Override
            protected MetaClass createNormalMetaClass(Class theClass, MetaClassRegistry registry) {
                return new GuardedMetaClass(registry, theClass);
            }
        });
        installed = true;
    }

    @Override
    public Object invokeMethod(Class sender, Object object, String name, Object[] args, boolean isCallToSuper, boolean fromInsideClass) {
        if (SandboxPolicy.active()) {
            SandboxPolicy.checkCall(object, theClass, name);
            SandboxPolicy.checkArgs(args);
            if (object instanceof java.net.URL u) {
                Object r = SandboxPolicy.urlCall(u, name);
                if (r != SandboxPolicy.NOT_HANDLED) return r;
            }
        }
        return super.invokeMethod(sender, object, name, args, isCallToSuper, fromInsideClass);
    }

    @Override
    public Object invokeStaticMethod(Object object, String name, Object[] args) {
        if (SandboxPolicy.active()) {
            SandboxPolicy.checkCall(object instanceof Class ? object : theClass, theClass, name);
            SandboxPolicy.checkArgs(args);
        }
        return super.invokeStaticMethod(object, name, args);
    }

    @Override
    public Object invokeConstructor(Object[] args) {
        if (SandboxPolicy.active()) SandboxPolicy.checkConstruct(theClass);
        return super.invokeConstructor(args);
    }

    @Override
    public Object getProperty(Class sender, Object object, String name, boolean isCallToSuper, boolean fromInsideClass) {
        if (SandboxPolicy.active()) {
            SandboxPolicy.checkProperty(object, theClass, name, false);
            if (object instanceof java.net.URL u) {
                Object r = SandboxPolicy.urlCall(u, name);
                if (r != SandboxPolicy.NOT_HANDLED) return r;
            }
        }
        return super.getProperty(sender, object, name, isCallToSuper, fromInsideClass);
    }

    @Override
    public void setProperty(Class sender, Object object, String name, Object value, boolean isCallToSuper, boolean fromInsideClass) {
        if (SandboxPolicy.active()) SandboxPolicy.checkProperty(object, theClass, name, true);
        super.setProperty(sender, object, name, value, isCallToSuper, fromInsideClass);
    }

    @Override
    public Object getAttribute(Class sender, Object receiver, String messageName, boolean useSuper) {
        if (SandboxPolicy.active()) throw SandboxPolicy.denied("field access");
        return super.getAttribute(sender, receiver, messageName, useSuper);
    }

    @Override
    public Object getAttribute(Class sender, Object receiver, String messageName, boolean useSuper, boolean fromInsideClass) {
        if (SandboxPolicy.active()) throw SandboxPolicy.denied("field access");
        return super.getAttribute(sender, receiver, messageName, useSuper, fromInsideClass);
    }

    @Override
    public void setAttribute(Class sender, Object receiver, String messageName, Object messageValue, boolean useSuper, boolean fromInsideClass) {
        if (SandboxPolicy.active()) throw SandboxPolicy.denied("field access");
    }

    @Override
    public CallSite createPojoCallSite(CallSite site, Object receiver, Object[] args) {
        return new PojoMetaClassSite(site, this);
    }

    @Override
    public CallSite createStaticSite(CallSite site, Object[] args) {
        return new StaticMetaClassSite(site, this);
    }

    @Override
    public CallSite createPogoCallSite(CallSite site, Object[] args) {
        return new PogoMetaClassSite(site, this);
    }

    @Override
    public CallSite createPogoCallCurrentSite(CallSite site, Class sender, Object[] args) {
        return new PogoMetaClassSite(site, this);
    }

    @Override
    public CallSite createConstructorSite(CallSite site, Object[] args) {
        return new MetaClassConstructorSite(site, this);
    }
}
