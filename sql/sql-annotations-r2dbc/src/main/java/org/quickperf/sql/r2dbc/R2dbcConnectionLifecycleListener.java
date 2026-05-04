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
import io.r2dbc.proxy.core.MethodExecutionInfo;
import io.r2dbc.proxy.listener.ProxyExecutionListener;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.quickperf.sql.connection.ConnectionListenerHook;
import org.quickperf.sql.connection.SqlConnectionEvent;
import org.quickperf.sql.connection.SqlConnectionListener;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * R2DBC connection lifecycle listener that bridges r2dbc-proxy
 * {@link ProxyExecutionListener#beforeMethod(MethodExecutionInfo)} and
 * {@link ProxyExecutionListener#afterMethod(MethodExecutionInfo)} callbacks
 * into QuickPerf's neutral {@link SqlConnectionListener} dispatch via
 * {@link ConnectionListenerHook}.
 *
 * <p>Connection ids are taken from
 * {@link ConnectionInfo#getConnectionId()} (r2dbc-proxy assigns the same id
 * to every event emitted for the same connection) and prefixed with
 * {@code "r2dbc-"} to disambiguate from JDBC ids.
 *
 * <h2>Method coverage</h2>
 * <ul>
 *   <li>{@code ConnectionFactory.create()} (success) &rarr;
 *       {@link SqlConnectionListener#onConnectionAcquired}</li>
 *   <li>{@code Connection.close()} (before delegate so the id is still
 *       recoverable from {@link MethodExecutionInfo#getConnectionInfo()})
 *       &rarr; {@link SqlConnectionListener#onConnectionReleased}</li>
 *   <li>{@code Connection.beginTransaction()} / {@code beginTransaction(TransactionDefinition)}
 *       (success) &rarr; {@link SqlConnectionListener#onTransactionBegan}</li>
 *   <li>{@code Connection.commitTransaction()} (success) &rarr;
 *       {@link SqlConnectionListener#onTransactionCommitted}</li>
 *   <li>{@code Connection.rollbackTransaction()} (success) &rarr;
 *       {@link SqlConnectionListener#onTransactionRolledBack}</li>
 *   <li>{@code Connection.setAutoCommit(boolean)} (success) &rarr;
 *       {@link SqlConnectionListener#onAutoCommitChanged}</li>
 *   <li>{@code Connection.setTransactionIsolationLevel(IsolationLevel)} (success) &rarr;
 *       {@link SqlConnectionListener#onIsolationLevelChanged} (level rendered
 *       via {@link String#valueOf(Object)})</li>
 *   <li>{@code Connection.createSavepoint(String)} (success) &rarr;
 *       {@link SqlConnectionListener#onSavepointCreated}</li>
 *   <li>{@code Connection.releaseSavepoint(String)} (success) &rarr;
 *       {@link SqlConnectionListener#onSavepointReleased}</li>
 *   <li>{@code Connection.rollbackTransactionToSavepoint(String)} (success) &rarr;
 *       {@link SqlConnectionListener#onSavepointRolledBack}</li>
 * </ul>
 *
 * <p>Methods that throw (i.e. {@link MethodExecutionInfo#getThrown()} returns
 * non-{@code null}) do not emit lifecycle events, mirroring the JDBC neutral
 * dispatch which fires after a successful delegate return.
 *
 * <p>Reactor schedulers may dispatch this listener from any worker thread;
 * the listener itself is stateless, and {@link ConnectionListenerHook} backs
 * its registry with a {@link java.util.concurrent.CopyOnWriteArraySet} so
 * iteration is safe under concurrent registration. Listener implementations
 * are responsible for serializing their own state (see
 * {@link ConnectionListenerHook} javadoc).
 */
public final class R2dbcConnectionLifecycleListener implements ProxyExecutionListener {

    private static final String R2DBC_ID_PREFIX = "r2dbc-";

    private final Supplier<Iterable<SqlConnectionListener>> listenersSupplier;

    /**
     * Construct a listener attached to the JVM-global
     * {@link ConnectionListenerHook}.
     */
    public R2dbcConnectionLifecycleListener() {
        this(new Supplier<Iterable<SqlConnectionListener>>() {
            @Override
            public Iterable<SqlConnectionListener> get() {
                return ConnectionListenerHook.getActiveListeners();
            }
        });
    }

    /**
     * Construct a listener with a custom listener source. Intended for unit
     * tests that need to keep registration scoped per test under
     * {@code parallel=all}.
     */
    public R2dbcConnectionLifecycleListener(Supplier<Iterable<SqlConnectionListener>> listenersSupplier) {
        if (listenersSupplier == null) {
            throw new IllegalArgumentException("listenersSupplier must not be null");
        }
        this.listenersSupplier = listenersSupplier;
    }

    @Override
    public void beforeMethod(MethodExecutionInfo info) {
        if (info == null) {
            return;
        }
        Method method = info.getMethod();
        if (method == null) {
            return;
        }
        if (isConnectionMethod(method) && "close".equals(method.getName())) {
            String connectionId = connectionIdFrom(info);
            if (connectionId == null) {
                return;
            }
            SqlConnectionEvent event = SqlConnectionEvent.r2dbc(connectionId);
            for (SqlConnectionListener listener : listenersSupplier.get()) {
                listener.onConnectionReleased(event);
            }
        }
    }

    @Override
    public void afterMethod(MethodExecutionInfo info) {
        if (info == null || info.getThrown() != null) {
            return;
        }
        Method method = info.getMethod();
        if (method == null) {
            return;
        }
        Class<?> declaringClass = method.getDeclaringClass();
        String methodName = method.getName();

        if (declaringClass == ConnectionFactory.class && "create".equals(methodName)) {
            dispatchAcquired(info);
            return;
        }
        if (!isConnectionMethod(method)) {
            return;
        }
        switch (methodName) {
            case "beginTransaction":
                dispatchTransaction(info, TxKind.BEGAN);
                return;
            case "commitTransaction":
                dispatchTransaction(info, TxKind.COMMITTED);
                return;
            case "rollbackTransaction":
                dispatchTransaction(info, TxKind.ROLLED_BACK);
                return;
            case "setAutoCommit":
                Object[] autoCommitArgs = info.getMethodArgs();
                if (autoCommitArgs != null && autoCommitArgs.length > 0
                        && autoCommitArgs[0] instanceof Boolean) {
                    dispatchAutoCommitChanged(info, (Boolean) autoCommitArgs[0]);
                }
                return;
            case "setTransactionIsolationLevel":
                Object[] isoArgs = info.getMethodArgs();
                if (isoArgs != null && isoArgs.length > 0) {
                    dispatchIsolationChanged(info, String.valueOf(isoArgs[0]));
                }
                return;
            case "createSavepoint":
                dispatchSavepoint(info, SavepointKind.CREATED);
                return;
            case "releaseSavepoint":
                dispatchSavepoint(info, SavepointKind.RELEASED);
                return;
            case "rollbackTransactionToSavepoint":
                dispatchSavepoint(info, SavepointKind.ROLLED_BACK);
                return;
            default:
                // ignore other methods (createStatement, getMetadata, validate, ...)
        }
    }

    private static boolean isConnectionMethod(Method method) {
        return Connection.class.isAssignableFrom(method.getDeclaringClass());
    }

    private static String connectionIdFrom(MethodExecutionInfo info) {
        ConnectionInfo connectionInfo = info.getConnectionInfo();
        if (connectionInfo == null) {
            return null;
        }
        String id = connectionInfo.getConnectionId();
        if (id == null) {
            return null;
        }
        return R2DBC_ID_PREFIX + id;
    }

    private void dispatchAcquired(MethodExecutionInfo info) {
        String connectionId = connectionIdFrom(info);
        if (connectionId == null) {
            return;
        }
        SqlConnectionEvent event = SqlConnectionEvent.r2dbc(connectionId);
        for (SqlConnectionListener listener : listenersSupplier.get()) {
            listener.onConnectionAcquired(event);
        }
    }

    private void dispatchTransaction(MethodExecutionInfo info, TxKind kind) {
        String connectionId = connectionIdFrom(info);
        if (connectionId == null) {
            return;
        }
        SqlConnectionEvent event = SqlConnectionEvent.r2dbc(connectionId);
        for (SqlConnectionListener listener : listenersSupplier.get()) {
            switch (kind) {
                case BEGAN:
                    listener.onTransactionBegan(event);
                    break;
                case COMMITTED:
                    listener.onTransactionCommitted(event);
                    break;
                case ROLLED_BACK:
                    listener.onTransactionRolledBack(event);
                    break;
            }
        }
    }

    private void dispatchAutoCommitChanged(MethodExecutionInfo info, boolean autoCommit) {
        String connectionId = connectionIdFrom(info);
        if (connectionId == null) {
            return;
        }
        SqlConnectionEvent event = SqlConnectionEvent.r2dbc(connectionId);
        for (SqlConnectionListener listener : listenersSupplier.get()) {
            listener.onAutoCommitChanged(event, autoCommit);
        }
    }

    private void dispatchIsolationChanged(MethodExecutionInfo info, String level) {
        String connectionId = connectionIdFrom(info);
        if (connectionId == null) {
            return;
        }
        SqlConnectionEvent event = SqlConnectionEvent.r2dbc(connectionId);
        for (SqlConnectionListener listener : listenersSupplier.get()) {
            listener.onIsolationLevelChanged(event, level);
        }
    }

    private void dispatchSavepoint(MethodExecutionInfo info, SavepointKind kind) {
        String connectionId = connectionIdFrom(info);
        if (connectionId == null) {
            return;
        }
        Object[] args = info.getMethodArgs();
        String name = (args != null && args.length > 0 && args[0] != null) ? args[0].toString() : null;
        SqlConnectionEvent event = SqlConnectionEvent.r2dbc(connectionId);
        for (SqlConnectionListener listener : listenersSupplier.get()) {
            switch (kind) {
                case CREATED:
                    listener.onSavepointCreated(event, name);
                    break;
                case RELEASED:
                    listener.onSavepointReleased(event, name);
                    break;
                case ROLLED_BACK:
                    listener.onSavepointRolledBack(event, name);
                    break;
            }
        }
    }

    private enum TxKind { BEGAN, COMMITTED, ROLLED_BACK }

    private enum SavepointKind { CREATED, RELEASED, ROLLED_BACK }

}
