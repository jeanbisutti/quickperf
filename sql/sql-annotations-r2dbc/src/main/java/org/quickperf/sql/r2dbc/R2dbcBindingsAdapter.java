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

import io.r2dbc.proxy.core.Binding;
import io.r2dbc.proxy.core.Bindings;
import io.r2dbc.proxy.core.BoundValue;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts r2dbc-proxy {@link Bindings} into datasource-proxy
 * {@link ParameterSetOperation} lists, in the order placeholders appear in the
 * original SQL (see {@link PlaceholderRewriter}).
 *
 * <p>This drives faithful {@code @DisplaySql} output and keeps
 * {@code AllParametersAreBoundExtractor} honest across positional and named
 * placeholder dialects.
 */
final class R2dbcBindingsAdapter {

    /** {@link PreparedStatement#setObject(int, Object)} captured once via reflection. */
    private static final Method SET_OBJECT_METHOD;
    static {
        try {
            SET_OBJECT_METHOD = PreparedStatement.class.getMethod("setObject", int.class, Object.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private R2dbcBindingsAdapter() {}

    /**
     * Build a parameter set list for a single {@link Bindings}, in the order of {@code orderedKeys}.
     *
     * @param bindings    r2dbc-proxy bindings (one per executed binding tuple).
     * @param orderedKeys placeholder keys in source order (see {@link PlaceholderRewriter}).
     * @return a list of {@link ParameterSetOperation} usable by datasource-proxy formatters and verifiers.
     */
    static List<ParameterSetOperation> toParameterSet(Bindings bindings, List<String> orderedKeys) {
        if (orderedKeys == null || orderedKeys.isEmpty() || bindings == null) {
            return Collections.emptyList();
        }
        List<ParameterSetOperation> ops = new ArrayList<ParameterSetOperation>(orderedKeys.size());
        int displayIndex = 1;
        for (String key : orderedKeys) {
            BoundValue bv = lookup(bindings, key);
            Object value = bv.isNull() ? null : bv.getValue();
            ops.add(new ParameterSetOperation(SET_OBJECT_METHOD, new Object[]{ displayIndex++, value }));
        }
        return ops;
    }

    /**
     * Build the datasource-proxy {@code parametersList} for an entire query.
     *
     * @param bindingsList list of {@link Bindings}, one per binding tuple (e.g. for batched executions).
     * @param orderedKeys  placeholder keys in source order (shared across all bindings).
     */
    static List<List<ParameterSetOperation>> toParametersList(List<Bindings> bindingsList,
                                                              List<String> orderedKeys) {
        if (bindingsList == null || bindingsList.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<ParameterSetOperation>> all = new ArrayList<List<ParameterSetOperation>>(bindingsList.size());
        for (Bindings b : bindingsList) {
            all.add(toParameterSet(b, orderedKeys));
        }
        return all;
    }

    private static BoundValue lookup(Bindings bindings, String key) {
        if (isNumeric(key)) {
            int index = Integer.parseInt(key);
            BoundValue byZero = findIndex(bindings, index);
            if (byZero != null) {
                return byZero;
            }
            BoundValue byOne = findIndex(bindings, index + 1);
            if (byOne != null) {
                return byOne;
            }
        } else {
            for (Binding b : bindings.getNamedBindings()) {
                if (key.equals(b.getKey())) {
                    return b.getBoundValue();
                }
            }
        }
        return BoundValue.nullValue(Object.class);
    }

    private static BoundValue findIndex(Bindings bindings, int index) {
        for (Binding b : bindings.getIndexBindings()) {
            Object key = b.getKey();
            if (key instanceof Integer && ((Integer) key).intValue() == index) {
                return b.getBoundValue();
            }
        }
        return null;
    }

    private static boolean isNumeric(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

}
