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

/**
 * Source-neutral connection-lifecycle listener consumed by both JDBC and R2DBC
 * recording paths.
 *
 * <p>Existing JDBC listeners do not need to implement this interface directly;
 * the abstract {@link ConnectionListener} class provides no-op implementations
 * of every method, so subclasses that only care about the legacy JDBC-typed
 * callbacks remain source-compatible.
 *
 * <p>R2DBC-only callers should subclass {@link ConnectionListener} too rather
 * than implementing this interface from scratch — that way they receive empty
 * defaults for the methods they do not care about.
 *
 * <p>Implementations are dispatched to from arbitrary threads (Reactor
 * scheduler workers for R2DBC, the test thread for JDBC). Implementations
 * must serialize their own state.
 */
public interface SqlConnectionListener {

    void onConnectionAcquired(SqlConnectionEvent event);

    void onConnectionReleased(SqlConnectionEvent event);

    void onTransactionBegan(SqlConnectionEvent event);

    void onTransactionCommitted(SqlConnectionEvent event);

    void onTransactionRolledBack(SqlConnectionEvent event);

    void onAutoCommitChanged(SqlConnectionEvent event, boolean autoCommit);

    void onIsolationLevelChanged(SqlConnectionEvent event, String level);

    void onSavepointCreated(SqlConnectionEvent event, String name);

    void onSavepointReleased(SqlConnectionEvent event, String name);

    void onSavepointRolledBack(SqlConnectionEvent event, String name);

}
