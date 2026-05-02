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
package org.quickperf.spring.junit5;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.quickperf.config.PropertyResolver;
import org.quickperf.config.SystemPropertyResolver;
import org.quickperf.junit5.spi.PropertyResolverProvider;

/**
 * {@link PropertyResolverProvider} implementation that, when the
 * Spring {@code SpringExtension} is loaded for the test, returns a
 * resolver backed by the test's Spring {@code Environment}. This
 * exposes properties from {@code @SpringBootTest(properties = ...)},
 * {@code application.properties}, {@code application.yml}, etc.
 *
 * <p>If the Spring extension is not present or the application context
 * cannot be obtained, this provider returns {@code null} so that the
 * caller falls back to {@link SystemPropertyResolver#INSTANCE}.
 *
 * <p>All access to Spring types is done via reflection so that this
 * module compiles against {@code spring-test} without depending on
 * {@code spring-context} at compile time.
 */
public class SpringPropertyResolverProvider implements PropertyResolverProvider {

    private static final String SPRING_EXTENSION_CLASS = "org.springframework.test.context.junit.jupiter.SpringExtension";

    @Override
    public PropertyResolver tryBuild(ExtensionContext extensionContext) {
        if (extensionContext == null) {
            return null;
        }
        try {
            Class<?> springExtensionClass = Class.forName(SPRING_EXTENSION_CLASS);
            java.lang.reflect.Method getApplicationContextMethod
                    = springExtensionClass.getMethod("getApplicationContext", ExtensionContext.class);
            Object applicationContext = getApplicationContextMethod.invoke(null, extensionContext);
            if (applicationContext == null) {
                return null;
            }
            final Object environment = applicationContext.getClass().getMethod("getEnvironment").invoke(applicationContext);
            if (environment == null) {
                return null;
            }
            final java.lang.reflect.Method getPropertyMethod = environment.getClass().getMethod("getProperty", String.class);
            return new PropertyResolver() {
                @Override
                public String resolve(String propertyName) {
                    try {
                        return (String) getPropertyMethod.invoke(environment, propertyName);
                    } catch (Throwable t) {
                        return null;
                    }
                }
            };
        } catch (Throwable t) {
            return null;
        }
    }

}
