/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Copyright 2019-2022 the original author or authors.
 */
package org.quickperf.spring.boot.r2dbc;

import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.callback.ProxyConfig;
import io.r2dbc.proxy.callback.QuickPerfProxyFactoryFactory;
import io.r2dbc.spi.Closeable;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Wrapped;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.quickperf.sql.r2dbc.R2dbcConnectionLifecycleListener;
import org.quickperf.sql.r2dbc.R2dbcQuickPerfListener;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.PriorityOrdered;
import reactor.core.Disposable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BeanPostProcessor that wraps every {@link ConnectionFactory} bean with a two-layer proxy stack:
 *
 * <ul>
 *   <li><b>Layer 1 (outer Spring AOP CGLIB subclass):</b> preserves the bean's runtime type so that
 *       user code performing {@code instanceof ConnectionPool}, {@code ((ConnectionPool) bean).getMetrics()},
 *       or similar concrete-type dispatch keeps working. The outer interceptor only intercepts the
 *       no-arg {@code create()} call (where r2dbc-proxy listeners need to observe each emitted
 *       {@link io.r2dbc.spi.Connection}); every other method (including {@code getMetadata()},
 *       {@code dispose()}, {@code close()}, {@code unwrap()}, {@code isDisposed()}, plus
 *       pool-specific methods like {@code getMetrics()}) is delegated directly to the original
 *       target. Consumers that need access to the raw bean Spring originally created can cast to
 *       {@link org.springframework.aop.framework.Advised} and call
 *       {@code getTargetSource().getTarget()}.</li>
 *   <li><b>Layer 2 (inner r2dbc-proxy {@link ProxyConnectionFactory}):</b> attaches the QuickPerf
 *       {@link R2dbcQuickPerfListener}. r2dbc-proxy's {@code JdkProxyFactory.wrapConnection(...)}
 *       automatically wraps every emitted {@link io.r2dbc.spi.Connection}, dispatching
 *       {@code ProxyExecutionListener} events for per-connection method calls and queries.</li>
 * </ul>
 *
 * <p>If Spring AOP / CGLIB cannot subclass the target (e.g. the {@code ConnectionFactory} bean is a
 * {@code final} class), the BPP emits a warning and falls back to a JDK dynamic proxy that
 * implements {@link ConnectionFactory}, the {@link QuickPerfR2dbcProxyMarker} and — when the target
 * itself implements them — {@link Wrapped}, {@link Disposable} and {@link Closeable}.
 * {@code instanceof ConnectionPool} (or other concrete {@code ConnectionFactory} subclasses) returns
 * {@code false} on the fallback proxy — the warning makes this visible to the user.
 *
 * <p>Validation queries issued internally by {@code ConnectionPool} are <b>not</b> counted by
 * QuickPerf: validation runs against the inner ConnectionFactory wrapped by the pool, not against
 * our outer {@link ProxyConnectionFactory}, so it never traverses the {@link R2dbcQuickPerfListener}.
 *
 * <p>{@link PriorityOrdered} with {@code LOWEST_PRECEDENCE - 1} mirrors the JDBC starter's
 * {@code QuickPerfProxyBeanPostProcessor} so the wrap happens after Boot's own decoration (e.g.
 * r2dbc-pool) but before consumers receive the bean.
 */
public class QuickPerfR2dbcProxyBeanPostProcessor implements BeanPostProcessor, PriorityOrdered {

