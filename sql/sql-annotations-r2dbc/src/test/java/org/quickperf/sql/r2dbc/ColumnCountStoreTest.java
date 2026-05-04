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
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The QuickPerf module under test runs Surefire with {@code parallel=all,threadCount=5}.
 * {@link ColumnCountStore} is JVM-static; to keep tests isolated under parallel
 * execution each test uses a unique connection id (UUID) and asserts only on
 * its own keys — never on the store's global size.
 */
public class ColumnCountStoreTest {

    private String connectionId;
    private QueryExecutionInfo info;

    @Before
    public void setUp() {
        connectionId = "conn-" + UUID.randomUUID();
        ConnectionInfo conn = MockConnectionInfo.builder().connectionId(connectionId).build();
        info = MockQueryExecutionInfo.builder().connectionInfo(conn).build();
    }

    @Test
    public void drain_returns_zero_when_nothing_was_recorded() {
        assertThat(ColumnCountStore.drain(connectionId, info)).isZero();
    }

    @Test
    public void recordOnce_then_drain_returns_recorded_value() {
        ColumnCountStore.recordOnce(connectionId, info, 5L);

        assertThat(ColumnCountStore.drain(connectionId, info)).isEqualTo(5L);
    }

    @Test
    public void drain_removes_the_entry_so_a_second_drain_returns_zero() {
        ColumnCountStore.recordOnce(connectionId, info, 5L);
        ColumnCountStore.drain(connectionId, info);

        assertThat(ColumnCountStore.drain(connectionId, info)).isZero();
    }

    @Test
    public void recordOnce_is_idempotent_first_writer_wins() {
        ColumnCountStore.recordOnce(connectionId, info, 3L);
        ColumnCountStore.recordOnce(connectionId, info, 99L);

        assertThat(ColumnCountStore.drain(connectionId, info)).isEqualTo(3L);
    }

    @Test
    public void different_query_executions_have_separate_keys_even_on_same_connection() {
        ConnectionInfo conn = MockConnectionInfo.builder().connectionId(connectionId).build();
        QueryExecutionInfo other = MockQueryExecutionInfo.builder().connectionInfo(conn).build();

        ColumnCountStore.recordOnce(connectionId, info, 3L);
        ColumnCountStore.recordOnce(connectionId, other, 7L);

        assertThat(ColumnCountStore.drain(connectionId, info)).isEqualTo(3L);
        assertThat(ColumnCountStore.drain(connectionId, other)).isEqualTo(7L);
    }

    @Test
    public void recordOnce_with_null_qei_is_a_noop() {
        ColumnCountStore.recordOnce(connectionId, null, 5L);

        assertThat(ColumnCountStore.drain(connectionId, null)).isZero();
    }
}
