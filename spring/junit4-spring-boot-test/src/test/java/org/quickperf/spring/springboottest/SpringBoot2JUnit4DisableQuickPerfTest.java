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
package org.quickperf.spring.springboottest;

import org.junit.Test;
import org.junit.experimental.results.PrintableResult;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot2JUnit4DisableQuickPerfWithApplicationProperties;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot2JUnit4DisableQuickPerfWithApplicationPropertiesForkedJvm;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot2JUnit4DisableQuickPerfWithApplicationYml;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot2JUnit4DisableQuickPerfWithApplicationYmlForkedJvm;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot2JUnit4DisableQuickPerfWithSpringBootTestProperties;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot2JUnit4DisableQuickPerfWithSpringBootTestPropertiesForkedJvm;

import static org.assertj.core.api.Assertions.assertThat;

public class SpringBoot2JUnit4DisableQuickPerfTest {

    @Test
    public void should_disable_quick_perf_when_property_defined_in_application_properties() {

        // GIVEN
        Class<?> testClass = SpringBoot2JUnit4DisableQuickPerfWithApplicationProperties.class;

        // WHEN
        PrintableResult printableResult = PrintableResult.testResult(testClass);

        // THEN
        assertThat(printableResult.failureCount()).isZero();
    }

    @Test
    public void should_disable_quick_perf_when_property_defined_in_application_yml() {

        // GIVEN
        Class<?> testClass = SpringBoot2JUnit4DisableQuickPerfWithApplicationYml.class;

        // WHEN
        PrintableResult printableResult = PrintableResult.testResult(testClass);

        // THEN
        assertThat(printableResult.failureCount()).isZero();
    }

    @Test
    public void should_disable_quick_perf_when_property_defined_in_spring_boot_test_properties() {

        // GIVEN
        Class<?> testClass = SpringBoot2JUnit4DisableQuickPerfWithSpringBootTestProperties.class;

        // WHEN
        PrintableResult printableResult = PrintableResult.testResult(testClass);

        // THEN
        assertThat(printableResult.failureCount()).isZero();
    }

    @Test
    public void should_disable_quick_perf_when_property_defined_in_application_properties_with_forked_jvm() {

        // GIVEN
        Class<?> testClass = SpringBoot2JUnit4DisableQuickPerfWithApplicationPropertiesForkedJvm.class;

        // WHEN
        PrintableResult printableResult = PrintableResult.testResult(testClass);

        // THEN
        assertThat(printableResult.failureCount()).isZero();
    }

    @Test
    public void should_disable_quick_perf_when_property_defined_in_application_yml_with_forked_jvm() {

        // GIVEN
        Class<?> testClass = SpringBoot2JUnit4DisableQuickPerfWithApplicationYmlForkedJvm.class;

        // WHEN
        PrintableResult printableResult = PrintableResult.testResult(testClass);

        // THEN
        assertThat(printableResult.failureCount()).isZero();
    }

    /**
     * Reproducer for bug_003: with {@code @SpringBootTest(properties = "disableQuickPerf=true")}
     * combined with a JVM-forking annotation such as {@code @HeapSize}, the fork-parent
     * resolver detects {@code disableQuickPerf=true} and {@link org.quickperf.TestExecutionContext#buildFrom}
     * early-returns leaving {@code perfAnnotations = null}. The runner-level
     * {@code testMethodToBeLaunchedInASpecificJvm} flag remains {@code true} so
     * {@code MainJvmAfterJUnitStatement} runs and {@code PerfIssuesEvaluator} NPEs while
     * iterating the null array. The fix in {@code QuickPerfSpringRunner#runChild}
     * detects the property-based disable up front and routes through the plain
     * Spring runner without forking.
     */
    @Test
    public void should_disable_quick_perf_when_property_defined_in_spring_boot_test_properties_with_forked_jvm() {

        // GIVEN
        Class<?> testClass = SpringBoot2JUnit4DisableQuickPerfWithSpringBootTestPropertiesForkedJvm.class;

        // WHEN
        PrintableResult printableResult = PrintableResult.testResult(testClass);

        // THEN
        assertThat(printableResult.failureCount()).isZero();
    }

}
