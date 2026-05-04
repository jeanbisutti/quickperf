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

import io.r2dbc.proxy.core.ConnectionInfo;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.spi.Readable;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.reactivestreams.Publisher;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Decorator for {@link Result} that records the column count of the rows
 * produced by an R2DBC query execution into {@link ColumnCountStore}.
 *
 * <p>The wrapped {@link Result} is the one returned by r2dbc-proxy's
 * {@code JdkProxyFactory.wrapResult(...)} so the
 * {@code ResultInvocationSubscriber} chain that drives
 * {@link io.r2dbc.proxy.listener.ProxyExecutionListener#afterQuery(QueryExecutionInfo)}
 * is preserved.
 *
 * <p>The four row-touching {@link Result} methods ({@link #map(BiFunction)},
 * {@link #map(Function)}, {@link #flatMap(Function)}, {@link #filter(Predicate)})
 * are intercepted to slip a tracer onto the user's lambda. The first time the
 * tracer sees row metadata it calls
 * {@link ColumnCountStore#recordOnce(String, QueryExecutionInfo, long)};
 * subsequent calls for the same execution are no-ops (first-writer wins
 * mirrors {@link org.quickperf.sql.SqlExecution#getColumnCount()} which
 * returns the column count of the first {@code ResultSet}).
 *
 * <p>{@link #getRowsUpdated()} is forwarded unchanged because update
 * counts never carry row metadata.
 *
 * <p>{@link #filter(Predicate)} returns a re-wrapped {@link Result} so
 * cascaded {@code map}/{@code flatMap} invocations on the filtered result
 * keep being observed.
 */
public final class QuickPerfMonitoringResult implements Result {

    private final Result delegate;
    private final QueryExecutionInfo queryExecutionInfo;
    private final String connectionId;

    public QuickPerfMonitoringResult(Result delegate, QueryExecutionInfo queryExecutionInfo) {
        this.delegate = delegate;
        this.queryExecutionInfo = queryExecutionInfo;
        ConnectionInfo connectionInfo = queryExecutionInfo == null ? null : queryExecutionInfo.getConnectionInfo();
        this.connectionId = connectionInfo == null ? null : connectionInfo.getConnectionId();
    }

    @Override
    public Publisher<Long> getRowsUpdated() {
        return delegate.getRowsUpdated();
    }

    @Override
    public <T> Publisher<T> map(final BiFunction<Row, RowMetadata, ? extends T> mappingFunction) {
        return delegate.map(new BiFunction<Row, RowMetadata, T>() {
            @Override
            public T apply(Row row, RowMetadata rowMetadata) {
                if (rowMetadata != null) {
                    recordColumnCount(rowMetadata.getColumnMetadatas().size());
                }
                return mappingFunction.apply(row, rowMetadata);
            }
        });
    }

    @Override
    public <T> Publisher<T> map(final Function<? super Readable, ? extends T> mappingFunction) {
        return delegate.map(new Function<Readable, T>() {
            @Override
            public T apply(Readable readable) {
                if (readable instanceof Row) {
                    Row row = (Row) readable;
                    RowMetadata metadata = row.getMetadata();
                    if (metadata != null) {
                        recordColumnCount(metadata.getColumnMetadatas().size());
                    }
                }
                return mappingFunction.apply(readable);
            }
        });
    }

    @Override
    public <T> Publisher<T> flatMap(final Function<Segment, ? extends Publisher<? extends T>> mappingFunction) {
        return delegate.flatMap(new Function<Segment, Publisher<? extends T>>() {
            @Override
            public Publisher<? extends T> apply(Segment segment) {
                recordColumnCountIfRowSegment(segment);
                return mappingFunction.apply(segment);
            }
        });
    }

    @Override
    public Result filter(final Predicate<Segment> filter) {
        Result filtered = delegate.filter(new Predicate<Segment>() {
            @Override
            public boolean test(Segment segment) {
                recordColumnCountIfRowSegment(segment);
                return filter.test(segment);
            }
        });
        return new QuickPerfMonitoringResult(filtered, queryExecutionInfo);
    }

    private void recordColumnCountIfRowSegment(Segment segment) {
        if (segment instanceof RowSegment) {
            Row row = ((RowSegment) segment).row();
            if (row != null) {
                RowMetadata metadata = row.getMetadata();
                if (metadata != null) {
                    recordColumnCount(metadata.getColumnMetadatas().size());
                }
            }
        }
    }

    private void recordColumnCount(long count) {
        ColumnCountStore.recordOnce(connectionId, queryExecutionInfo, count);
    }
}
