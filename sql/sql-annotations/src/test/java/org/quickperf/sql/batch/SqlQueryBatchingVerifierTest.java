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

import org.junit.Test;
import org.quickperf.issue.PerfIssue;
import org.quickperf.sql.annotation.ExpectJdbcBatching;
import org.quickperf.sql.annotation.ExpectQueryBatching;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

public class SqlQueryBatchingVerifierTest {

    @ExpectJdbcBatching
    private static class WithLegacyDefault {
        public void m() {}
    }

    @ExpectQueryBatching
    private static class WithAliasDefault {
        public void m() {}
    }

    @ExpectJdbcBatching(batchSize = 30)
    private static class WithLegacySize30 {
        public void m() {}
    }

    @ExpectQueryBatching(batchSize = 30)
    private static class WithAliasSize30 {
        public void m() {}
    }

    private static ExpectJdbcBatching legacyAnnotation(Class<?> source) {
        return source.getAnnotation(ExpectJdbcBatching.class);
    }

    private static ExpectQueryBatching aliasAnnotation(Class<?> source) {
        return source.getAnnotation(ExpectQueryBatching.class);
    }

    private static SqlBatchSizes batchSizes(int... values) {
        return new SqlBatchSizes(values);
    }

    @Test
    public void alias_verifier_with_no_size_returns_no_issue_when_batches_are_used() {
        PerfIssue legacy = SqlStatementBatchVerifier.INSTANCE.verifyPerfIssue(
                legacyAnnotation(WithLegacyDefault.class), batchSizes(30, 30));
        PerfIssue alias = SqlQueryBatchingVerifier.INSTANCE.verifyPerfIssue(
                aliasAnnotation(WithAliasDefault.class), batchSizes(30, 30));

        assertThat(legacy.getDescription()).isEqualTo(PerfIssue.NONE.getDescription());
        assertThat(alias.getDescription()).isEqualTo(legacy.getDescription());
    }

    @Test
    public void alias_verifier_with_no_size_reports_disabled_batching() {
        PerfIssue legacy = SqlStatementBatchVerifier.INSTANCE.verifyPerfIssue(
                legacyAnnotation(WithLegacyDefault.class), batchSizes(0));
        PerfIssue alias = SqlQueryBatchingVerifier.INSTANCE.verifyPerfIssue(
                aliasAnnotation(WithAliasDefault.class), batchSizes(0));

        assertThat(legacy.getDescription()).contains("JDBC batching is disabled");
        assertThat(alias.getDescription()).isEqualTo(legacy.getDescription());
    }

    @Test
    public void alias_verifier_with_size_returns_no_issue_when_batch_size_matches() {
        PerfIssue legacy = SqlStatementBatchVerifier.INSTANCE.verifyPerfIssue(
                legacyAnnotation(WithLegacySize30.class), batchSizes(30, 30));
        PerfIssue alias = SqlQueryBatchingVerifier.INSTANCE.verifyPerfIssue(
                aliasAnnotation(WithAliasSize30.class), batchSizes(30, 30));

        assertThat(legacy.getDescription()).isEqualTo(PerfIssue.NONE.getDescription());
        assertThat(alias.getDescription()).isEqualTo(legacy.getDescription());
    }

    @Test
    public void alias_verifier_with_size_reports_mismatch_with_same_message_as_legacy() {
        PerfIssue legacy = SqlStatementBatchVerifier.INSTANCE.verifyPerfIssue(
                legacyAnnotation(WithLegacySize30.class), batchSizes(20, 20));
        PerfIssue alias = SqlQueryBatchingVerifier.INSTANCE.verifyPerfIssue(
                aliasAnnotation(WithAliasSize30.class), batchSizes(20, 20));

        assertThat(legacy.getDescription()).contains("Expected batch size <30>", "is <20>");
        assertThat(alias.getDescription()).isEqualTo(legacy.getDescription());
    }

    /**
     * Sanity check that the alias annotation has the expected reflective metadata so other QuickPerf
     * machinery (annotation discovery, global annotation merging) can pick it up.
     */
    @Test
    public void alias_annotation_is_runtime_visible_on_classes() throws NoSuchMethodException {
        Method m = WithAliasSize30.class.getDeclaredMethod("m");
        ExpectQueryBatching onMethod = m.getAnnotation(ExpectQueryBatching.class);
        ExpectQueryBatching onClass = WithAliasSize30.class.getAnnotation(ExpectQueryBatching.class);

        assertThat(onMethod).isNull();
        assertThat(onClass).isNotNull();
        assertThat(onClass.batchSize()).isEqualTo(30);
    }

}
