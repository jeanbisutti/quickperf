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
package org.quickperf.testng.sql;

import org.quickperf.config.PropertyResolver;
import org.quickperf.config.SystemPropertyResolver;
import org.quickperf.testng.spi.TestNGPropertyResolverLoader;
import org.testng.annotations.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the no-Spring fallback for the SQL-listener's classpath: when no
 * {@code PropertyResolverProvider} is registered via {@code META-INF/services},
 * {@link TestNGPropertyResolverLoader} returns {@link SystemPropertyResolver#INSTANCE}.
 *
 * <p>This pins the SQL-listener's plain-TestNG behavior introduced by the
 * v2 {@code testng-spi} wiring &mdash; behavior must match the pre-v2 path.
 */
public class TestNGPropertyResolverLoaderFallbackTest {

    @Test
    public void no_provider_registered_returns_system_property_resolver() throws Exception {
        // GIVEN a plain test instance and method (no Spring on this module's test classpath).
        Object plainInstance = new Object();
        Method plainMethod = TestNGPropertyResolverLoaderFallbackTest.class
                .getDeclaredMethod("no_provider_registered_returns_system_property_resolver");

        // WHEN
        PropertyResolver resolver =
                TestNGPropertyResolverLoader.INSTANCE.build(plainInstance, plainMethod);

        // THEN the loader falls back to the system-property resolver.
        assertThat(resolver).isSameAs(SystemPropertyResolver.INSTANCE);
    }

}
