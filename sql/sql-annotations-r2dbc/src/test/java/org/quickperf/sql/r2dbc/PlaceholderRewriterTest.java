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
    public void preserves_string_literal_containing_colon_word() {
        // PR-1 fix: the state-machine scanner does NOT rewrite content inside
        // ' '. Previously, the regex-based v1 mis-fired here.
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT 'literal :not_a_binding' FROM book");

        assertThat(result.orderedKeys()).isEmpty();
        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT 'literal :not_a_binding' FROM book");
    }

    @Test
    public void mixed_named_and_positional_preserves_per_type_order() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT * FROM t WHERE a = :a AND b = $1");

        // The single-pass scanner now reports placeholders in true source order,
        // so :a comes before $1.
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

    @Test
    public void question_mark_placeholders_produce_zero_based_positional_keys() {
        // PR-1: ?-only SQL now produces a non-empty orderedKeys, so @DisplaySql
        // can substitute the bound values.
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "INSERT INTO book(id, author) VALUES (?, ?)");

        assertThat(result.rewrittenSql())
                .isEqualTo("INSERT INTO book(id, author) VALUES (?, ?)");
        assertThat(result.orderedKeys()).containsExactly("0", "1");
    }

    @Test
    public void spring_style_question_n_placeholders_use_the_literal_digit_value() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "INSERT INTO book(id, author) VALUES (?1, ?2)");

        assertThat(result.rewrittenSql())
                .isEqualTo("INSERT INTO book(id, author) VALUES (?, ?)");
        assertThat(result.orderedKeys()).containsExactly("1", "2");
    }

    @Test
    public void colon_word_inside_double_quoted_identifier_is_preserved() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT \"col:weird\" FROM t WHERE id = :id");

        assertThat(result.rewrittenSql()).isEqualTo("SELECT \"col:weird\" FROM t WHERE id = ?");
        assertThat(result.orderedKeys()).containsExactly("id");
    }

    @Test
    public void embedded_quote_escape_inside_string_is_preserved() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT * FROM t WHERE name = 'O''Brien' AND id = :id");

        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT * FROM t WHERE name = 'O''Brien' AND id = ?");
        assertThat(result.orderedKeys()).containsExactly("id");
    }

    @Test
    public void question_mark_inside_string_literal_is_not_treated_as_placeholder() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT 'unknown?' FROM t WHERE id = ?");

        assertThat(result.rewrittenSql()).isEqualTo("SELECT 'unknown?' FROM t WHERE id = ?");
        assertThat(result.orderedKeys()).containsExactly("0");
    }

    @Test
    public void line_comment_content_is_preserved_and_not_scanned_for_placeholders() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT * FROM t -- WHERE id = :ignored\nWHERE id = :id");

        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT * FROM t -- WHERE id = :ignored\nWHERE id = ?");
        assertThat(result.orderedKeys()).containsExactly("id");
    }

    @Test
    public void block_comment_content_is_preserved_and_not_scanned_for_placeholders() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT /* :ignored AND ?ignored */ * FROM t WHERE id = :id");

        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT /* :ignored AND ?ignored */ * FROM t WHERE id = ?");
        assertThat(result.orderedKeys()).containsExactly("id");
    }

    @Test
    public void postgres_e_string_with_backslash_escape_is_preserved() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT E'a\\'b :ignored' FROM t WHERE id = :id");

        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT E'a\\'b :ignored' FROM t WHERE id = ?");
        assertThat(result.orderedKeys()).containsExactly("id");
    }

    @Test
    public void postgres_unicode_string_is_preserved() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT U&'\\00E9 :ignored' FROM t WHERE id = :id");

        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT U&'\\00E9 :ignored' FROM t WHERE id = ?");
        assertThat(result.orderedKeys()).containsExactly("id");
    }

    @Test
    public void postgres_dollar_quoted_string_is_preserved() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT $$body :ignored ?ignored$$ FROM t WHERE id = :id");

        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT $$body :ignored ?ignored$$ FROM t WHERE id = ?");
        assertThat(result.orderedKeys()).containsExactly("id");
    }

    @Test
    public void postgres_dollar_quoted_string_with_tag_is_preserved() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT $tag$body :ignored ?ignored$tag$ FROM t WHERE id = :id");

        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT $tag$body :ignored ?ignored$tag$ FROM t WHERE id = ?");
        assertThat(result.orderedKeys()).containsExactly("id");
    }

    @Test
    public void postgres_dollar_quoted_strings_can_nest_via_distinct_tags() {
        PlaceholderRewriter.Result result = PlaceholderRewriter.rewrite(
                "SELECT $a$ outer $b$ :ignored $b$ outer $a$ FROM t WHERE id = :id");

        assertThat(result.rewrittenSql())
                .isEqualTo("SELECT $a$ outer $b$ :ignored $b$ outer $a$ FROM t WHERE id = ?");
        assertThat(result.orderedKeys()).containsExactly("id");
    }

}
