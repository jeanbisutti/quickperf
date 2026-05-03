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
package org.quickperf.sql.connection;

import org.junit.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SqlConnectionEventTest {

    @Test public void
    jdbc_factory_should_carry_jdbc_source_and_no_stack_trace_marker() {

        SqlConnectionEvent event = SqlConnectionEvent.jdbc("jdbc-1");

        assertThat(event.getConnectionId()).isEqualTo("jdbc-1");
        assertThat(event.getSource()).isEqualTo(SqlConnectionEvent.Source.JDBC);
        assertThat(event.getStackTraceMarker()).isNull();
        assertThat(event.getTimestampMillis()).isGreaterThan(0L);
    }

    @Test public void
    r2dbc_factory_should_carry_r2dbc_source() {

        SqlConnectionEvent event = SqlConnectionEvent.r2dbc("r2dbc-7");

        assertThat(event.getConnectionId()).isEqualTo("r2dbc-7");
        assertThat(event.getSource()).isEqualTo(SqlConnectionEvent.Source.R2DBC);
    }

    @Test public void
    factory_with_marker_should_keep_the_supplied_throwable() {

        Throwable marker = new RuntimeException("acquired here");

        SqlConnectionEvent event = SqlConnectionEvent.jdbc("jdbc-2", marker);

        assertThat(event.getStackTraceMarker()).isSameAs(marker);
    }

    @Test public void
    null_connection_id_should_be_rejected() {

        ThrowingCallable buildWithNullId = new ThrowingCallable() {
            @Override public void call() {
                SqlConnectionEvent.jdbc(null);
            }
        };
        assertThatThrownBy(buildWithNullId)
                .isInstanceOf(IllegalArgumentException.class);
    }

}
