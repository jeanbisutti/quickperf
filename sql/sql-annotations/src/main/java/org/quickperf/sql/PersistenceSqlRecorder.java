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
package org.quickperf.sql;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import org.quickperf.TestExecutionContext;
import org.quickperf.WorkingFolder;
import org.quickperf.sql.repository.SqlRepository;
import org.quickperf.sql.repository.SqlRepositoryFactory;

import java.io.PrintStream;
import java.util.List;


public class PersistenceSqlRecorder implements SqlRecorder<SqlExecutions> {

    /** Test seam: stderr the cross-test contamination warning is written
     * to. Defaults to {@link System#err}; tests substitute a capturing
     * PrintStream and restore the previous value afterwards. Volatile so
     * the swap is visible across threads under Surefire parallel=all. */
    static volatile PrintStream WARNING_SINK = System.err;

    private final DataSourceProxyVerifier datasourceProxyVerifier = new DataSourceProxyVerifier();

    private SqlRepository sqlRepository;

    @Override
    public void startRecording(TestExecutionContext testExecutionContext) {
        // Publish-last (I13): fully initialise sqlRepository BEFORE register,
        // so a worker thread that takes the active-set fallback during this
        // construction window cannot observe `this` mid-construction and NPE
        // on a still-null sqlRepository.
        sqlRepository = SqlRepositoryFactory.getSqlRepository(testExecutionContext);
        // Clear any contamination flag carried over from a previous test
        // that reused this recorder instance (defensive - the same
        // PersistenceSqlRecorder identity should not leak a stale tag into
        // a fresh start).
        SqlRecorderRegistry.clearCrossTestContaminationFor(this);
        SqlRecorderRegistry.INSTANCE.register(this);
    }

    @Override
    public void addQueryExecution(ExecutionInfo execInfo, List<QueryInfo> queries, int listenerIdentifier) {
        datasourceProxyVerifier.addListenerIdentifier(listenerIdentifier);
        sqlRepository.addQueryExecution(execInfo, queries);
    }

    @Override
    public void stopRecording(TestExecutionContext testExecutionContext) {
        SqlRecorderRegistry.unregister(this);
        WorkingFolder workingFolder = testExecutionContext.getWorkingFolder();
        sqlRepository.flush(workingFolder);
        if(datasourceProxyVerifier.hasQuickPerfBuiltSeveralDataSourceProxies()) {
            System.out.println();
            System.err.println(DataSourceProxyVerifier.SEVERAL_PROXIES_WARNING);
        }
    }

    @Override
    public SqlExecutions findRecord(TestExecutionContext testExecutionContext) {
        // Test executed in a specific JVM
        if (sqlRepository == null) {
            sqlRepository = SqlRepositoryFactory.getSqlRepository(testExecutionContext);
        }
        try {
            WorkingFolder workingFolder = testExecutionContext.getWorkingFolder();
            SqlExecutions executions = sqlRepository.findExecutedQueries(workingFolder);
            if (SqlRecorderRegistry.hasCrossTestContamination(this)) {
                // Defensive: a misbehaving repository may return SqlExecutions.NONE
                // (the JVM-wide singleton). Tagging NONE is a no-op so we
                // would silently lose the warning. Swap to a fresh
                // empty SqlExecutions in that case so the marker survives.
                if (executions == SqlExecutions.NONE) {
                    executions = new SqlExecutions();
                }
                executions.markCrossTestContamination();
                WARNING_SINK.println(SqlExecutions.CROSS_TEST_CONTAMINATION_WARNING);
            }
            return executions;
        } finally {
            // Flag is single-shot per test - clearing after read prevents a
            // subsequent reuse of this recorder (under @RepeatedTest, persistent
            // worker pools, etc.) from re-emitting the same warning.
            SqlRecorderRegistry.clearCrossTestContaminationFor(this);
        }
    }

    @Override
    public void cleanResources() {
        // Backstop eviction: defends against a recorder leaking a tag if
        // findRecord() is never called (e.g. the test failed before
        // PerfIssuesEvaluator ran).
        SqlRecorderRegistry.clearCrossTestContaminationFor(this);
    }

}
