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
package org.quickperf.spring.r2dbc;

import org.junit.jupiter.api.Test;
import org.quickperf.junit5.JUnit5Tests;
import org.quickperf.junit5.JUnit5Tests.JUnit5TestsResult;
import org.quickperf.spring.r2dbc.r2dbctest.ExpectSelectWithR2dbc;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link org.quickperf.sql.annotation.ExpectSelect @ExpectSelect}
 * detects R2DBC executions: launches {@link ExpectSelectWithR2dbc} (which runs
 * 2 SELECTs but expects 1) via the JUnit 5 launcher and confirms the test
 * fails with the QuickPerf diagnostic message.
 */
class SpringBoot3JUnit5R2dbcTest {

    @Test
    void should_detect_select_with_r2dbc_test() {

        // GIVEN
        Class<?> testClass = ExpectSelectWithR2dbc.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        // THEN
        assertThat(jUnit5TestsResult.getNumberOfFailures()).isOne();

        String errorReport = jUnit5TestsResult.getErrorReport();
        assertThat(errorReport)
                .contains("You may think that <1> select statement was sent to the database");

    }

}
