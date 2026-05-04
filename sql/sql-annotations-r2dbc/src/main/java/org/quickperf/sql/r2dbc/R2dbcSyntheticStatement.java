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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

/**
 * JDBC-shaped synthetic {@link ResultSet} factory that lets
 * {@link R2dbcExecutionAdapter} feed an R2DBC column count into a
 * {@link net.ttddyy.dsproxy.ExecutionInfo} without modifying
 * {@link org.quickperf.sql.SqlExecution}.
 *
 * <p>{@link org.quickperf.sql.SqlExecution} retrieves the column count by
 * casting {@code ExecutionInfo#getResult()} to {@link ResultSet} and calling
 * {@code resultSet.getMetaData().getColumnCount()}. This class produces a
 * minimal {@link ResultSet} JDK proxy whose only meaningful behaviour is
 * exposing the previously-drained column count via that exact path. Every
 * other {@code ResultSet} / {@code ResultSetMetaData} method throws
 * {@link UnsupportedOperationException} so any accidental access surfaces
 * loudly during development.
 *
 * <p>The class is named {@code R2dbcSyntheticStatement} to follow the v3 R2DBC
 * plan, but in practice {@link org.quickperf.sql.SqlExecution} only ever
 * traverses the {@link ResultSet}; no JDBC {@code Statement} is needed.
 */
final class R2dbcSyntheticStatement {

    private R2dbcSyntheticStatement() {}

    /**
     * Build a {@link ResultSet} JDK proxy that returns {@code columnCount}
     * via {@code getMetaData().getColumnCount()} and rejects every other call.
     */
    static ResultSet resultWithColumnCount(final long columnCount) {
        final ResultSetMetaData metaData = (ResultSetMetaData) Proxy.newProxyInstance(
                R2dbcSyntheticStatement.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getColumnCount".equals(method.getName())) {
                            return (int) columnCount;
                        }
                        if ("toString".equals(method.getName())) {
                            return "R2dbcSyntheticResultSetMetaData(columnCount=" + columnCount + ")";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        throw new UnsupportedOperationException(
                                "R2dbcSyntheticResultSetMetaData does not support " + method.getName());
                    }
                });
        return (ResultSet) Proxy.newProxyInstance(
                R2dbcSyntheticStatement.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getMetaData".equals(method.getName())) {
                            return metaData;
                        }
                        if ("toString".equals(method.getName())) {
                            return "R2dbcSyntheticResultSet(columnCount=" + columnCount + ")";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        throw new UnsupportedOperationException(
                                "R2dbcSyntheticResultSet does not support " + method.getName());
                    }
                });
    }
}
