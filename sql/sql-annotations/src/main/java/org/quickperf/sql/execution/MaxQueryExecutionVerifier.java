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

import org.quickperf.issue.PerfIssue;
import org.quickperf.issue.VerifiablePerformanceIssue;
import org.quickperf.sql.annotation.ExpectMaxQueryExecution;

/**
 * Verifier for the database-agnostic {@link ExpectMaxQueryExecution} alias. Shares the assertion rules with
 * {@link MaxJdbcQueryExecutionVerifier} (the legacy {@code @ExpectMaxJdbcQueryExecution} verifier) by delegating
 * to the package-private {@link MaxJdbcQueryExecutionVerifier#verify(int, SqlAnalysis)} helper.
 */
public class MaxQueryExecutionVerifier implements VerifiablePerformanceIssue<ExpectMaxQueryExecution, SqlAnalysis>  {

    public static final MaxQueryExecutionVerifier INSTANCE = new MaxQueryExecutionVerifier();

    private MaxQueryExecutionVerifier() { }

    @Override
    public PerfIssue verifyPerfIssue(ExpectMaxQueryExecution annotation, SqlAnalysis sqlAnalysis) {
        return MaxJdbcQueryExecutionVerifier.verify(annotation.value(), sqlAnalysis);
    }

}
