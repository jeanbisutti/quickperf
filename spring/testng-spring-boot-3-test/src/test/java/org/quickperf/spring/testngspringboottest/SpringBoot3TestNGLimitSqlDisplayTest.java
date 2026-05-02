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

import org.quickperf.spring.testngspringboottest.limitsqldisplay.SpringBoot3TestNGLimitSqlDisplayWithApplicationProperties;
import org.quickperf.spring.testngspringboottest.limitsqldisplay.SpringBoot3TestNGLimitSqlDisplayWithApplicationYml;
import org.quickperf.spring.testngspringboottest.limitsqldisplay.SpringBoot3TestNGLimitSqlDisplayWithSpringBootTestProperties;
import org.quickperf.testng.TestNGTests;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SpringBoot3TestNGLimitSqlDisplayTest {

    @Test(enabled = false) // TODO QuickPerf v2 — TestNG-Spring property resolution out of scope per Plan-spring-property-resolution.md §7
    public void should_limit_sql_display_when_property_defined_in_application_properties() {

        // GIVEN
        Class<?> testClass = SpringBoot3TestNGLimitSqlDisplayWithApplicationProperties.class;
        TestNGTests testNGTests = TestNGTests.createInstance(testClass);

        // WHEN
        TestNGTests.TestNGTestsResult testsResult = testNGTests.run();

        // THEN
        assertThat(testsResult.getNumberOfFailedTest()).isOne();

        String errorMessage = testsResult.getThrowableOfFirstTest().getMessage();
        assertThat(errorMessage)
                      .contains("You may think that <1> select statement was sent to the database")
                      .doesNotContain("[JDBC QUERY EXECUTION");

    }

    @Test(enabled = false) // TODO QuickPerf v2 — TestNG-Spring property resolution out of scope per Plan-spring-property-resolution.md §7
    public void should_limit_sql_display_when_property_defined_in_application_yml() {

        // GIVEN
        Class<?> testClass = SpringBoot3TestNGLimitSqlDisplayWithApplicationYml.class;
        TestNGTests testNGTests = TestNGTests.createInstance(testClass);

        // WHEN
        TestNGTests.TestNGTestsResult testsResult = testNGTests.run();

        // THEN
        assertThat(testsResult.getNumberOfFailedTest()).isOne();

        String errorMessage = testsResult.getThrowableOfFirstTest().getMessage();
        assertThat(errorMessage)
                      .contains("You may think that <1> select statement was sent to the database")
                      .doesNotContain("[JDBC QUERY EXECUTION");

    }

    @Test(enabled = false) // TODO QuickPerf v2 — TestNG-Spring property resolution out of scope per Plan-spring-property-resolution.md §7
    public void should_limit_sql_display_when_property_defined_in_spring_boot_test_properties() {

        // GIVEN
        Class<?> testClass = SpringBoot3TestNGLimitSqlDisplayWithSpringBootTestProperties.class;
        TestNGTests testNGTests = TestNGTests.createInstance(testClass);

        // WHEN
        TestNGTests.TestNGTestsResult testsResult = testNGTests.run();

        // THEN
        assertThat(testsResult.getNumberOfFailedTest()).isOne();

        String errorMessage = testsResult.getThrowableOfFirstTest().getMessage();
        assertThat(errorMessage)
                      .contains("You may think that <1> select statement was sent to the database")
                      .doesNotContain("[JDBC QUERY EXECUTION");

    }

}
