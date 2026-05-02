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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites R2DBC SQL placeholders ({@code $N}, {@code :name}) to JDBC-style {@code ?}
 * markers while preserving placeholder source order.
 *
 * <p>Source order is captured because {@link io.r2dbc.proxy.core.Bindings#getNamedBindings()}
 * returns a {@link java.util.SortedSet} sorted alphabetically by key, which would
 * reorder values relative to the SQL source if the binding adapter iterated the
 * set directly.
 *
 * <p><b>Limitations (v1):</b>
 * <ul>
 *   <li>String literals and SQL comments containing {@code :foo}-shaped patterns
 *       are not escaped; the rewrite will mis-fire for SQL like
 *       {@code 'literal :not_a_binding'}.</li>
 *   <li>Mixed positional ({@code $N}) and named ({@code :name}) placeholders in
 *       a single statement are not supported: the two-pass rewrite preserves order
 *       within one type but not across types. No real R2DBC driver mixes them.</li>
 * </ul>
 * Both limitations are documented in the starter README and are tracked as v2 work.
 */
final class PlaceholderRewriter {

    /** Matches positional placeholders like {@code $1}, {@code $42}. */
    private static final Pattern POSITIONAL = Pattern.compile("\\$\\d+");

    /**
     * Matches named placeholders like {@code :name}, NOT preceded by another
     * colon (so the PostgreSQL cast operator {@code column::int} is preserved).
     */
    private static final Pattern NAMED = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");

    private PlaceholderRewriter() {}

    /** Result of a rewrite: the rewritten SQL plus the source-order list of placeholder keys. */
    static final class Result {
        private final String rewrittenSql;
        private final List<String> orderedKeys;

        Result(String rewrittenSql, List<String> orderedKeys) {
            this.rewrittenSql = rewrittenSql;
            this.orderedKeys = orderedKeys;
        }

        String rewrittenSql() {
            return rewrittenSql;
        }

        /**
         * Source order of placeholder keys: numeric strings ({@code "0"}, {@code "1"}, …)
         * for positional bindings, identifier strings for named bindings.
         */
        List<String> orderedKeys() {
            return Collections.unmodifiableList(orderedKeys);
        }
    }

    static Result rewrite(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new Result(sql == null ? "" : sql, Collections.<String>emptyList());
        }

        List<String> orderedKeys = new ArrayList<String>();

        StringBuffer out = new StringBuffer(sql.length());
        Matcher named = NAMED.matcher(sql);
        while (named.find()) {
            orderedKeys.add(named.group(1));
            named.appendReplacement(out, "?");
        }
        named.appendTail(out);
        String afterNamed = out.toString();

        StringBuffer out2 = new StringBuffer(afterNamed.length());
        Matcher positional = POSITIONAL.matcher(afterNamed);
        int positionalIndex = 0;
        while (positional.find()) {
            orderedKeys.add(Integer.toString(positionalIndex++));
            positional.appendReplacement(out2, "?");
        }
        positional.appendTail(out2);

        return new Result(out2.toString(), orderedKeys);
    }

}
