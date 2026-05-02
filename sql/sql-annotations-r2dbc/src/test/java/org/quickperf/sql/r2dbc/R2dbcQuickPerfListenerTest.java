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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

public class R2dbcQuickPerfListenerTest {

    /** A minimal SqlRecorder that just counts dispatches and remembers the last ExecutionInfo. */
    private static final class CountingRecorder implements SqlRecorder<org.quickperf.perfrecording.PerfRecord> {
        final AtomicInteger calls = new AtomicInteger();
        volatile ExecutionInfo last;
        volatile int lastListenerId = -1;

        @Override
        public void addQueryExecution(ExecutionInfo execInfo, List<net.ttddyy.dsproxy.QueryInfo> queries, int listenerIdentifier) {
            calls.incrementAndGet();
            last = execInfo;
            lastListenerId = listenerIdentifier;
        }

        @Override
        public void startRecording(org.quickperf.TestExecutionContext testExecutionContext) {}

        @Override
        public void stopRecording(org.quickperf.TestExecutionContext testExecutionContext) {}

        @Override
        public org.quickperf.perfrecording.PerfRecord findRecord(org.quickperf.TestExecutionContext testExecutionContext) {
            return null;
        }

        @Override
        public void cleanResources() {}
    }

    private QueryExecutionInfo simpleAfterQuery() {
        return MockQueryExecutionInfo.builder()
                .queries(Collections.singletonList(new QueryInfo("SELECT 1")))
                .executeDuration(Duration.ofMillis(1))
                .type(ExecutionType.STATEMENT)
                .build();
    }

    @Test
    public void afterQuery_dispatches_to_each_recorder_resolved_via_supplier() {
        final List<SqlRecorder<?>> active = new ArrayList<SqlRecorder<?>>();
        CountingRecorder r1 = new CountingRecorder();
        CountingRecorder r2 = new CountingRecorder();
        active.add(r1);
        active.add(r2);

        Supplier<Iterable<SqlRecorder<?>>> supplier = new Supplier<Iterable<SqlRecorder<?>>>() {
            @Override
            public Iterable<SqlRecorder<?>> get() { return active; }
        };
        R2dbcQuickPerfListener listener = new R2dbcQuickPerfListener("cf", supplier);

        listener.afterQuery(simpleAfterQuery());

        assertThat(r1.calls.get()).isEqualTo(1);
        assertThat(r2.calls.get()).isEqualTo(1);
    }

    @Test
    public void datasource_name_carries_bean_name_prefix() {
        final List<SqlRecorder<?>> active = new ArrayList<SqlRecorder<?>>();
        CountingRecorder r = new CountingRecorder();
        active.add(r);
        R2dbcQuickPerfListener listener = new R2dbcQuickPerfListener("primaryFactory",
                new Supplier<Iterable<SqlRecorder<?>>>() {
                    @Override public Iterable<SqlRecorder<?>> get() { return active; }
                });

        listener.afterQuery(simpleAfterQuery());

        assertThat(r.last.getDataSourceName()).isEqualTo("r2dbc:primaryFactory");
    }

    @Test
    public void afterQuery_with_no_active_recorders_does_not_throw() {
        final List<SqlRecorder<?>> empty = new ArrayList<SqlRecorder<?>>();
        R2dbcQuickPerfListener listener = new R2dbcQuickPerfListener("cf",
                new Supplier<Iterable<SqlRecorder<?>>>() {
                    @Override public Iterable<SqlRecorder<?>> get() { return empty; }
                });

        listener.afterQuery(simpleAfterQuery()); // no-op
    }

    @Test
    public void afterQuery_with_null_info_is_a_no_op() {
        final List<SqlRecorder<?>> active = new ArrayList<SqlRecorder<?>>();
        CountingRecorder r = new CountingRecorder();
        active.add(r);
        R2dbcQuickPerfListener listener = new R2dbcQuickPerfListener("cf",
                new Supplier<Iterable<SqlRecorder<?>>>() {
                    @Override public Iterable<SqlRecorder<?>> get() { return active; }
                });

        listener.afterQuery(null);

        assertThat(r.calls.get()).isZero();
    }

    @Test(expected = IllegalArgumentException.class)
    public void null_supplier_is_rejected_at_construction() {
        new R2dbcQuickPerfListener("cf", null);
    }

    @Test
    public void listener_identifier_is_stable_across_dispatches_for_one_instance() {
        final List<SqlRecorder<?>> active = new ArrayList<SqlRecorder<?>>();
        CountingRecorder r = new CountingRecorder();
        active.add(r);
        R2dbcQuickPerfListener listener = new R2dbcQuickPerfListener("cf",
                new Supplier<Iterable<SqlRecorder<?>>>() {
                    @Override public Iterable<SqlRecorder<?>> get() { return active; }
                });

        listener.afterQuery(simpleAfterQuery());
        int firstId = r.lastListenerId;
        listener.afterQuery(simpleAfterQuery());

        assertThat(r.lastListenerId).isEqualTo(firstId);
    }

}
