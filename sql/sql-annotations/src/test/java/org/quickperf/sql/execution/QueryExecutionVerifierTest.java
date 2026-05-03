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
package org.quickperf.sql.execution;

import org.junit.Test;
import org.quickperf.issue.PerfIssue;
import org.quickperf.sql.SqlExecutions;
import org.quickperf.sql.annotation.ExpectJdbcQueryExecution;
import org.quickperf.sql.annotation.ExpectQueryExecution;
import org.quickperf.sql.select.analysis.SelectAnalysis;
import org.quickperf.unit.Count;

import static org.assertj.core.api.Assertions.assertThat;

public class QueryExecutionVerifierTest {

    @ExpectJdbcQueryExecution(2)
    private static class Legacy2 {}

    @ExpectQueryExecution(2)
    private static class Alias2 {}

    private static ExpectJdbcQueryExecution legacy(Class<?> source) {
        return source.getAnnotation(ExpectJdbcQueryExecution.class);
    }

    private static ExpectQueryExecution alias(Class<?> source) {
        return source.getAnnotation(ExpectQueryExecution.class);
    }

    private static SqlAnalysis analysisWith(int actualExecutions) {
        SelectAnalysis selectAnalysis = new SelectAnalysis(0, 0, false);
        return new SqlAnalysis(new Count(actualExecutions), selectAnalysis, new SqlExecutions());
    }

    @Test
    public void alias_returns_no_issue_when_count_matches() {
        PerfIssue legacyResult = JdbcQueryExecutionVerifier.INSTANCE.verifyPerfIssue(
                legacy(Legacy2.class), analysisWith(2));
        PerfIssue aliasResult = QueryExecutionVerifier.INSTANCE.verifyPerfIssue(
                alias(Alias2.class), analysisWith(2));

        assertThat(legacyResult.getDescription()).isEqualTo(PerfIssue.NONE.getDescription());
        assertThat(aliasResult.getDescription()).isEqualTo(legacyResult.getDescription());
    }

    @Test
    public void alias_reports_mismatch_with_same_message_as_legacy() {
        PerfIssue legacyResult = JdbcQueryExecutionVerifier.INSTANCE.verifyPerfIssue(
                legacy(Legacy2.class), analysisWith(5));
        PerfIssue aliasResult = QueryExecutionVerifier.INSTANCE.verifyPerfIssue(
                alias(Alias2.class), analysisWith(5));

        assertThat(legacyResult.getDescription()).contains("<2>", "<5>");
        assertThat(aliasResult.getDescription()).isEqualTo(legacyResult.getDescription());
    }

}
