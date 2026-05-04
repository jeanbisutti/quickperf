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
import org.quickperf.spring.r2dbc.r2dbctest.DisplaySqlWithR2dbc;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link org.quickperf.sql.annotation.DisplaySql @DisplaySql}
 * works end-to-end with R2DBC: the inner test
 * {@link DisplaySqlWithR2dbc} executes a {@code CREATE TABLE}, a {@code MERGE}
 * and a {@code SELECT} via a reactive {@code DatabaseClient} while
 * {@code @DisplaySql} is active, and must complete without errors.
 *
 * <p>The actual rendered-SQL correctness (placeholder scanner / {@code ?}-only
 * renderer fix from PR-1, column count from PR-2) is asserted by
 * {@link R2dbcAnalyzeSqlTest} which uses the same {@code QuickPerfSqlFormatter}
 * pipeline but writes to a file rather than {@link System#out} — capturing
 * {@link System#out} here would be racy under {@code surefire parallel=all}.
 */
class R2dbcDisplaySqlTest {

    @Test
    void at_display_sql_must_not_break_r2dbc_test_execution() {

        // GIVEN
        Class<?> testClass = DisplaySqlWithR2dbc.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult result = jUnit5Tests.run();

        // THEN
        assertThat(result.getNumberOfFailures())
                .as("Inner test failures:%n%s", result.getErrorReport())
                .isZero();
    }

}
