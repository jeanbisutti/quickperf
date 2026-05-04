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
package org.quickperf.spring.r2dbc.r2dbctest;

import org.junit.jupiter.api.Test;
import org.quickperf.junit5.QuickPerfTest;
import org.quickperf.sql.annotation.DisplaySql;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Inner test launched by {@link org.quickperf.spring.r2dbc.R2dbcDisplaySqlTest}
 * to verify that {@code @DisplaySql} prints rendered reactive SQL on the
 * console (including the rendered placeholder values produced by PR-1's
 * placeholder scanner / {@code ?}-only renderer fix).
 */
@QuickPerfTest
@DataR2dbcTest
public class DisplaySqlWithR2dbc {

    @Autowired
    private DatabaseClient databaseClient;

    @DisplaySql
    @Test
    public void execute_a_select_through_r2dbc() {
        databaseClient.sql("CREATE TABLE IF NOT EXISTS DISPLAY_R2DBC_TEST (id BIGINT PRIMARY KEY, name VARCHAR(255))")
                .fetch().rowsUpdated().block();
        databaseClient.sql("MERGE INTO DISPLAY_R2DBC_TEST KEY (id) VALUES (1, 'r2dbc')")
                .fetch().rowsUpdated().block();
        databaseClient.sql("SELECT id, name FROM DISPLAY_R2DBC_TEST")
                .fetch().all().collectList().block();
    }

}
