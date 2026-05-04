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
import org.junit.Before;
import org.junit.Test;
import org.quickperf.TestExecutionContext;
import org.quickperf.WorkingFolder;
import org.quickperf.sql.repository.SqlRepository;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link PersistenceSqlRecorder#findRecord(TestExecutionContext)}:
 *  - emits {@link SqlExecutions#CROSS_TEST_CONTAMINATION_WARNING} to its
 *    stderr seam exactly once, when a contamination tag is set on the
 *    recorder via {@link SqlRecorderRegistry#markCrossTestContamination};
 *  - is silent when the recorder is not tagged;
 *  - tolerates a misbehaving repository that returns
 *    {@link SqlExecutions#NONE} (the JVM-wide singleton) by swapping to a
 *    fresh non-NONE measure so the contamination tag survives.
 *
 * See pr1-plan.md §2.4 / §3.5.
 */
public class PersistenceSqlRecorderStderrWarningTest {

    private static final String CHARSET = "UTF-8";

    /** Surefire is configured with parallel=all, threadCount=5 at the root pom.
     * The {@link PersistenceSqlRecorder#WARNING_SINK} test seam is a single
     * static PrintStream, so two test methods that swap it concurrently
     * would step on each other's captured streams (last-writer-wins) and the
     * recorder's println would land in the wrong test's buffer. We serialize
     * every test in this class on a static lock acquired in @Before and
     * released in @After. */
    private static final ReentrantLock SINK_LOCK = new ReentrantLock();

    private PrintStream previousSink;
    private ByteArrayOutputStream captured;

    @Before
    public void redirectWarningSink() throws UnsupportedEncodingException {
        SINK_LOCK.lock();
        previousSink = PersistenceSqlRecorder.WARNING_SINK;
        captured = new ByteArrayOutputStream();
        PersistenceSqlRecorder.WARNING_SINK = new PrintStream(captured, true, CHARSET);
    }

    @After
    public void restoreWarningSink() {
        try {
            PersistenceSqlRecorder.WARNING_SINK = previousSink;
            SqlRecorderRegistry.INSTANCE.clear();
        } finally {
            SINK_LOCK.unlock();
        }
    }

    @Test public void
    findRecord_should_emit_warning_once_when_recorder_was_marked_as_contaminated()
            throws UnsupportedEncodingException {

        // GIVEN a recorder backed by a stub repository that returns a fresh, empty SqlExecutions
        SqlExecutions nonNone = new SqlExecutions();
        PersistenceSqlRecorder recorder = recorderWithRepositoryReturning(nonNone);

        // ... and the recorder has been tagged as contaminated by the registry
        SqlRecorderRegistry.markCrossTestContamination(recorder);

        // WHEN the test framework reads the recording
        SqlExecutions result = recorder.findRecord(mock(TestExecutionContext.class));

        // THEN the warning text was emitted to stderr exactly once
        String stderr = captured.toString(CHARSET);
        assertThat(stderr).contains(SqlExecutions.CROSS_TEST_CONTAMINATION_WARNING);
        int firstOccurrence = stderr.indexOf(SqlExecutions.CROSS_TEST_CONTAMINATION_WARNING);
        int lastOccurrence = stderr.lastIndexOf(SqlExecutions.CROSS_TEST_CONTAMINATION_WARNING);
        assertThat(firstOccurrence).isEqualTo(lastOccurrence);

        // ... and the returned measure carries the tag so format() can prepend the warning later
        assertThat(result.hasCrossTestContamination()).isTrue();
        assertThat(result).isSameAs(nonNone);
    }

    @Test public void
    findRecord_should_not_emit_warning_when_recorder_was_not_marked()
            throws UnsupportedEncodingException {

        // GIVEN a recorder NOT marked as contaminated
        PersistenceSqlRecorder recorder = recorderWithRepositoryReturning(new SqlExecutions());

        // WHEN
        SqlExecutions result = recorder.findRecord(mock(TestExecutionContext.class));

        // THEN no warning was emitted and the measure has no contamination tag
        assertThat(captured.toString(CHARSET)).isEmpty();
        assertThat(result.hasCrossTestContamination()).isFalse();
    }

    @Test public void
    findRecord_should_clear_the_contamination_flag_so_a_subsequent_call_does_not_re_emit()
            throws UnsupportedEncodingException {

        // GIVEN a recorder that will be read twice (e.g. an evaluator that calls findRecord
        // more than once, or a recorder reused under a persistent worker pool). The
        // repository hands out a FRESH SqlExecutions on every call so we can prove the
        // flag is cleared on the recorder, not just absent on a reused measure.
        PersistenceSqlRecorder recorder = recorderWithRepositoryReturningFreshMeasure();
        SqlRecorderRegistry.markCrossTestContamination(recorder);

        // WHEN
        recorder.findRecord(mock(TestExecutionContext.class));
        captured.reset();
        SqlExecutions secondRead = recorder.findRecord(mock(TestExecutionContext.class));

        // THEN the second read sees a clean recorder
        assertThat(captured.toString(CHARSET)).isEmpty();
        assertThat(secondRead.hasCrossTestContamination()).isFalse();
    }

    @Test public void
    findRecord_should_swap_NONE_for_a_fresh_measure_so_the_tag_survives()
            throws UnsupportedEncodingException {

        // GIVEN a recorder whose repository returns the JVM-wide-shared NONE singleton.
        // Tagging NONE is a no-op (defence-in-depth: NONE is shared and must stay clean),
        // so findRecord must swap to a fresh non-NONE measure before tagging it.
        PersistenceSqlRecorder recorder = recorderWithRepositoryReturning(SqlExecutions.NONE);
        SqlRecorderRegistry.markCrossTestContamination(recorder);

        // WHEN
        SqlExecutions result = recorder.findRecord(mock(TestExecutionContext.class));

        // THEN the result is not NONE, carries the tag, and NONE remains untagged
        assertThat(result).isNotSameAs(SqlExecutions.NONE);
        assertThat(result.hasCrossTestContamination()).isTrue();
        assertThat(SqlExecutions.NONE.hasCrossTestContamination()).isFalse();

        // ... and the warning was still emitted to stderr
        assertThat(captured.toString(CHARSET)).contains(SqlExecutions.CROSS_TEST_CONTAMINATION_WARNING);
    }

    /** Builds a recorder whose private sqlRepository field is a stub returning {@code measure}.
     * Avoids the standard {@code startRecording} path so the test does not have to wire up a
     * real {@link org.quickperf.WorkingFolder} or test-execution context. */
    private PersistenceSqlRecorder recorderWithRepositoryReturning(SqlExecutions measure) {
        PersistenceSqlRecorder recorder = new PersistenceSqlRecorder();
        SqlRepository repository = mock(SqlRepository.class);
        when(repository.findExecutedQueries(org.mockito.ArgumentMatchers.nullable(WorkingFolder.class))).thenReturn(measure);
        try {
            java.lang.reflect.Field f = PersistenceSqlRecorder.class.getDeclaredField("sqlRepository");
            f.setAccessible(true);
            f.set(recorder, repository);
        } catch (Exception e) {
            throw new AssertionError("Failed to inject stub SqlRepository", e);
        }
        return recorder;
    }

    /** Variant that hands out a fresh {@link SqlExecutions} on every call so a test can
     * distinguish between "flag cleared on the recorder" (correct) and "flag absent on a
     * reused measure" (false negative). */
    private PersistenceSqlRecorder recorderWithRepositoryReturningFreshMeasure() {
        PersistenceSqlRecorder recorder = new PersistenceSqlRecorder();
        SqlRepository repository = mock(SqlRepository.class);
        when(repository.findExecutedQueries(org.mockito.ArgumentMatchers.nullable(WorkingFolder.class)))
                .thenAnswer(new org.mockito.stubbing.Answer<SqlExecutions>() {
                    @Override
                    public SqlExecutions answer(org.mockito.invocation.InvocationOnMock invocation) {
                        return new SqlExecutions();
                    }
                });
        try {
            java.lang.reflect.Field f = PersistenceSqlRecorder.class.getDeclaredField("sqlRepository");
            f.setAccessible(true);
            f.set(recorder, repository);
        } catch (Exception e) {
            throw new AssertionError("Failed to inject stub SqlRepository", e);
        }
        return recorder;
    }

}
