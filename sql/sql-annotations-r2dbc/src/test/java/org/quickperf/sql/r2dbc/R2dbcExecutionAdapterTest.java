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
import io.r2dbc.proxy.core.BoundValue;
import io.r2dbc.proxy.core.ConnectionInfo;
import io.r2dbc.proxy.core.ExecutionType;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.proxy.test.MockConnectionInfo;
import io.r2dbc.proxy.test.MockQueryExecutionInfo;
import net.ttddyy.dsproxy.StatementType;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class R2dbcExecutionAdapterTest {

    @Test
    public void datasource_name_is_prefixed_to_distinguish_from_jdbc() {
        QueryExecutionInfo info = MockQueryExecutionInfo.builder()
                .queries(Collections.singletonList(new QueryInfo("SELECT 1")))
                .executeDuration(Duration.ofMillis(5))
                .type(ExecutionType.STATEMENT)
                .isSuccess(true)
                .build();

        R2dbcExecutionAdapter.Adapted adapted = R2dbcExecutionAdapter.adapt(info, "myFactory");

        assertThat(adapted.executionInfo.getDataSourceName()).isEqualTo("r2dbc:myFactory");
    }

    @Test
    public void connection_id_is_propagated_when_available() {
        ConnectionInfo conn = MockConnectionInfo.builder().connectionId("conn-42").build();
        QueryExecutionInfo info = MockQueryExecutionInfo.builder()
                .connectionInfo(conn)
                .queries(Collections.singletonList(new QueryInfo("SELECT 1")))
                .executeDuration(Duration.ofMillis(1))
                .type(ExecutionType.STATEMENT)
                .build();

        R2dbcExecutionAdapter.Adapted adapted = R2dbcExecutionAdapter.adapt(info, "cf");

        assertThat(adapted.executionInfo.getConnectionId()).isEqualTo("conn-42");
    }

    @Test
    public void statement_with_no_bindings_is_classified_as_STATEMENT() {
        QueryExecutionInfo info = MockQueryExecutionInfo.builder()
                .queries(Collections.singletonList(new QueryInfo("SELECT 1")))
                .executeDuration(Duration.ZERO)
                .type(ExecutionType.STATEMENT)
                .build();

        R2dbcExecutionAdapter.Adapted adapted = R2dbcExecutionAdapter.adapt(info, "cf");

        assertThat(adapted.executionInfo.getStatementType()).isEqualTo(StatementType.STATEMENT);
    }

    @Test
    public void statement_with_bindings_is_classified_as_PREPARED() {
        QueryInfo q = new QueryInfo("SELECT * FROM book WHERE id = $1");
        Bindings binding = new Bindings();
        binding.addIndexBinding(Bindings.indexBinding(0, BoundValue.value(1L)));
        q.getBindingsList().add(binding);

        QueryExecutionInfo info = MockQueryExecutionInfo.builder()
                .queries(Collections.singletonList(q))
                .executeDuration(Duration.ofMillis(2))
                .type(ExecutionType.STATEMENT)
                .build();

        R2dbcExecutionAdapter.Adapted adapted = R2dbcExecutionAdapter.adapt(info, "cf");

        assertThat(adapted.executionInfo.getStatementType()).isEqualTo(StatementType.PREPARED);
    }

    @Test
    public void execution_type_BATCH_marks_executionInfo_as_batch() {
        QueryExecutionInfo info = MockQueryExecutionInfo.builder()
                .queries(Arrays.asList(new QueryInfo("INSERT INTO t VALUES (1)"),
                                       new QueryInfo("INSERT INTO t VALUES (2)")))
                .type(ExecutionType.BATCH)
                .batchSize(2)
                .executeDuration(Duration.ofMillis(3))
                .build();

        R2dbcExecutionAdapter.Adapted adapted = R2dbcExecutionAdapter.adapt(info, "cf");

        assertThat(adapted.executionInfo.isBatch()).isTrue();
        assertThat(adapted.executionInfo.getBatchSize()).isEqualTo(2);
    }

    @Test
    public void elapsed_time_is_propagated_in_milliseconds() {
        QueryExecutionInfo info = MockQueryExecutionInfo.builder()
                .queries(Collections.singletonList(new QueryInfo("SELECT 1")))
                .executeDuration(Duration.ofMillis(123))
                .type(ExecutionType.STATEMENT)
                .build();

        R2dbcExecutionAdapter.Adapted adapted = R2dbcExecutionAdapter.adapt(info, "cf");

        assertThat(adapted.executionInfo.getElapsedTime()).isEqualTo(123L);
    }

    @Test
    public void result_is_left_null_when_no_column_count_was_recorded() {
        QueryExecutionInfo info = MockQueryExecutionInfo.builder()
                .queries(Collections.singletonList(new QueryInfo("SELECT 1")))
                .executeDuration(Duration.ZERO)
                .type(ExecutionType.STATEMENT)
                .build();

        R2dbcExecutionAdapter.Adapted adapted = R2dbcExecutionAdapter.adapt(info, "cf");

        assertThat(adapted.executionInfo.getResult()).isNull();
    }

    @Test
    public void result_is_a_synthetic_resultset_when_a_column_count_was_recorded() throws java.sql.SQLException {
        String connectionId = "conn-" + java.util.UUID.randomUUID();
        ConnectionInfo conn = MockConnectionInfo.builder().connectionId(connectionId).build();
        QueryExecutionInfo info = MockQueryExecutionInfo.builder()
                .connectionInfo(conn)
                .queries(Collections.singletonList(new QueryInfo("SELECT a, b, c FROM t")))
                .executeDuration(Duration.ZERO)
                .type(ExecutionType.STATEMENT)
                .build();

        ColumnCountStore.recordOnce(connectionId, info, 3L);
        R2dbcExecutionAdapter.Adapted adapted = R2dbcExecutionAdapter.adapt(info, "cf");

        Object result = adapted.executionInfo.getResult();
        assertThat(result).isInstanceOf(java.sql.ResultSet.class);
        java.sql.ResultSet rs = (java.sql.ResultSet) result;
        assertThat(rs.getMetaData().getColumnCount()).isEqualTo(3);
        // store entry for this (connectionId, qei) was drained
        assertThat(ColumnCountStore.drain(connectionId, info)).isZero();
    }

    @Test
    public void query_sql_is_rewritten_with_question_marks() {
        QueryInfo q = new QueryInfo("SELECT * FROM book WHERE id = $1");
        Bindings binding = new Bindings();
        binding.addIndexBinding(Bindings.indexBinding(0, BoundValue.value(7L)));
        q.getBindingsList().add(binding);

        QueryExecutionInfo info = MockQueryExecutionInfo.builder()
                .queries(Collections.singletonList(q))
                .type(ExecutionType.STATEMENT)
                .executeDuration(Duration.ZERO)
                .build();

        R2dbcExecutionAdapter.Adapted adapted = R2dbcExecutionAdapter.adapt(info, "cf");

        List<net.ttddyy.dsproxy.QueryInfo> queries = adapted.queries;
        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).getQuery()).isEqualTo("SELECT * FROM book WHERE id = ?");
        assertThat(queries.get(0).getParametersList()).hasSize(1);
        assertThat(queries.get(0).getParametersList().get(0).get(0).getArgs())
                .containsExactly(1, 7L);
    }

    @Test
    public void null_bean_name_falls_back_to_default() {
        QueryExecutionInfo info = MockQueryExecutionInfo.builder()
                .queries(Collections.singletonList(new QueryInfo("SELECT 1")))
                .executeDuration(Duration.ZERO)
                .type(ExecutionType.STATEMENT)
                .build();

        R2dbcExecutionAdapter.Adapted adapted = R2dbcExecutionAdapter.adapt(info, null);

        assertThat(adapted.executionInfo.getDataSourceName()).isEqualTo("r2dbc:connectionFactory");
    }

}
