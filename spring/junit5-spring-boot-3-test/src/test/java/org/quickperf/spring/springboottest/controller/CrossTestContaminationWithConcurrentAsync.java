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
package org.quickperf.spring.springboottest.controller;

import org.junit.jupiter.api.Test;
import org.quickperf.junit5.QuickPerfTest;
import org.quickperf.spring.springboottest.FootballApplication;
import org.quickperf.spring.springboottest.dto.PlayerWithTeamName;
import org.quickperf.spring.springboottest.service.AsyncPlayerService;
import org.quickperf.sql.annotation.ExpectSelect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@QuickPerfTest
@SpringBootTest(classes = {FootballApplication.class})
public class CrossTestContaminationWithConcurrentAsync {

    @Autowired
    private AsyncPlayerService asyncPlayerService;

    @ExpectSelect(3)
    @Test
    void test_with_async_select() throws Exception {
        // SQL executes on a Spring @Async executor thread (not the test thread).
        // 1 SELECT for players + 2 SELECTs for lazy-loaded teams (N+1)
        CompletableFuture<List<PlayerWithTeamName>> future =
                asyncPlayerService.findPlayersWithTeamNameAsync();
        List<PlayerWithTeamName> players = future.get();
        assertThat(players).hasSize(2);
    }

    @ExpectSelect(0)
    @Test
    void test_with_no_sql() throws InterruptedException {
        // Keep this test's recorder active long enough for the concurrent
        // test's @Async SQL to be recorded via ALL_ACTIVE_RECORDERS —
        // which includes this test's recorder.
        Thread.sleep(2000);
    }

}
