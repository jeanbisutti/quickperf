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
package org.quickperf.spring.testng;

import org.quickperf.config.PropertyResolver;
import org.quickperf.testng.spi.PropertyResolverProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * {@link PropertyResolverProvider} that reads QuickPerf properties from the
 * Spring {@code Environment} of a TestNG test instance extending
 * {@code AbstractTestNGSpringContextTests}.
 *
 * <p>All access to Spring types is reflective so {@code spring-test} is a
 * compile-time {@code provided} dependency only.
 *
 * <p>This provider returns {@code null} (caller falls back to
 * {@link org.quickperf.config.SystemPropertyResolver}) when:
 * <ul>
 *   <li>the test instance does not have a {@code testContextManager} field
 *       (i.e. does not extend {@code AbstractTestNGSpringContextTests});</li>
 *   <li>the application context is not yet loaded (e.g. during
 *       {@code @BeforeClass(alwaysRun=true)}); or</li>
 *   <li>any reflective step throws.</li>
 * </ul>
 */
public class SpringTestNGPropertyResolverProvider implements PropertyResolverProvider {

    private static final String TEST_CONTEXT_MANAGER_FIELD = "testContextManager";

    @Override
    public PropertyResolver tryBuild(Object testInstance, Method testMethod) {
        if (testInstance == null) {
            return null;
        }
        try {
            Field tcmField = findFieldUpHierarchy(testInstance.getClass(), TEST_CONTEXT_MANAGER_FIELD);
            if (tcmField == null) {
                return null;
            }
            tcmField.setAccessible(true);
            Object testContextManager = tcmField.get(testInstance);
            if (testContextManager == null) {
                return null;
            }
            Object testContext = testContextManager.getClass().getMethod("getTestContext").invoke(testContextManager);
            if (testContext == null) {
                return null;
            }
            Object applicationContext = testContext.getClass().getMethod("getApplicationContext").invoke(testContext);
            if (applicationContext == null) {
                return null;
            }
            final Object environment = applicationContext.getClass().getMethod("getEnvironment").invoke(applicationContext);
            if (environment == null) {
                return null;
            }
            final Method getPropertyMethod = environment.getClass().getMethod("getProperty", String.class);
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

    private static Field findFieldUpHierarchy(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

}
