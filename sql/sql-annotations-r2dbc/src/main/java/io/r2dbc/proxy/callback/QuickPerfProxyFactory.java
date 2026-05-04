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

// IMPORTANT: This class is deliberately placed in r2dbc-proxy's
// "io.r2dbc.proxy.callback" package so it can invoke the package-private
// JdkProxyFactory(ProxyConfig) constructor. Verified against r2dbc-proxy 1.1.4.
// Pin the r2dbc-proxy version range in the BOM and smoke-build before each
// minor upgrade — if the JdkProxyFactory constructor or ProxyFactory contract
// changes, this class must be revisited.
package io.r2dbc.proxy.callback;

import io.r2dbc.proxy.core.ConnectionInfo;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.StatementInfo;
import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import org.quickperf.sql.r2dbc.QuickPerfMonitoringResult;

/**
 * QuickPerf {@link ProxyFactory} that delegates to r2dbc-proxy's stock
 * {@link JdkProxyFactory} for every wrap, except for
 * {@link #wrapResult(Result, QueryExecutionInfo, QueriesExecutionContext)}
 * which additionally wraps the result in {@link QuickPerfMonitoringResult} so
 * row metadata observed during user-side mapping is recorded into
 * {@link org.quickperf.sql.r2dbc.ColumnCountStore}.
 *
 * <p>Wrapping the {@link JdkProxyFactory} output (instead of returning the raw
 * delegate result directly) preserves r2dbc-proxy's
 * {@code ResultInvocationSubscriber} chain that drives
 * {@link io.r2dbc.proxy.listener.ProxyExecutionListener#afterQuery(QueryExecutionInfo)}.
 */
public final class QuickPerfProxyFactory implements ProxyFactory {

    private final JdkProxyFactory delegate;

    public QuickPerfProxyFactory(ProxyConfig proxyConfig) {
        this.delegate = new JdkProxyFactory(proxyConfig);
    }

    @Override
    public ConnectionFactory wrapConnectionFactory(ConnectionFactory connectionFactory) {
        return delegate.wrapConnectionFactory(connectionFactory);
    }

    @Override
    public Connection wrapConnection(Connection connection, ConnectionInfo connectionInfo) {
        return delegate.wrapConnection(connection, connectionInfo);
    }

    @Override
    public Batch wrapBatch(Batch batch, ConnectionInfo connectionInfo) {
        return delegate.wrapBatch(batch, connectionInfo);
    }

    @Override
    public Statement wrapStatement(Statement statement, StatementInfo statementInfo, ConnectionInfo connectionInfo) {
        return delegate.wrapStatement(statement, statementInfo, connectionInfo);
    }

    @Override
    public Result wrapResult(Result result, QueryExecutionInfo queryExecutionInfo,
                             QueriesExecutionContext queriesExecutionContext) {
        Result wrapped = delegate.wrapResult(result, queryExecutionInfo, queriesExecutionContext);
        return new QuickPerfMonitoringResult(wrapped, queryExecutionInfo);
    }

    @Override
    public Row wrapRow(Row row, QueryExecutionInfo queryExecutionInfo) {
        return delegate.wrapRow(row, queryExecutionInfo);
    }

    @Override
    public Result.RowSegment wrapRowSegment(Result.RowSegment rowSegment, QueryExecutionInfo queryExecutionInfo) {
        return delegate.wrapRowSegment(rowSegment, queryExecutionInfo);
    }
}
