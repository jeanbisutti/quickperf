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
import org.quickperf.spring.springboottest.limitsqldisplay.SpringBoot3JUnit5LimitSqlDisplayWithApplicationProperties;
import org.quickperf.spring.springboottest.limitsqldisplay.SpringBoot3JUnit5LimitSqlDisplayWithApplicationYml;
import org.quickperf.spring.springboottest.limitsqldisplay.SpringBoot3JUnit5LimitSqlDisplayWithSpringBootTestProperties;
import org.quickperf.spring.springboottest.limitsqldisplay.SpringBoot3JUnit5LimitSqlDisplayWithSpringBootTestPropertiesForkedJvm;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBoot3JUnit5LimitSqlDisplayTest {

    @Test
    void should_limit_sql_display_when_property_defined_in_application_yml() {

        // GIVEN
        Class<?> testClass = SpringBoot3JUnit5LimitSqlDisplayWithApplicationYml.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        // THEN
        assertThat(jUnit5TestsResult.getNumberOfFailures()).isOne();

        String errorReport = jUnit5TestsResult.getErrorReport();
        assertThat(errorReport)
                      .contains("You may think that <1> select statement was sent to the database")
                      .doesNotContain("[JDBC QUERY EXECUTION");

    }

    @Test
    void should_limit_sql_display_when_property_defined_in_application_properties() {

        // GIVEN
        Class<?> testClass = SpringBoot3JUnit5LimitSqlDisplayWithApplicationProperties.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        // THEN
        assertThat(jUnit5TestsResult.getNumberOfFailures()).isOne();

        String errorReport = jUnit5TestsResult.getErrorReport();
        assertThat(errorReport)
                      .contains("You may think that <1> select statement was sent to the database")
                      .doesNotContain("[JDBC QUERY EXECUTION");

    }

    @Test
    void should_limit_sql_display_when_property_defined_in_spring_boot_test_properties() {

        // GIVEN
        Class<?> testClass = SpringBoot3JUnit5LimitSqlDisplayWithSpringBootTestProperties.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        // THEN
        assertThat(jUnit5TestsResult.getNumberOfFailures()).isOne();

        String errorReport = jUnit5TestsResult.getErrorReport();
        assertThat(errorReport)
                      .contains("You may think that <1> select statement was sent to the database")
                      .doesNotContain("[JDBC QUERY EXECUTION");

    }

    @Test
    void should_limit_sql_display_when_property_defined_in_spring_boot_test_properties_with_forked_jvm() {

        // GIVEN
        Class<?> testClass = SpringBoot3JUnit5LimitSqlDisplayWithSpringBootTestPropertiesForkedJvm.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        // THEN
        assertThat(jUnit5TestsResult.getNumberOfFailures()).isOne();

        String errorReport = jUnit5TestsResult.getErrorReport();
        assertThat(errorReport)
                      .contains("You may think that <1> select statement was sent to the database")
                      .doesNotContain("[JDBC QUERY EXECUTION");

    }

}