    private static final Logger LOGGER = Logger.getLogger(QuickPerfR2dbcProxyBeanPostProcessor.class.getName());

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof ConnectionFactory)) {
            return bean;
        }
        if (bean instanceof QuickPerfR2dbcProxyMarker) {
            return bean;
        }

        ConnectionFactory target = (ConnectionFactory) bean;
        ProxyConfig proxyConfig = ProxyConfig.builder()
                .proxyFactoryFactory(new QuickPerfProxyFactoryFactory())
                .build();
        proxyConfig.addListener(new R2dbcQuickPerfListener(beanName));
        proxyConfig.addListener(new R2dbcConnectionLifecycleListener());
        ConnectionFactory r2dbcProxiedFactory = ProxyConnectionFactory.builder(target)
                .proxyConfig(proxyConfig)
                .build();

        Class<?> targetClass = target.getClass();
        if (canSubclass(targetClass)) {
            try {
                return wrapWithSpringAopCglib(target, r2dbcProxiedFactory);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING,
                        "QuickPerf: target ConnectionFactory " + targetClass.getName()
                                + " could not be CGLIB-subclassed; falling back to a JDK proxy. "
                                + "'instanceof " + targetClass.getSimpleName() + "' will return false in user code.",
                        t);
            }
        } else {
            LOGGER.warning("QuickPerf: target ConnectionFactory " + targetClass.getName()
                    + " is final and cannot be CGLIB-subclassed; falling back to a JDK proxy. "
                    + "'instanceof " + targetClass.getSimpleName() + "' will return false in user code.");
        }
        return wrapWithJdkProxy(target, r2dbcProxiedFactory);
    }

    @Override
    public int getOrder() {
        return PriorityOrdered.LOWEST_PRECEDENCE - 1;
    }

    private static boolean canSubclass(Class<?> targetClass) {
        return !Modifier.isFinal(targetClass.getModifiers());
    }

    private static Object wrapWithSpringAopCglib(ConnectionFactory target, ConnectionFactory r2dbcProxiedFactory) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addInterface(QuickPerfR2dbcProxyMarker.class);
        proxyFactory.addAdvice(new OuterCreateInterceptor(r2dbcProxiedFactory));
        return proxyFactory.getProxy(QuickPerfR2dbcProxyBeanPostProcessor.class.getClassLoader());
    }

    private static Object wrapWithJdkProxy(ConnectionFactory target, ConnectionFactory r2dbcProxiedFactory) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        interfaces.add(ConnectionFactory.class);
        interfaces.add(QuickPerfR2dbcProxyMarker.class);
        if (target instanceof Wrapped) {
            interfaces.add(Wrapped.class);
        }
        if (target instanceof Disposable) {
            interfaces.add(Disposable.class);
        }
        if (target instanceof Closeable) {
            interfaces.add(Closeable.class);
        }
        return Proxy.newProxyInstance(
                QuickPerfR2dbcProxyBeanPostProcessor.class.getClassLoader(),
                interfaces.toArray(new Class<?>[0]),
                new JdkProxyConnectionFactoryInterceptor(target, r2dbcProxiedFactory));
    }

    /**
     * Outer Spring AOP advice that intercepts the no-arg {@code create()} call so that emitted
     * {@link io.r2dbc.spi.Connection} instances are wrapped by r2dbc-proxy and visible to
     * {@link io.r2dbc.proxy.listener.ProxyExecutionListener} listeners. Every other method call
     * proceeds against the original target, preserving bean type, metadata, and lifecycle
     * semantics.
     */
    static final class OuterCreateInterceptor implements MethodInterceptor {

        private final ConnectionFactory r2dbcProxiedFactory;

        OuterCreateInterceptor(ConnectionFactory r2dbcProxiedFactory) {
            this.r2dbcProxiedFactory = r2dbcProxiedFactory;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            if (isNoArgCreate(method)) {
                return r2dbcProxiedFactory.create();
            }
            return invocation.proceed();
        }

        private static boolean isNoArgCreate(Method method) {
            return "create".equals(method.getName()) && method.getParameterCount() == 0;
        }
    }

    /**
     * JDK dynamic proxy invocation handler used as a fallback when Spring AOP / CGLIB cannot
     * subclass the target {@link ConnectionFactory}. Forwards {@code create()} to the r2dbc-proxy
     * wrapped factory and every other method to the original target so that {@code dispose()},
     * {@code close()} and pool metadata accessors keep working.
     */
    static final class JdkProxyConnectionFactoryInterceptor implements InvocationHandler {

        private final ConnectionFactory target;
        private final ConnectionFactory r2dbcProxiedFactory;

        JdkProxyConnectionFactoryInterceptor(ConnectionFactory target, ConnectionFactory r2dbcProxiedFactory) {
            this.target = target;
            this.r2dbcProxiedFactory = r2dbcProxiedFactory;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("create".equals(method.getName()) && method.getParameterCount() == 0) {
                return r2dbcProxiedFactory.create();
            }
            return method.invoke(target, args);
        }
    }

}
