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
import io.r2dbc.proxy.test.MockConnectionInfo;
import io.r2dbc.proxy.test.MockQueryExecutionInfo;
import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.Readable;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.Before;
import org.junit.Test;
import org.reactivestreams.Publisher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuickPerfMonitoringResult}: each Result API surface
 * (the two {@code map} overloads, {@code flatMap}, {@code filter}) records the
 * column count exactly once into {@link ColumnCountStore} and forwards the
 * user's lambda unchanged.
 *
 * <p>The tests use {@link CapturingResult}, a Result fake whose
 * {@code map} / {@code flatMap} / {@code filter} methods capture the
 * (instrumented) lambda passed by {@link QuickPerfMonitoringResult}. The test
 * then drives the captured lambda directly with stubbed {@link Row} /
 * {@link RowMetadata} values, simulating r2dbc-proxy emitting a row.
 */
public class QuickPerfMonitoringResultTest {

    private String connectionId;
    private QueryExecutionInfo qei;

    @Before
    public void setUp() {
        connectionId = "conn-" + UUID.randomUUID();
        ConnectionInfo conn = MockConnectionInfo.builder().connectionId(connectionId).build();
        qei = MockQueryExecutionInfo.builder().connectionInfo(conn).build();
    }

    @Test
    public void map_bifunction_records_column_count_and_invokes_user_lambda() {
        Row row = mock(Row.class);
        RowMetadata metadata = metadataWith(2);
        CapturingResult delegate = new CapturingResult();
        QuickPerfMonitoringResult monitoring = new QuickPerfMonitoringResult(delegate, qei);

        AtomicReference<String> userResult = new AtomicReference<>();
        BiFunction<Row, RowMetadata, String> userMapper = (r, m) -> {
            userResult.set("user-saw-" + m.getColumnMetadatas().size());
            return "v";
        };
        monitoring.map(userMapper);

        // Simulate r2dbc-proxy invoking the instrumented lambda
        delegate.invokeBiFunctionWith(row, metadata);

        assertThat(userResult.get()).isEqualTo("user-saw-2");
        assertThat(ColumnCountStore.drain(connectionId, qei)).isEqualTo(2L);
    }

    @Test
    public void map_function_records_column_count_for_row_readable() {
        Row row = mock(Row.class);
        RowMetadata metadata = metadataWith(4);
        when(row.getMetadata()).thenReturn(metadata);
        CapturingResult delegate = new CapturingResult();
        QuickPerfMonitoringResult monitoring = new QuickPerfMonitoringResult(delegate, qei);

        AtomicReference<Readable> userSaw = new AtomicReference<>();
        Function<Readable, String> userMapper = readable -> {
            userSaw.set(readable);
            return "v";
        };
        monitoring.map(userMapper);

        delegate.invokeFunctionWith(row);

        assertThat(userSaw.get()).isSameAs(row);
        assertThat(ColumnCountStore.drain(connectionId, qei)).isEqualTo(4L);
    }

    @Test
    public void map_function_for_non_row_readable_records_no_count() {
        Readable nonRow = mock(Readable.class);
        CapturingResult delegate = new CapturingResult();
        QuickPerfMonitoringResult monitoring = new QuickPerfMonitoringResult(delegate, qei);

        monitoring.map((Function<Readable, String>) readable -> "v");
        delegate.invokeFunctionWith(nonRow);

        assertThat(ColumnCountStore.drain(connectionId, qei)).isZero();
    }

    @Test
    public void flatMap_records_column_count_for_row_segments() {
        Row row = mock(Row.class);
        RowMetadata metadata = metadataWith(3);
        when(row.getMetadata()).thenReturn(metadata);
        Result.RowSegment rowSegment = mock(Result.RowSegment.class);
        when(rowSegment.row()).thenReturn(row);
        CapturingResult delegate = new CapturingResult();
        QuickPerfMonitoringResult monitoring = new QuickPerfMonitoringResult(delegate, qei);

        AtomicReference<Result.Segment> userSaw = new AtomicReference<>();
        monitoring.flatMap(seg -> {
            userSaw.set(seg);
            return null;
        });
        delegate.invokeFlatMapWith(rowSegment);

        assertThat(userSaw.get()).isSameAs(rowSegment);
        assertThat(ColumnCountStore.drain(connectionId, qei)).isEqualTo(3L);
    }

    @Test
    public void flatMap_for_non_row_segment_records_no_count() {
        Result.Segment nonRow = mock(Result.Segment.class);
        CapturingResult delegate = new CapturingResult();
        QuickPerfMonitoringResult monitoring = new QuickPerfMonitoringResult(delegate, qei);

        monitoring.flatMap(seg -> null);
        delegate.invokeFlatMapWith(nonRow);

        assertThat(ColumnCountStore.drain(connectionId, qei)).isZero();
    }

