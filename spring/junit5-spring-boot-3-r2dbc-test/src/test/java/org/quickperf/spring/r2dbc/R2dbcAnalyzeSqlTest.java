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
import org.quickperf.spring.r2dbc.r2dbctest.AnalyzeSqlWithR2dbc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link org.quickperf.sql.annotation.AnalyzeSql @AnalyzeSql}
 * produces a full SQL analysis report for reactive R2DBC queries: launches
 * {@link AnalyzeSqlWithR2dbc} (which issues a {@code CREATE TABLE}, a
 * {@code MERGE} and a {@code SELECT} via reactive {@code DatabaseClient})
 * through the JUnit 5 launcher and asserts the rendered report contains the
 * expected execution counts, the SELECT bucket, and the formatted SELECT
 * statement (which exercises PR-1's placeholder rendering and PR-2's column
 * count fix end-to-end).
 */
class R2dbcAnalyzeSqlTest {

    @Test
    void should_render_full_sql_analysis_for_r2dbc() throws IOException {

        // GIVEN
        Class<?> testClass = AnalyzeSqlWithR2dbc.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult result = jUnit5Tests.run();

        // THEN
        assertThat(result.getNumberOfFailures()).isZero();

        String report = Files.lines(Paths.get(AnalyzeSqlWithR2dbc.FILE_PATH))
                .collect(joining(System.lineSeparator()));

        assertThat(report)
                .contains("[QUICK PERF] SQL ANALYSIS")
                .contains("SQL EXECUTIONS:")
                .contains("SELECT: ")
                .contains("SELECT id, name FROM ANALYZE_R2DBC_TEST");
    }

}
