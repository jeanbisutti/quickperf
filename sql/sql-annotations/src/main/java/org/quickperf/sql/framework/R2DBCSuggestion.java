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
package org.quickperf.sql.framework;

import org.quickperf.SystemProperties;

/**
 * R2DBC-specific suggestions, sibling of {@link JdbcSuggestion} and {@link HibernateSuggestion}.
 * Triggered conditionally when {@link ClassPath#containsR2dbcSpi()} is {@code true}.
 */
public enum R2DBCSuggestion implements QuickPerfSuggestion {

    SERVER_ROUND_TRIPS {
        @Override
        public String getMessage() {
            if (SystemProperties.SIMPLIFIED_SQL_DISPLAY.evaluate()) {
                return "";
            }
            String bomb = "\uD83D\uDCA3";
            return    bomb + " " + "Reactive (R2DBC) executions still pay per-statement network round-trips."
                    + System.lineSeparator()
                    + "Prefer batching with Statement.bind().add().bind().add().execute() or fetch related rows "
                    + "in a single SELECT (JOIN, IN (...))."
                    + System.lineSeparator()
                    + "When using Spring Data R2DBC, an @Query with a JOIN, an R2dbcEntityTemplate projection, "
                    + "or DatabaseClient with bind() calls usually replaces N round-trips with one.";
        }
    },

    N_PLUS_ONE {
        @Override
        public String getMessage() {
            if (SystemProperties.SIMPLIFIED_SQL_DISPLAY.evaluate()) {
                return "";
            }
            return    "Spring Data R2DBC: avoid loading associated entities one-by-one with"
                    + System.lineSeparator()
                    + "Flux.flatMap(parent -> repository.findByParentId(parent.getId())). Prefer @Query"
                    + System.lineSeparator()
                    + "with an explicit JOIN, or fetch all related rows with WHERE parent_id IN (...)"
                    + System.lineSeparator()
                    + "and group them in memory.";
        }
    }

}
