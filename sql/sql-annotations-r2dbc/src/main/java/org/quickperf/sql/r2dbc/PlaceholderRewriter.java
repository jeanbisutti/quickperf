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

/**
 * Rewrites R2DBC SQL placeholders ({@code $N}, {@code :name}, {@code ?},
 * {@code ?N}) to JDBC-style {@code ?} markers while preserving placeholder
 * source order.
 *
 * <p>Source order matters because {@link io.r2dbc.proxy.core.Bindings#getNamedBindings()}
 * returns a {@link java.util.SortedSet} sorted alphabetically by key, which would
 * reorder values relative to the SQL source if the binding adapter iterated the
 * set directly.
 *
 * <p>The implementation is a single-pass character scanner that recognises
 * the following lexical contexts and skips placeholder substitution inside them:
 * <ul>
 *   <li>{@code '...'} string literals (with {@code ''} as the embedded-quote escape).</li>
 *   <li>{@code "..."} quoted identifiers (with {@code ""} as the embedded-quote escape).</li>
 *   <li>{@code --} line comments (terminated by {@code \n} or end-of-input).</li>
 *   <li>{@code /* ... *}{@code /} block comments (no nesting; terminated by the first {@code *}{@code /}).</li>
 *   <li>PostgreSQL {@code E'...'} extended strings, where {@code \\}, {@code \'} and any
 *       other backslash-escape sequence behaves as in standard SQL extended strings.</li>
 *   <li>PostgreSQL {@code U&'...'} Unicode strings (treated like a regular {@code '...'} body).</li>
 *   <li>PostgreSQL {@code $tag$ ... $tag$} dollar-quoted strings, where {@code tag} is empty
 *       or an identifier-like token.</li>
 * </ul>
 *
 * <p>Outside of those contexts, the scanner recognises:
 * <ul>
 *   <li>{@code ?} → emits a single {@code ?}, pushes a positional key (zero-based counter shared with {@code $N}).</li>
 *   <li>{@code ?N} (Spring-style positional, where {@code N} is one or more digits)
 *       → emits a single {@code ?}, pushes the literal digits as the key.</li>
 *   <li>{@code $N} (PostgreSQL-style positional) → emits {@code ?}, pushes the
 *       zero-based shared counter (matching the v1 behaviour).</li>
 *   <li>{@code :name} (named) → emits {@code ?}, pushes the identifier as the key. {@code ::}
 *       (PostgreSQL cast operator) is preserved verbatim.</li>
 * </ul>
 *
 * <p>The character-level scanner replaces the v1 two-pass regex implementation,
 * which broke when SQL contained string literals or comments holding {@code :foo}-shaped
 * substrings, and which dropped {@code ?} placeholders entirely (causing
 * {@code @DisplaySql} to render the wrong values for {@code ?}-only statements).
 *
 * <p>Out of scope for this scanner (deferred to future work):
 * MS SQL Server bracketed identifiers ({@code [id]}), Oracle alternative
 * quoting ({@code Q'⟨delim⟩…⟨delim⟩'}), and cross-string {@code E'...'}
 * backslash continuation across multiple adjacent extended strings.
 */
final class PlaceholderRewriter {

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

        StringBuilder out = new StringBuilder(sql.length());
        List<String> keys = new ArrayList<String>();
        int positionalCounter = 0;
        int len = sql.length();
        int i = 0;

