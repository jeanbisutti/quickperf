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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link SqlRecorderRegistry#snapshotForCurrentThread()} returns a defensive
 * copy of the calling thread's registered recorders, and an empty map when nothing is
 * registered (or only the post-clear sentinel is present).
 */
public class SqlRecorderRegistrySnapshotTest {

    @After
    public void tearDown() {
        SqlRecorderRegistry.INSTANCE.clear();
    }

    @Test public void
    snapshot_is_empty_when_nothing_registered() {
        Map<Class<? extends SqlRecorder>, SqlRecorder> snapshot
                = SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread();
        assertThat(snapshot).isEmpty();
    }

    @Test public void
    snapshot_contains_registered_recorder() {
        SqlRecorder recorder = new PersistenceSqlRecorder();
        SqlRecorderRegistry.INSTANCE.register(recorder);

        Map<Class<? extends SqlRecorder>, SqlRecorder> snapshot
                = SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread();

        assertThat(snapshot).containsEntry(PersistenceSqlRecorder.class, recorder);
    }

    @Test public void
    snapshot_is_a_defensive_copy_so_caller_mutation_does_not_leak() {
        SqlRecorder recorder = new PersistenceSqlRecorder();
        SqlRecorderRegistry.INSTANCE.register(recorder);

        Map<Class<? extends SqlRecorder>, SqlRecorder> snapshot
                = SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread();
        snapshot.clear();

        // Re-snapshot — the registry must still hold the recorder.
        Map<Class<? extends SqlRecorder>, SqlRecorder> reSnapshot
                = SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread();
        assertThat(reSnapshot).containsEntry(PersistenceSqlRecorder.class, recorder);
    }

    @Test public void
    snapshot_after_clear_is_empty() {
        SqlRecorderRegistry.INSTANCE.register(new PersistenceSqlRecorder());
        SqlRecorderRegistry.INSTANCE.clear();

        Map<Class<? extends SqlRecorder>, SqlRecorder> snapshot
                = SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread();
        assertThat(snapshot).isEmpty();
    }

}
