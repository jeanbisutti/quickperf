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
package org.quickperf.spring;

import org.junit.Test;
import org.junit.experimental.results.PrintableResult;
import org.junit.runner.RunWith;
import org.quickperf.jvm.JVM;
import org.quickperf.jvm.annotations.ExpectNoHeapAllocation;
import org.quickperf.spring.junit4.QuickPerfSpringRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.experimental.results.PrintableResult.testResult;

/**
 * Regression test for bug_001: the spring3 {@link QuickPerfSpringRunner} used
 * to call {@code TestExecutionContext.buildNewJvmFrom(quickPerfConfigs, testMethod)}
 * (the 2-arg overload) on the fork-parent path, silently ignoring properties
 * declared via {@code @SpringBootTest(properties = ...)} while spring4 and
 * spring5 honored them.
 *
 * <p>The fixture below combines {@code @SpringBootTest(properties =
 * "disableQuickPerf=true")} with {@code @ExpectNoHeapAllocation} (which
 * forces a forked JVM) and a method that deliberately allocates. Without the
 * fix QuickPerf forks the JVM and the {@code @ExpectNoHeapAllocation}
 * assertion fails. With the fix the runner detects the disable directive via
 * the property resolver, routes the test through the plain Spring runner and
 * the test passes unmodified.
 */
public class JUnit4Spring3DisableQuickPerfPropertyTest {

    private final boolean testEnabled = JVM.INSTANCE.version.isLessThanTo16();

    private static class TestApplicationContextInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext ac) {
            // No-op initializer kept here to mirror the existing JUnit 4 spring3 fixtures.
            Object object = new Object();
        }

    }

    @RunWith(QuickPerfSpringRunner.class)
    @SpringBootTest(properties = {"disableQuickPerf=true"})
    @ContextConfiguration(initializers = TestApplicationContextInitializer.class)
    public static class ClassWithSpringBootTestDisableQuickPerfAndForkingAnnotation {

        @ExpectNoHeapAllocation
        @Test
        public void a_test_method_allocating() {
            Object object = new Object();
        }

    }

    @Test
    public void
    spring_boot_test_properties_should_disable_quick_perf_even_when_a_forking_annotation_is_present() {

        if (!testEnabled) {
            return;
        }

        Class<?> testClass = ClassWithSpringBootTestDisableQuickPerfAndForkingAnnotation.class;

        PrintableResult printableResult = testResult(testClass);

        assertThat(printableResult.failureCount()).isZero();
    }

}
