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
import org.quickperf.sql.annotation.ExpectMaxJdbcQueryExecution;
import org.quickperf.sql.annotation.ExpectMaxQueryExecution;
import org.quickperf.sql.select.analysis.SelectAnalysis;
import org.quickperf.unit.Count;

import static org.assertj.core.api.Assertions.assertThat;

public class MaxQueryExecutionVerifierTest {

    @ExpectMaxJdbcQueryExecution(3)
    private static class LegacyMax3 {}

    @ExpectMaxQueryExecution(3)
    private static class AliasMax3 {}

    private static ExpectMaxJdbcQueryExecution legacy(Class<?> source) {
        return source.getAnnotation(ExpectMaxJdbcQueryExecution.class);
    }

    private static ExpectMaxQueryExecution alias(Class<?> source) {
        return source.getAnnotation(ExpectMaxQueryExecution.class);
    }

    private static SqlAnalysis analysisWith(int actualExecutions) {
        SelectAnalysis selectAnalysis = new SelectAnalysis(0, 0, false);
        return new SqlAnalysis(new Count(actualExecutions), selectAnalysis, new SqlExecutions());
    }

    @Test
    public void alias_returns_no_issue_when_count_is_below_max() {
        PerfIssue legacyResult = MaxJdbcQueryExecutionVerifier.INSTANCE.verifyPerfIssue(
                legacy(LegacyMax3.class), analysisWith(2));
        PerfIssue aliasResult = MaxQueryExecutionVerifier.INSTANCE.verifyPerfIssue(
                alias(AliasMax3.class), analysisWith(2));

        assertThat(legacyResult.getDescription()).isEqualTo(PerfIssue.NONE.getDescription());
        assertThat(aliasResult.getDescription()).isEqualTo(legacyResult.getDescription());
    }

    @Test
    public void alias_returns_no_issue_when_count_equals_max() {
        PerfIssue aliasResult = MaxQueryExecutionVerifier.INSTANCE.verifyPerfIssue(
                alias(AliasMax3.class), analysisWith(3));

        assertThat(aliasResult.getDescription()).isEqualTo(PerfIssue.NONE.getDescription());
    }

    @Test
    public void alias_reports_excess_with_same_message_as_legacy() {
        PerfIssue legacyResult = MaxJdbcQueryExecutionVerifier.INSTANCE.verifyPerfIssue(
                legacy(LegacyMax3.class), analysisWith(7));
        PerfIssue aliasResult = MaxQueryExecutionVerifier.INSTANCE.verifyPerfIssue(
                alias(AliasMax3.class), analysisWith(7));

        assertThat(legacyResult.getDescription()).contains("at most <3>", "<7>");
        assertThat(aliasResult.getDescription()).isEqualTo(legacyResult.getDescription());
    }

}
