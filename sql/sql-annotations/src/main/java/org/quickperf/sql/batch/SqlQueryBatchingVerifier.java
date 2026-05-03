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
package org.quickperf.sql.batch;

import org.quickperf.issue.PerfIssue;
import org.quickperf.issue.VerifiablePerformanceIssue;
import org.quickperf.sql.annotation.ExpectQueryBatching;

/**
 * Verifier for the database-agnostic {@link ExpectQueryBatching} alias. Shares the assertion rules with
 * {@link SqlStatementBatchVerifier} (the legacy {@code @ExpectJdbcBatching} verifier) by delegating to the
 * package-private {@link SqlStatementBatchVerifier#verify(int, int[])} helper.
 */
public class SqlQueryBatchingVerifier implements VerifiablePerformanceIssue<ExpectQueryBatching, SqlBatchSizes> {

    public static final SqlQueryBatchingVerifier INSTANCE = new SqlQueryBatchingVerifier();

    private SqlQueryBatchingVerifier() {}

    @Override
    public PerfIssue verifyPerfIssue(ExpectQueryBatching annotation, SqlBatchSizes measuredSqlBatchSizes) {
        return SqlStatementBatchVerifier.verify(annotation.batchSize(), measuredSqlBatchSizes.getValue());
    }

}
