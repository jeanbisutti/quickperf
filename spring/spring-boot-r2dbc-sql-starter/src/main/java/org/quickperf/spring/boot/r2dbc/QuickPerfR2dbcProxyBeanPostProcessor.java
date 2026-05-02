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
import io.r2dbc.spi.ConnectionFactory;
import org.quickperf.sql.r2dbc.R2dbcQuickPerfListener;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.PriorityOrdered;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * BeanPostProcessor that wraps every {@link ConnectionFactory} bean with a JDK
 * dynamic proxy delegating all calls to a r2dbc-proxy
 * {@link ProxyConnectionFactory} which has QuickPerf's
 * {@link R2dbcQuickPerfListener} attached.
 *
 * <p>A JDK dynamic proxy (rather than CGLIB / class subclassing) is used because
 * {@code ConnectionFactory} is an interface in r2dbc-spi, and Boot's
 * {@code R2dbcAutoConfiguration} returns instances whose concrete classes are
 * not subclassable (e.g. driver-private, sealed in the case of pool wrappers).
 *
 * <p>{@link PriorityOrdered} with {@code LOWEST_PRECEDENCE - 1} mirrors the
 * JDBC starter's {@code QuickPerfProxyBeanPostProcessor} so the wrap happens
 * after Boot's own decoration (e.g. r2dbc-pool) but before consumers receive
 * the bean.
 */
public class QuickPerfR2dbcProxyBeanPostProcessor implements BeanPostProcessor, PriorityOrdered {

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
        ConnectionFactory original = (ConnectionFactory) bean;
        ConnectionFactory proxiedFactory = ProxyConnectionFactory.builder(original)
                .listener(new R2dbcQuickPerfListener(beanName))
                .build();
        return Proxy.newProxyInstance(
                bean.getClass().getClassLoader(),
                new Class<?>[]{ ConnectionFactory.class, QuickPerfR2dbcProxyMarker.class },
                new ProxyConnectionFactoryInterceptor(proxiedFactory));
    }

    @Override
    public int getOrder() {
        return PriorityOrdered.LOWEST_PRECEDENCE - 1;
    }

    /**
     * JDK dynamic proxy invocation handler that forwards every interface call to
     * the r2dbc-proxy-wrapped {@link ConnectionFactory}.
     *
     * <p>Visible-for-testing.
     */
    static final class ProxyConnectionFactoryInterceptor implements InvocationHandler {

        private final ConnectionFactory proxiedFactory;

        ProxyConnectionFactoryInterceptor(ConnectionFactory proxiedFactory) {
            this.proxiedFactory = proxiedFactory;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Forward Object methods (equals/hashCode/toString) to the proxiedFactory too,
            // so identity comparisons against the bean are stable.
            return method.invoke(proxiedFactory, args);
        }
    }

}
