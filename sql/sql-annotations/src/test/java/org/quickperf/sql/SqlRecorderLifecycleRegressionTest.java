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

import org.junit.Test;
import org.quickperf.TestExecutionContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Regression test ensuring {@link PersistenceSqlRecorder#startRecording(TestExecutionContext)}
 * does not leak the recorder into {@link SqlRecorderHook} when subsequent steps fail.
 *
 * <p>Without exception-safety, a failed startup would leak the recorder into the
 * JVM-global hook and contaminate subsequent reactive tests, even when running
 * with {@code parallel=none}.
 */
public class SqlRecorderLifecycleRegressionTest {

    @Test public void
    failed_start_recording_should_not_leak_recorder_into_hook() {

        PersistenceSqlRecorder recorder = new PersistenceSqlRecorder();

        try {
            // Passing a null TestExecutionContext makes
            // SqlRepositoryFactory.getSqlRepository throw a NullPointerException
            // (it calls testExecutionUsesTwoJVMs() on the context).
            recorder.startRecording(null);
            fail("Expected an exception when starting recording with a null context");
        } catch (RuntimeException expected) {
            // ok — startRecording is expected to fail.
        }

        assertThat(SqlRecorderHook.getActiveRecorders()).doesNotContain(recorder);
    }

}
