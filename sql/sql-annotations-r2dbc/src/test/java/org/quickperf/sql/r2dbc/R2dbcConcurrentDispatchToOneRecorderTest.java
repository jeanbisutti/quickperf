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

import io.r2dbc.proxy.core.ExecutionType;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.proxy.test.MockQueryExecutionInfo;
import net.ttddyy.dsproxy.ExecutionInfo;
import org.junit.Test;
import org.quickperf.sql.SqlRecorder;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the {@code synchronized (recorder)} block in
 * {@link R2dbcQuickPerfListener#afterQuery(QueryExecutionInfo)}.
 *
 * <p>Reactor schedulers may dispatch {@code afterQuery} from any worker thread.
 * QuickPerf recorders accumulate executions into a non-thread-safe
 * {@link ArrayDeque}-backed {@link org.quickperf.sql.SqlExecutions}; concurrent
 * dispatches without per-recorder serialization corrupt the deque (lost or
 * duplicated entries, occasionally NPE on iteration).
 */
public class R2dbcConcurrentDispatchToOneRecorderTest {

    /**
     * Mirrors the non-thread-safe accumulation pattern of
     * {@link org.quickperf.sql.PersistenceSqlRecorder} so we can isolate the
     * concurrent-dispatch concern without standing up a SqlRepository.
     */
    private static final class DequeBackedRecorder implements SqlRecorder<org.quickperf.perfrecording.PerfRecord> {
        private final Deque<ExecutionInfo> deque = new ArrayDeque<ExecutionInfo>();

        @Override
        public void addQueryExecution(ExecutionInfo execInfo, List<net.ttddyy.dsproxy.QueryInfo> queries, int listenerIdentifier) {
            deque.addLast(execInfo);
        }

        int size() { return deque.size(); }

        @Override public void startRecording(org.quickperf.TestExecutionContext testExecutionContext) {}
        @Override public void stopRecording(org.quickperf.TestExecutionContext testExecutionContext) {}
        @Override public org.quickperf.perfrecording.PerfRecord findRecord(org.quickperf.TestExecutionContext testExecutionContext) { return null; }
        @Override public void cleanResources() {}
    }

    @Test
    public void concurrent_dispatches_to_one_recorder_do_not_lose_executions() throws Exception {
        final DequeBackedRecorder recorder = new DequeBackedRecorder();
        final List<SqlRecorder<?>> active = new ArrayList<SqlRecorder<?>>();
        active.add(recorder);

        final R2dbcQuickPerfListener listener = new R2dbcQuickPerfListener("cf",
                new Supplier<Iterable<SqlRecorder<?>>>() {
                    @Override public Iterable<SqlRecorder<?>> get() { return active; }
                });

        final QueryExecutionInfo info = MockQueryExecutionInfo.builder()
                .queries(Collections.singletonList(new QueryInfo("SELECT 1")))
                .executeDuration(Duration.ofMillis(1))
                .type(ExecutionType.STATEMENT)
                .build();

        final int threads = 8;
        final int dispatchesPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < dispatchesPerThread; i++) {
                            listener.afterQuery(info);
                        }
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(recorder.size()).isEqualTo(threads * dispatchesPerThread);
    }

}
