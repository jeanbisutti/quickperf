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
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that {@link SpringTestNGPropertyResolverProvider} returns
 * {@code null} (instead of throwing) when invoked before the Spring
 * application context is loaded, e.g. during
 * {@code @BeforeClass(alwaysRun=true)}.
 *
 * <p>Reproduces the pre-context state by stubbing
 * {@code testContextManager.getTestContext().getApplicationContext()} to
 * return {@code null}.
 */
public class PreContextLoadSafetyTest {

    private static final SpringTestNGPropertyResolverProvider PROVIDER =
            new SpringTestNGPropertyResolverProvider();

    @Test
    public void returns_null_when_test_instance_is_null() {
        PropertyResolver resolver = PROVIDER.tryBuild(null, null);

        assertThat(resolver).isNull();
    }

    @Test
    public void returns_null_when_test_class_has_no_test_context_manager_field() {
        Object plainInstance = new Object();

        PropertyResolver resolver = PROVIDER.tryBuild(plainInstance, null);

        assertThat(resolver).isNull();
    }

    @Test
    public void returns_null_when_application_context_is_not_yet_loaded() {
        FakeTestNGTestInstance instance = new FakeTestNGTestInstance();
        instance.testContextManager = new FakeTestContextManagerWithoutContext();

        PropertyResolver resolver = PROVIDER.tryBuild(instance, null);

        assertThat(resolver).isNull();
    }

    @Test
    public void returns_null_when_test_context_manager_field_is_null() {
        FakeTestNGTestInstance instance = new FakeTestNGTestInstance();
        instance.testContextManager = null;

        PropertyResolver resolver = PROVIDER.tryBuild(instance, null);

        assertThat(resolver).isNull();
    }

    @Test
    public void resolves_property_when_application_context_is_loaded() {
        FakeTestNGTestInstance instance = new FakeTestNGTestInstance();
        instance.testContextManager = new FakeTestContextManagerWithEnvironment();

        PropertyResolver resolver = PROVIDER.tryBuild(instance, null);

        assertThat(resolver).isNotNull();
        assertThat(resolver.resolve("disableQuickPerf")).isEqualTo("true");
        assertThat(resolver.resolve("unknownProperty")).isNull();
    }

    @SuppressWarnings("unused")
    static class FakeTestNGTestInstance {
        Object testContextManager;
    }

    @SuppressWarnings("unused")
    public static class FakeTestContextManagerWithoutContext {
        public FakeTestContextWithoutAppContext getTestContext() {
            return new FakeTestContextWithoutAppContext();
        }
    }

    @SuppressWarnings("unused")
    public static class FakeTestContextWithoutAppContext {
        public Object getApplicationContext() {
            return null;
        }
    }

    @SuppressWarnings("unused")
    public static class FakeTestContextManagerWithEnvironment {
        public FakeTestContextWithAppContext getTestContext() {
            return new FakeTestContextWithAppContext();
        }
    }

    @SuppressWarnings("unused")
    public static class FakeTestContextWithAppContext {
        public FakeApplicationContext getApplicationContext() {
            return new FakeApplicationContext();
        }
    }

    @SuppressWarnings("unused")
    public static class FakeApplicationContext {
        public FakeEnvironment getEnvironment() {
            return new FakeEnvironment();
        }
    }

    @SuppressWarnings("unused")
    public static class FakeEnvironment {
        public String getProperty(String name) {
            if ("disableQuickPerf".equals(name)) {
                return "true";
            }
            return null;
        }
    }

}
