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
import org.quickperf.spring.testngspringboottest.limitsqldisplay.SpringBoot3TestNGLimitSqlDisplayWithApplicationProperties;
import org.quickperf.testng.TestNGTests;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that when the same QuickPerf property is set by both Spring and a JVM system property,
 * the JVM system property takes precedence — matching the order documented in
 * {@code Plan-testng-spring-property-resolution.md} §7.
 *
 * <p>Each inner test class loads the property from {@code application-<profile>.properties}, which
 * Spring places below {@code systemProperties} in its property source ordering. Setting
 * {@link System#setProperty(String, String)} before the inner runs causes Spring's {@code Environment}
 * to resolve the system value, which the QuickPerf resolver then sees.
 */
public class SpringBoot3TestNGPropertyOverrideTest {

    @Test
    public void system_property_overrides_spring_environment_for_disable_quick_perf() {

        // GIVEN
        System.setProperty("disableQuickPerf", "false");
        try {
            Class<?> innerClass = SpringBoot3TestNGDisableQuickPerfWithApplicationProperties.class;
            TestNGTests testNGTests = TestNGTests.createInstance(innerClass);

            // WHEN
            TestNGTests.TestNGTestsResult testsResult = testNGTests.run();

            // THEN
            // Spring sets disableQuickPerf=true via application-disablequickperfprops.properties,
            // but the system property "false" wins, re-enabling QuickPerf, so @ExpectSelect(0)
            // fires against the actual select and the inner test fails.
            assertThat(testsResult.getNumberOfFailedTest()).isOne();
        } finally {
            System.clearProperty("disableQuickPerf");
        }

    }

    @Test
    public void system_property_overrides_spring_environment_for_limit_quick_perf_sql_info_on_console() {

        // GIVEN
        System.setProperty("limitQuickPerfSqlInfoOnConsole", "false");
        try {
            Class<?> innerClass = SpringBoot3TestNGLimitSqlDisplayWithApplicationProperties.class;
            TestNGTests testNGTests = TestNGTests.createInstance(innerClass);

            // WHEN
            TestNGTests.TestNGTestsResult testsResult = testNGTests.run();

            // THEN
            // Spring sets limitQuickPerfSqlInfoOnConsole=true via
            // application-limitsqlprops.properties, but the system property "false" wins, so the
            // failure message must include the full JDBC query execution dump that "limit" hides.
            assertThat(testsResult.getNumberOfFailedTest()).isOne();
            String errorMessage = testsResult.getThrowableOfFirstTest().getMessage();
            assertThat(errorMessage).contains("[JDBC QUERY EXECUTION");
        } finally {
            System.clearProperty("limitQuickPerfSqlInfoOnConsole");
        }

    }

}
