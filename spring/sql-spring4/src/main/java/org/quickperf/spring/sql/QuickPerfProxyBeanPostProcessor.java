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
package org.quickperf.spring.sql;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.quickperf.sql.config.QuickPerfSqlDataSourceBuilder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ReflectionUtils;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;


// Inspiration from https://blog.arnoldgalovics.com/configuring-a-datasource-proxy-in-spring-boot/
// and https://github.com/gavlyukovskiy/spring-boot-data-source-decorator

// Implements PriorityOrdered (not Ordered) so this BeanPostProcessor is registered before any
// Ordered BeanPostProcessor that depends on the DataSource. Spring instantiates PriorityOrdered
// bean post-processors first.
public class QuickPerfProxyBeanPostProcessor implements BeanPostProcessor, PriorityOrdered {

    private static final Logger LOGGER = Logger.getLogger(QuickPerfProxyBeanPostProcessor.class.getName());

    private static volatile boolean reflectionWarningLogged = false;

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource && !ScopedProxyUtils.isScopedTarget(beanName)) {
            final ProxyFactory factory = new ProxyFactory(bean);
            factory.setProxyTargetClass(true);
            factory.addAdvice(new ProxyDataSourceInterceptor((DataSource) bean));
            return factory.getProxy();
        }
        if (bean instanceof ThreadPoolTaskExecutor) {
            installQuickPerfTaskDecorator((ThreadPoolTaskExecutor) bean);
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return PriorityOrdered.LOWEST_PRECEDENCE - 1;
    }

    /**
     * Installs (or composes onto) a {@link QuickPerfTaskDecorator} on the given executor so Spring
     * @Async tasks inherit QuickPerf's per-thread snapshot. Idempotent: re-running this on an
     * already-decorated executor is a no-op.
     */
    private static void installQuickPerfTaskDecorator(ThreadPoolTaskExecutor executor) {
        Field field;
        try {
            field = ThreadPoolTaskExecutor.class.getDeclaredField("taskDecorator");
        } catch (NoSuchFieldException e) {
            logReflectionFallbackOnce("ThreadPoolTaskExecutor.taskDecorator field not found; "
                    + "QuickPerf cannot detect existing task decorators.", e);
            executor.setTaskDecorator(new QuickPerfTaskDecorator());
            return;
        }
        field.setAccessible(true);
        Object existing;
        try {
            existing = field.get(executor);
        } catch (IllegalAccessException e) {
            logReflectionFallbackOnce("Cannot read ThreadPoolTaskExecutor.taskDecorator "
                    + "(SecurityManager?); QuickPerf may override an existing decorator.", e);
            executor.setTaskDecorator(new QuickPerfTaskDecorator());
            return;
        }
        if (existing instanceof QuickPerfTaskDecorator) {
            return;
        }
        if (existing == null) {
            executor.setTaskDecorator(new QuickPerfTaskDecorator());
        } else {
            executor.setTaskDecorator(new QuickPerfComposingTaskDecorator((TaskDecorator) existing));
        }
    }

    private static void logReflectionFallbackOnce(String message, Throwable t) {
        if (!reflectionWarningLogged) {
            reflectionWarningLogged = true;
            LOGGER.log(Level.WARNING, message + " Falling back to overriding any pre-existing decorator.", t);
        }
    }

    private static class ProxyDataSourceInterceptor implements MethodInterceptor {

        private final DataSource datasourceProxy;

        public ProxyDataSourceInterceptor(final DataSource dataSource) {
            this.datasourceProxy =
                    QuickPerfSqlDataSourceBuilder.aDataSourceBuilder()
                    .buildProxy(dataSource);
        }

        @Override
        public Object invoke(final MethodInvocation invocation) throws Throwable {
            Method proxyMethod = ReflectionUtils.findMethod( this.datasourceProxy.getClass()
                                                                  ,invocation.getMethod().getName());
            if (proxyMethod != null) {
                return proxyMethod.invoke(this.datasourceProxy, invocation.getArguments());
            }
            return invocation.proceed();
        }
    }

}
