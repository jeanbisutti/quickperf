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
package org.quickperf.sql;

import org.junit.After;
import org.junit.Test;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class SqlRecorderRegistryTest {

    @After
    public void tearDown() {
        // Surefire runs this module with parallel=all, threadCount=5; clear
        // any state this test left behind so it cannot leak into siblings.
        SqlRecorderRegistry.INSTANCE.clear();
    }

    @Test public void
    should_get_a_sql_recorder_from_its_type() {

        // GIVEN
        SqlRecorder registeredSqlRecorder = new PersistenceSqlRecorder();
        SqlRecorderRegistry.INSTANCE.register(registeredSqlRecorder);

        // WHEN
        SqlRecorder retrievedSqlRecorder = SqlRecorderRegistry.INSTANCE
                                          .getSqlRecorderOfType(PersistenceSqlRecorder.class);

        // THEN
        assertThat(retrievedSqlRecorder).isEqualTo(registeredSqlRecorder);

    }

    @Test public void
    should_clear_sql_recorder_registry() {

        // GIVEN
        SqlRecorder registeredSqlRecorder = new PersistenceSqlRecorder();
        SqlRecorderRegistry.INSTANCE.register(registeredSqlRecorder);

        // WHEN
        SqlRecorderRegistry.INSTANCE.clear();

        // THEN
        Collection<SqlRecorder> sqlRecorders = SqlRecorderRegistry.INSTANCE.getSqlRecorders();
        assertThat(sqlRecorders).hasSize(0);

    }

    @Test public void
    register_then_get_returns_recorder_on_unrelated_worker_thread() throws Exception {

        // GIVEN — register on the test thread (Surefire pool thread).
        final SqlRecorder testThreadRecorder = new PersistenceSqlRecorder();
        SqlRecorderRegistry.INSTANCE.register(testThreadRecorder);

        // WHEN — an unrelated worker (NOT a child of the test thread) reads
        // the registry. The worker has no entry in PER_THREAD_RECORDERS, so
        // it must fall back to ACTIVE_RECORDERS and find the recorder.
        final AtomicReference<Collection<SqlRecorder>> workerView
                = new AtomicReference<Collection<SqlRecorder>>();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                workerView.set(SqlRecorderRegistry.INSTANCE.getSqlRecorders());
            }
        }, "registry-worker-fallback-smoke");
        worker.start();
        worker.join();

        // THEN
        assertThat(workerView.get()).contains(testThreadRecorder);

    }

}