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

import static org.assertj.core.api.Assertions.assertThat;

public class ConnectionListenerSqlBridgeTest {

    @Test public void
    connection_listener_should_implement_sql_connection_listener() {

        assertThat(SqlConnectionListener.class).isAssignableFrom(ConnectionListener.class);
    }

    @Test public void
    abstract_connection_listener_no_op_methods_should_not_throw() {

        ConnectionListener listener = new ConnectionListener() { };

        SqlConnectionEvent event = SqlConnectionEvent.jdbc("jdbc-1");

        listener.onConnectionAcquired(event);
        listener.onConnectionReleased(event);
        listener.onTransactionBegan(event);
        listener.onTransactionCommitted(event);
        listener.onTransactionRolledBack(event);
        listener.onAutoCommitChanged(event, true);
        listener.onIsolationLevelChanged(event, "SERIALIZABLE");
        listener.onSavepointCreated(event, "sp1");
        listener.onSavepointReleased(event, "sp1");
        listener.onSavepointRolledBack(event, "sp1");
    }

}
