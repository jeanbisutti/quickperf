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
package org.quickperf;

import org.junit.Test;
import org.quickperf.config.library.AnnotationConfig;
import org.quickperf.config.library.SetOfAnnotationConfigs;
import org.quickperf.measure.BooleanMeasure;
import org.quickperf.perfrecording.RecordablePerformance;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link TestExecutionContext#buildPerfRecordersToExecute(SetOfAnnotationConfigs, Annotation[])}
 * deduplicates recorders by recorder class.
 *
 * <p>Before PR-7, the {@code perfRecorderClasses} {@link java.util.HashSet}
 * tracking already-built recorder classes was never populated, so the
 * {@code contains(...)} guard always returned {@code false} and two annotations
 * sharing a recorder class produced two recorder instances. The bug was
 * harmless until alias annotations introduced in PR-3 began sharing a recorder
 * class with their legacy counterpart.
 */
public class TestExecutionContextDedupTest {

    @SuppressWarnings("InterfaceMayBeAnnotatedFunctional")
    public @interface FirstAnnotation {
    }

    @SuppressWarnings("InterfaceMayBeAnnotatedFunctional")
    public @interface SecondAnnotation {
    }

    @SuppressWarnings("InterfaceMayBeAnnotatedFunctional")
    public @interface ThirdAnnotation {
    }

    public static final class SharedRecorder implements RecordablePerformance<BooleanMeasure> {
        @Override public void startRecording(TestExecutionContext testExecutionContext) {}
        @Override public void stopRecording(TestExecutionContext testExecutionContext) {}
        @Override public BooleanMeasure findRecord(TestExecutionContext testExecutionContext) {
            return BooleanMeasure.FALSE;
        }
        @Override public void cleanResources() {}
    }

    public static final class OtherRecorder implements RecordablePerformance<BooleanMeasure> {
        @Override public void startRecording(TestExecutionContext testExecutionContext) {}
        @Override public void stopRecording(TestExecutionContext testExecutionContext) {}
        @Override public BooleanMeasure findRecord(TestExecutionContext testExecutionContext) {
            return BooleanMeasure.FALSE;
        }
        @Override public void cleanResources() {}
    }

    private static SetOfAnnotationConfigs configs() {
        AnnotationConfig firstConfig = new AnnotationConfig.Builder()
                .perfRecorderClass(SharedRecorder.class)
                .build(FirstAnnotation.class);
        AnnotationConfig secondConfig = new AnnotationConfig.Builder()
                .perfRecorderClass(SharedRecorder.class)
                .build(SecondAnnotation.class);
        AnnotationConfig thirdConfig = new AnnotationConfig.Builder()
                .perfRecorderClass(OtherRecorder.class)
                .build(ThirdAnnotation.class);
        return new SetOfAnnotationConfigs(Arrays.asList(firstConfig, secondConfig, thirdConfig));
    }

    @Test public void
    builds_a_single_recorder_when_two_annotations_share_a_recorder_class() {

        SetOfAnnotationConfigs configs = configs();
        Annotation[] perfAnnotations = new Annotation[] { firstAnnotation(), secondAnnotation() };

        List<RecordablePerformance> recorders = TestExecutionContext.buildPerfRecordersToExecute(configs, perfAnnotations);

        assertThat(recorders).hasSize(1);
        assertThat(recorders.get(0)).isInstanceOf(SharedRecorder.class);
    }

    @Test public void
    builds_one_recorder_per_distinct_recorder_class() {

        SetOfAnnotationConfigs configs = configs();
        Annotation[] perfAnnotations = new Annotation[] {
                firstAnnotation(), secondAnnotation(), thirdAnnotation()
        };

        List<RecordablePerformance> recorders = TestExecutionContext.buildPerfRecordersToExecute(configs, perfAnnotations);

        assertThat(recorders).hasSize(2);
        assertThat(recorders).extracting("class")
                .containsExactlyInAnyOrder(SharedRecorder.class, OtherRecorder.class);
    }

    @Test public void
    returns_empty_list_when_no_annotation_has_a_registered_recorder_class() {

        SetOfAnnotationConfigs emptyConfigs = new SetOfAnnotationConfigs(java.util.Collections.<AnnotationConfig>emptyList());
        Annotation[] perfAnnotations = new Annotation[] { firstAnnotation() };

        List<RecordablePerformance> recorders = TestExecutionContext.buildPerfRecordersToExecute(emptyConfigs, perfAnnotations);

        assertThat(recorders).isEmpty();
    }

    private static FirstAnnotation firstAnnotation() {
        return new FirstAnnotation() {
            @Override public Class<? extends Annotation> annotationType() { return FirstAnnotation.class; }
        };
    }

    private static SecondAnnotation secondAnnotation() {
        return new SecondAnnotation() {
            @Override public Class<? extends Annotation> annotationType() { return SecondAnnotation.class; }
        };
    }

    private static ThirdAnnotation thirdAnnotation() {
        return new ThirdAnnotation() {
            @Override public Class<? extends Annotation> annotationType() { return ThirdAnnotation.class; }
        };
    }

}
