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
package org.quickperf.sql.r2dbc;

import io.r2dbc.proxy.core.Bindings;
import io.r2dbc.proxy.core.ConnectionInfo;
import io.r2dbc.proxy.core.ExecutionType;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.StatementType;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Synthesizes datasource-proxy {@link ExecutionInfo} and {@link QueryInfo}
 * objects from r2dbc-proxy's {@link QueryExecutionInfo}, so that the JDBC
 * branch's {@link org.quickperf.sql.SqlRecorder} stack and downstream
 * extractors / verifiers can ingest reactive executions unchanged.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@link ExecutionInfo#getDataSourceName()} is set to {@code "r2dbc:" + beanName}
 *       so JDBC and R2DBC executions remain distinguishable in reports.</li>
 *   <li>{@link ExecutionInfo#getResult()} is set to a synthetic {@link java.sql.ResultSet}
 *       that exposes the previously-recorded R2DBC column count via
 *       {@code getMetaData().getColumnCount()}, or left {@code null} when no column count
 *       was observed (update statements, failed executions).
 *       {@link org.quickperf.sql.SqlExecution} short-circuits its column count to 0 when
 *       {@code result == null}.</li>
 *   <li>{@link QueryInfo#getQuery()} carries the rewritten SQL with positional /
 *       named placeholders replaced by {@code ?}.</li>
 *   <li>{@link QueryInfo#getParametersList()} contains one
 *       {@code List<ParameterSetOperation>} per {@link Bindings} (so batched
 *       executions are flattened).</li>
 * </ul>
 */
final class R2dbcExecutionAdapter {

    private R2dbcExecutionAdapter() {}

    /** Output of {@link #adapt(QueryExecutionInfo, String)}: the JDBC-side ExecutionInfo + queries. */
    static final class Adapted {
        final ExecutionInfo executionInfo;
        final List<QueryInfo> queries;

        Adapted(ExecutionInfo executionInfo, List<QueryInfo> queries) {
            this.executionInfo = executionInfo;
            this.queries = queries;
        }
    }

    /**
     * Build a JDBC-shaped pair from an r2dbc-proxy after-query event.
     *
     * @param info     the r2dbc-proxy query execution event.
     * @param beanName the Spring bean name of the proxied {@code ConnectionFactory}, or
     *                 {@code "connectionFactory"} for non-Spring builders.
     */
    static Adapted adapt(QueryExecutionInfo info, String beanName) {
        ExecutionInfo execInfo = new ExecutionInfo();
        execInfo.setDataSourceName("r2dbc:" + (beanName == null ? "connectionFactory" : beanName));

        ConnectionInfo connectionInfo = info.getConnectionInfo();
        String connectionId = null;
        if (connectionInfo != null) {
            connectionId = connectionInfo.getConnectionId();
            execInfo.setConnectionId(connectionId);
        }

        Duration duration = info.getExecuteDuration();
        execInfo.setElapsedTime(duration == null ? 0L : duration.toMillis());

        execInfo.setSuccess(info.isSuccess());
        execInfo.setThrowable(info.getThrowable());

        boolean isBatch = info.getType() == ExecutionType.BATCH || info.getBatchSize() > 1;
        execInfo.setBatch(isBatch);
        execInfo.setBatchSize(info.getBatchSize());

        execInfo.setStatementType(detectStatementType(info));

        long columnCount = ColumnCountStore.drain(connectionId, info);
        if (columnCount > 0L) {
            execInfo.setResult(R2dbcSyntheticStatement.resultWithColumnCount(columnCount));
        }

        List<QueryInfo> queries = adaptQueries(info.getQueries());
        return new Adapted(execInfo, queries);
    }

    private static StatementType detectStatementType(QueryExecutionInfo info) {
        List<io.r2dbc.proxy.core.QueryInfo> r2Queries = info.getQueries();
        if (r2Queries == null) {
            return StatementType.STATEMENT;
        }
        for (io.r2dbc.proxy.core.QueryInfo q : r2Queries) {
            List<Bindings> bl = q.getBindingsList();
            if (bl != null && !bl.isEmpty()) {
                for (Bindings b : bl) {
                    if (!b.getIndexBindings().isEmpty() || !b.getNamedBindings().isEmpty()) {
                        return StatementType.PREPARED;
                    }
                }
            }
        }
        return StatementType.STATEMENT;
    }

    private static List<QueryInfo> adaptQueries(List<io.r2dbc.proxy.core.QueryInfo> r2Queries) {
        if (r2Queries == null || r2Queries.isEmpty()) {
            return Collections.emptyList();
        }
        List<QueryInfo> out = new ArrayList<QueryInfo>(r2Queries.size());
        for (io.r2dbc.proxy.core.QueryInfo r2 : r2Queries) {
            out.add(adaptQuery(r2));
        }
        return out;
    }

    private static QueryInfo adaptQuery(io.r2dbc.proxy.core.QueryInfo r2) {
        String originalSql = r2.getQuery();
        PlaceholderRewriter.Result rewrite = PlaceholderRewriter.rewrite(originalSql);

        QueryInfo q = new QueryInfo(rewrite.rewrittenSql());

        List<Bindings> bindingsList = r2.getBindingsList();
        if (bindingsList != null && !bindingsList.isEmpty() && !rewrite.orderedKeys().isEmpty()) {
            List<List<ParameterSetOperation>> params =
                    R2dbcBindingsAdapter.toParametersList(bindingsList, rewrite.orderedKeys());
            q.setParametersList(params);
        }

        return q;
    }

}
