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

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PlaceholderRewriterTest {

    @Test
    public void rewrites_positional_placeholders_to_question_marks_and_records_zero_based_keys() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT * FROM book WHERE id = $1 AND author = $2");

        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT * FROM book WHERE id = ? AND author = ?");
        assertThat(result.orderedKeys()).containsExactly("0", "1");
    }

    @Test
    public void rewrites_named_placeholders_to_question_marks_in_source_order() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT * FROM book WHERE author = :author AND year > :year");

        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT * FROM book WHERE author = ? AND year > ?");
        assertThat(result.orderedKeys()).containsExactly("author", "year");
    }

    @Test
    public void source_order_for_named_placeholders_is_not_alphabetical() {
        // Named bindings are exposed by r2dbc-proxy as a SortedSet (alphabetical by key).
        // The rewriter must capture the SQL source order so the binding adapter can
        // reorder values to match what the SQL actually expects.
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT * FROM book WHERE z = :z AND a = :a");

        assertThat(result.orderedKeys()).containsExactly("z", "a");
    }

    @Test
    public void preserves_postgres_double_colon_cast_operator() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT id::int FROM book WHERE author = :author");

        assertThat(result.rewrittenSql()).isEqualTo("SELECT id::int FROM book WHERE author = ?");
        assertThat(result.orderedKeys()).containsExactly("author");
    }

    @Test
    public void records_documented_mis_fire_on_string_literals_containing_colon_word() {
        // v1 limitation: literal scanning is not implemented. This test pins
        // the current behavior so any future fix will surface here.
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT 'literal :not_a_binding' FROM book");

        assertThat(result.orderedKeys()).containsExactly("not_a_binding");
        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT 'literal ?' FROM book");
    }

    @Test
    public void mixed_named_and_positional_preserves_per_type_order() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT * FROM t WHERE a = :a AND b = $1");

        // v1 limitation: across-type interleaving is not supported. The rewrite
        // is two-pass (named first, then positional), so the keys list reflects
        // both passes concatenated, not the original source-order interleave.
        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT * FROM t WHERE a = ? AND b = ?");
        assertThat(result.orderedKeys()).containsExactly("a", "0");
    }

    @Test
    public void empty_or_null_input_is_handled_gracefully() {
        assertThat(PlaceholderRewriter.rewrite("").orderedKeys()).isEmpty();
        assertThat(PlaceholderRewriter.rewrite(null).orderedKeys()).isEmpty();
    }

    @Test
    public void no_placeholders_returns_sql_unchanged_and_empty_keys() {
        String sql = "SELECT 1";
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(sql);

        assertThat(result.rewrittenSql()).isEqualTo(sql);
        List<String> keys = result.orderedKeys();
        assertThat(keys).isEmpty();
    }

}