        while (i < len) {
            char c = sql.charAt(i);

            if (c == '\'') {
                int end = scanSingleQuoted(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '"') {
                int end = scanDoubleQuoted(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int end = scanLineComment(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = scanBlockComment(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if ((c == 'E' || c == 'e') && i + 1 < len && sql.charAt(i + 1) == '\'') {
                int end = scanExtendedString(sql, i + 1);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if ((c == 'U' || c == 'u')
                    && i + 2 < len && sql.charAt(i + 1) == '&' && sql.charAt(i + 2) == '\'') {
                int end = scanSingleQuoted(sql, i + 2);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '$') {
                int next = i + 1;
                if (next < len && Character.isDigit(sql.charAt(next))) {
                    int j = next;
                    while (j < len && Character.isDigit(sql.charAt(j))) {
                        j++;
                    }
                    out.append('?');
                    keys.add(Integer.toString(positionalCounter++));
                    i = j;
                    continue;
                }
                int dollarEnd = tryScanDollarQuote(sql, i);
                if (dollarEnd > i) {
                    out.append(sql, i, dollarEnd);
                    i = dollarEnd;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            if (c == '?') {
                int next = i + 1;
                if (next < len && Character.isDigit(sql.charAt(next))) {
                    int j = next;
                    while (j < len && Character.isDigit(sql.charAt(j))) {
                        j++;
                    }
                    out.append('?');
                    keys.add(sql.substring(next, j));
                    i = j;
                } else {
                    out.append('?');
                    keys.add(Integer.toString(positionalCounter++));
                    i++;
                }
                continue;
            }
            if (c == ':') {
                if (i + 1 < len && sql.charAt(i + 1) == ':') {
                    out.append("::");
                    i += 2;
                    continue;
                }
                if (i + 1 < len && isNameStart(sql.charAt(i + 1))) {
                    int j = i + 1;
                    while (j < len && isNamePart(sql.charAt(j))) {
                        j++;
                    }
                    out.append('?');
                    keys.add(sql.substring(i + 1, j));
                    i = j;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }

            out.append(c);
            i++;
        }

        return new Result(out.toString(), keys);
    }

    /** Scans from the opening {@code '} (inclusive) and returns the index just past the closing {@code '}. */
    private static int scanSingleQuoted(String sql, int start) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    /** Scans from the opening {@code "} (inclusive) and returns the index just past the closing {@code "}. */
    private static int scanDoubleQuoted(String sql, int start) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '"') {
                if (i + 1 < len && sql.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    /** Scans from the leading {@code --} (inclusive) and returns the index just past the terminating newline (or EOF). */
    private static int scanLineComment(String sql, int start) {
        int len = sql.length();
        int i = start + 2;
        while (i < len && sql.charAt(i) != '\n') {
            i++;
        }
        if (i < len) {
            i++;
        }
        return i;
    }

    /** Scans from the leading {@code /} '*' (inclusive) and returns the index just past the terminating {@code *} '/'. */
    private static int scanBlockComment(String sql, int start) {
        int len = sql.length();
        int i = start + 2;
        while (i + 1 < len) {
            if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return len;
    }

    /** Scans a PostgreSQL {@code E'...'} extended string from the opening quote (inclusive). */
    private static int scanExtendedString(String sql, int quoteIndex) {
        int len = sql.length();
        int i = quoteIndex + 1;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < len) {
                i += 2;
                continue;
            }
            if (c == '\'') {
                if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    /**
     * Tries to scan a PostgreSQL dollar-quoted string {@code $tag$...$tag$} starting at {@code start}
     * (where {@code sql.charAt(start) == '$'}). Returns the index just past the terminating {@code $tag$},
     * or {@code start} if the input does not look like a dollar quote (caller should treat the {@code $}
     * as a literal).
     */
    private static int tryScanDollarQuote(String sql, int start) {
        int len = sql.length();
        int j = start + 1;
        while (j < len && isDollarTagPart(sql.charAt(j), j == start + 1)) {
            j++;
        }
        if (j >= len || sql.charAt(j) != '$') {
            return start;
        }
        String tag = sql.substring(start, j + 1);
        int bodyStart = j + 1;
        int idx = bodyStart;
        while (idx + tag.length() <= len) {
            if (sql.charAt(idx) == '$' && sql.regionMatches(idx, tag, 0, tag.length())) {
                return idx + tag.length();
            }
            idx++;
        }
        return len;
    }

    private static boolean isDollarTagPart(char c, boolean first) {
        if (first) {
            return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }
        return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isNameStart(char c) {
        return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isNamePart(char c) {
        return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

}
