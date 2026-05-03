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
package org.quickperf.spring.testngspringboottest;

import org.quickperf.spring.testngspringboottest.disablequickperf.SpringBoot3TestNGDisableQuickPerfWithApplicationProperties;
import org.quickperf.spring.testngspringboottest.disablequickperf.SpringBoot3TestNGDisableQuickPerfWithApplicationYml;
import org.quickperf.spring.testngspringboottest.disablequickperf.SpringBoot3TestNGDisableQuickPerfWithSpringBootTestProperties;
import org.quickperf.testng.TestNGTests;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SpringBoot3TestNGDisableQuickPerfTest {

    @Test
    public void should_disable_quick_perf_when_property_defined_in_application_properties() {

        // GIVEN
        Class<?> testClass = SpringBoot3TestNGDisableQuickPerfWithApplicationProperties.class;
        TestNGTests testNGTests = TestNGTests.createInstance(testClass);

        // WHEN
        TestNGTests.TestNGTestsResult testsResult = testNGTests.run();

        // THEN
        assertThat(testsResult.getNumberOfPassedTest()).isOne();

    }

    @Test
    public void should_disable_quick_perf_when_property_defined_in_application_yml() {

        // GIVEN
        Class<?> testClass = SpringBoot3TestNGDisableQuickPerfWithApplicationYml.class;
        TestNGTests testNGTests = TestNGTests.createInstance(testClass);

        // WHEN
        TestNGTests.TestNGTestsResult testsResult = testNGTests.run();

        // THEN
        assertThat(testsResult.getNumberOfPassedTest()).isOne();

    }

    @Test
    public void should_disable_quick_perf_when_property_defined_in_spring_boot_test_properties() {

        // GIVEN
        Class<?> testClass = SpringBoot3TestNGDisableQuickPerfWithSpringBootTestProperties.class;
        TestNGTests testNGTests = TestNGTests.createInstance(testClass);

        // WHEN
        TestNGTests.TestNGTestsResult testsResult = testNGTests.run();

        // THEN
        assertThat(testsResult.getNumberOfPassedTest()).isOne();

    }

}
