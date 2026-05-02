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
import org.quickperf.sql.annotation.ExpectSelect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.util.Map;

/**
 * Equivalent of {@code ExpectSelectWithJdbc} for R2DBC. Two SELECTs are
 * issued via {@link DatabaseClient} but the annotation expects only one,
 * so QuickPerf must observe two reactive executions and fail the test
 * with the expected diagnostic message — that is what
 * {@code SpringBoot3JUnit5R2dbcTest} verifies.
 *
 * <p>Not intended to be discovered directly by Surefire — only invoked via
 * the JUnit 5 launcher inside its sibling test.
 */
@QuickPerfTest
@DataR2dbcTest
public class ExpectSelectWithR2dbc {

    @Autowired
    private DatabaseClient databaseClient;

    @ExpectSelect(1)
    @Test
    public void execute_two_selects() {

        databaseClient.sql("CREATE TABLE IF NOT EXISTS PLAYER_R2DBC_TEST (id BIGINT PRIMARY KEY, name VARCHAR(255))")
                .fetch().rowsUpdated().block();
        databaseClient.sql("MERGE INTO PLAYER_R2DBC_TEST KEY (id) VALUES (1, 'Paul Pogba')")
                .fetch().rowsUpdated().block();
        databaseClient.sql("MERGE INTO PLAYER_R2DBC_TEST KEY (id) VALUES (2, 'Antoine Griezmann')")
                .fetch().rowsUpdated().block();

        StepVerifier.create(databaseClient.sql("SELECT id, name FROM PLAYER_R2DBC_TEST WHERE id = 1")
                        .fetch().all().collectList())
                .assertNext((java.util.List<Map<String, Object>> rows) -> {
                    org.assertj.core.api.Assertions.assertThat(rows).hasSize(1);
                })
                .verifyComplete();

        StepVerifier.create(databaseClient.sql("SELECT id, name FROM PLAYER_R2DBC_TEST WHERE id = 2")
                        .fetch().all().collectList())
                .assertNext((java.util.List<Map<String, Object>> rows) -> {
                    org.assertj.core.api.Assertions.assertThat(rows).hasSize(1);
                })
                .verifyComplete();

    }

}
