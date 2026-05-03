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

import org.junit.jupiter.api.Test;
import org.quickperf.junit5.JUnit5Tests;
import org.quickperf.junit5.JUnit5Tests.JUnit5TestsResult;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot3JUnit5DisableQuickPerfWithApplicationProperties;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot3JUnit5DisableQuickPerfWithApplicationPropertiesForkedJvm;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot3JUnit5DisableQuickPerfWithApplicationYml;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot3JUnit5DisableQuickPerfWithApplicationYmlForkedJvm;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot3JUnit5DisableQuickPerfWithSpringBootTestProperties;
import org.quickperf.spring.springboottest.disablequickperf.SpringBoot3JUnit5DisableQuickPerfWithSpringBootTestPropertiesForkedJvm;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests guarding the JUnit 5 analogues of bug_003: when a test
 * declares {@code @SpringBootTest(properties = "disableQuickPerf=true")} or
 * loads {@code disableQuickPerf=true} via {@code application.properties} /
 * {@code application.yml}, QuickPerf must skip its evaluation and let the
 * test body run unmodified — even when the test also carries an annotation
 * that would normally fork the JVM (such as
 * {@link org.quickperf.jvm.annotations.HeapSize}).
 */
public class SpringBoot3JUnit5DisableQuickPerfTest {

    @Test
    void should_disable_quick_perf_when_property_defined_in_application_properties() {

        Class<?> testClass = SpringBoot3JUnit5DisableQuickPerfWithApplicationProperties.class;

        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        assertThat(jUnit5TestsResult.getNumberOfFailures()).isZero();
    }

    @Test
    void should_disable_quick_perf_when_property_defined_in_application_yml() {

        Class<?> testClass = SpringBoot3JUnit5DisableQuickPerfWithApplicationYml.class;

        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        assertThat(jUnit5TestsResult.getNumberOfFailures()).isZero();
    }

    @Test
    void should_disable_quick_perf_when_property_defined_in_spring_boot_test_properties() {

        Class<?> testClass = SpringBoot3JUnit5DisableQuickPerfWithSpringBootTestProperties.class;

        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        assertThat(jUnit5TestsResult.getNumberOfFailures()).isZero();
    }

    @Test
    void should_disable_quick_perf_when_property_defined_in_application_properties_with_forked_jvm() {

        Class<?> testClass = SpringBoot3JUnit5DisableQuickPerfWithApplicationPropertiesForkedJvm.class;

        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        assertThat(jUnit5TestsResult.getNumberOfFailures()).isZero();
    }

    @Test
    void should_disable_quick_perf_when_property_defined_in_application_yml_with_forked_jvm() {

        Class<?> testClass = SpringBoot3JUnit5DisableQuickPerfWithApplicationYmlForkedJvm.class;

        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        assertThat(jUnit5TestsResult.getNumberOfFailures()).isZero();
    }

    @Test
    void should_disable_quick_perf_when_property_defined_in_spring_boot_test_properties_with_forked_jvm() {

        Class<?> testClass = SpringBoot3JUnit5DisableQuickPerfWithSpringBootTestPropertiesForkedJvm.class;

        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        assertThat(jUnit5TestsResult.getNumberOfFailures()).isZero();
    }

}
