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
package org.quickperf.testng.spi;

import org.quickperf.config.PropertyResolver;
import org.quickperf.config.SystemPropertyResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Singleton, {@link ServiceLoader}-backed loader of
 * {@link PropertyResolverProvider} implementations for the TestNG path.
 *
 * <p>Mirrors the JUnit 5 loader (see
 * {@code QuickPerfTestExtension.loadPropertyResolverProviders}). The
 * {@code ServiceLoader} walk is performed once per classloader at static
 * initialization time and then cached.
 */
public final class TestNGPropertyResolverLoader {

    public static final TestNGPropertyResolverLoader INSTANCE = new TestNGPropertyResolverLoader();

    private final List<PropertyResolverProvider> providers;

    private TestNGPropertyResolverLoader() {
        this.providers = loadProviders();
    }

    private static List<PropertyResolverProvider> loadProviders() {
        List<PropertyResolverProvider> result = new ArrayList<PropertyResolverProvider>();
        try {
            ServiceLoader<PropertyResolverProvider> loader =
                    ServiceLoader.load(PropertyResolverProvider.class);
            for (Iterator<PropertyResolverProvider> it = loader.iterator(); it.hasNext(); ) {
                result.add(it.next());
            }
        } catch (Throwable t) {
            // SPI loading must never break TestNG dispatch.
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Iterate registered providers in {@link ServiceLoader} order. The first
     * provider that returns a non-{@code null} resolver wins. Falls back to
     * {@link SystemPropertyResolver#INSTANCE} when no provider applies.
     *
     * @param testInstance the TestNG test instance (may be {@code null})
     * @param testMethod   the reflective test method (may be {@code null})
     * @return a resolver, never {@code null}
     */
    public PropertyResolver build(Object testInstance, Method testMethod) {
        for (PropertyResolverProvider provider : providers) {
            try {
                PropertyResolver resolver = provider.tryBuild(testInstance, testMethod);
                if (resolver != null) {
                    return resolver;
                }
            } catch (Throwable t) {
                // Provider failure is non-fatal: try the next provider.
            }
        }
        return SystemPropertyResolver.INSTANCE;
    }

}