    @Test
    public void filter_records_column_count_and_returns_a_re_wrapped_result() {
        Row row = mock(Row.class);
        RowMetadata metadata = metadataWith(5);
        when(row.getMetadata()).thenReturn(metadata);
        Result.RowSegment rowSegment = mock(Result.RowSegment.class);
        when(rowSegment.row()).thenReturn(row);
        CapturingResult delegate = new CapturingResult();
        QuickPerfMonitoringResult monitoring = new QuickPerfMonitoringResult(delegate, qei);

        Result filtered = monitoring.filter(seg -> true);
        delegate.invokeFilterWith(rowSegment);

        assertThat(filtered).isInstanceOf(QuickPerfMonitoringResult.class);
        assertThat(ColumnCountStore.drain(connectionId, qei)).isEqualTo(5L);
    }

    @Test
    public void getRowsUpdated_does_not_record_a_column_count() {
        @SuppressWarnings("unchecked")
        Publisher<Long> updatesPublisher = mock(Publisher.class);
        CapturingResult delegate = new CapturingResult();
        delegate.rowsUpdated = updatesPublisher;
        QuickPerfMonitoringResult monitoring = new QuickPerfMonitoringResult(delegate, qei);

        Publisher<Long> result = monitoring.getRowsUpdated();

        assertThat(result).isSameAs(updatesPublisher);
        assertThat(ColumnCountStore.drain(connectionId, qei)).isZero();
    }

    @Test
    public void heterogeneous_batch_keeps_first_count_first_writer_wins() {
        Row firstRow = mock(Row.class);
        RowMetadata firstMetadata = metadataWith(2);
        when(firstRow.getMetadata()).thenReturn(firstMetadata);
        Row secondRow = mock(Row.class);
        RowMetadata secondMetadata = metadataWith(99);
        when(secondRow.getMetadata()).thenReturn(secondMetadata);
        CapturingResult delegate = new CapturingResult();
        QuickPerfMonitoringResult monitoring = new QuickPerfMonitoringResult(delegate, qei);

        monitoring.map((Function<Readable, String>) r -> "v");
        delegate.invokeFunctionWith(firstRow);
        delegate.invokeFunctionWith(secondRow);

        assertThat(ColumnCountStore.drain(connectionId, qei)).isEqualTo(2L);
    }

    private static RowMetadata metadataWith(int columnCount) {
        ColumnMetadata column = mock(ColumnMetadata.class);
        List<ColumnMetadata> columns = columnCount == 0
                ? Collections.emptyList()
                : Arrays.asList(filledArray(columnCount, column));
        RowMetadata metadata = mock(RowMetadata.class);
        doReturn(columns).when(metadata).getColumnMetadatas();
        return metadata;
    }

    private static ColumnMetadata[] filledArray(int n, ColumnMetadata sample) {
        ColumnMetadata[] arr = new ColumnMetadata[n];
        Arrays.fill(arr, sample);
        return arr;
    }

    /**
     * A {@link Result} fake that captures the (instrumented) lambdas the
     * decorator passes to {@code map} / {@code flatMap} / {@code filter} so the
     * test can drive them with stub rows / segments.
     */
    private static final class CapturingResult implements Result {
        BiFunction<Row, RowMetadata, ?> capturedBiFunction;
        Function<? super Readable, ?> capturedFunction;
        Function<Segment, ? extends Publisher<?>> capturedFlatMap;
        Predicate<Segment> capturedFilter;
        Publisher<Long> rowsUpdated;

        @Override
        public Publisher<Long> getRowsUpdated() {
            return rowsUpdated;
        }

        @Override
        public <T> Publisher<T> map(BiFunction<Row, RowMetadata, ? extends T> bi) {
            capturedBiFunction = bi;
            return null;
        }

        @Override
        public <T> Publisher<T> map(Function<? super Readable, ? extends T> f) {
            capturedFunction = f;
            return null;
        }

        @Override
        public Result filter(Predicate<Segment> filter) {
            capturedFilter = filter;
            return this;
        }

        @Override
        public <T> Publisher<T> flatMap(Function<Segment, ? extends Publisher<? extends T>> f) {
            capturedFlatMap = f;
            return null;
        }

        @SuppressWarnings("unchecked")
        void invokeBiFunctionWith(Row row, RowMetadata metadata) {
            ((BiFunction<Row, RowMetadata, Object>) capturedBiFunction).apply(row, metadata);
        }

        @SuppressWarnings("unchecked")
        void invokeFunctionWith(Readable readable) {
            ((Function<Readable, Object>) capturedFunction).apply(readable);
        }

        void invokeFlatMapWith(Segment segment) {
            capturedFlatMap.apply(segment);
        }

        void invokeFilterWith(Segment segment) {
            capturedFilter.test(segment);
        }
    }
}
