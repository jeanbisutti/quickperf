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
package org.quickperf.spring.springboottest;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.quickperf.junit5.JUnit5Tests;
import org.quickperf.junit5.JUnit5Tests.JUnit5TestsResult;
import org.quickperf.spring.springboottest.controller.CrossTestContaminationWithConcurrentAsync;
import org.quickperf.spring.springboottest.controller.CrossTestContaminationWithConcurrentRandomPort;
import org.quickperf.spring.springboottest.controller.DetectionOfNPlusOneSelectWithAsync;
import org.quickperf.spring.springboottest.controller.DetectionOfNPlusOneSelectWithCompletableFuture;
import org.quickperf.spring.springboottest.service.DetectionOfSelectWithScheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class SpringBootThreadingScenariosJunit5Test {

    @Test
    void should_fail_if_select_number_is_greater_than_expected_with_async() {

        // GIVEN
        Class<?> testClass = DetectionOfNPlusOneSelectWithAsync.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        // THEN
        assertThat(jUnit5TestsResult.getNumberOfFailures()).isOne();

        String errorReport = jUnit5TestsResult.getErrorReport();
        assertThat(errorReport)
                      .contains("You may think that <1> select statement was sent to the database")
                      .contains("Perhaps you are facing an N+1 select issue");

    }

    @Test
    void should_fail_if_select_number_is_greater_than_expected_with_completable_future() {

        // GIVEN
        Class<?> testClass = DetectionOfNPlusOneSelectWithCompletableFuture.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        // THEN
        assertThat(jUnit5TestsResult.getNumberOfFailures()).isOne();

        String errorReport = jUnit5TestsResult.getErrorReport();
        assertThat(errorReport)
                      .contains("You may think that <1> select statement was sent to the database")
                      .contains("Perhaps you are facing an N+1 select issue");

    }

    @Test
    void should_detect_select_from_scheduled_task() {

        // GIVEN
        Class<?> testClass = DetectionOfSelectWithScheduled.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult jUnit5TestsResult = jUnit5Tests.run();

        // THEN
        assertThat(jUnit5TestsResult.getNumberOfFailures()).isOne();

        String errorReport = jUnit5TestsResult.getErrorReport();
        assertThat(errorReport)
                      .contains("You may think that <0> select statement was sent to the database")
                      .contains("But there is in fact <1>");

    }

    @Test
    void concurrent_tests_with_async() {

        // GIVEN
        TestExecutionSummary summary =
                runInParallel(CrossTestContaminationWithConcurrentAsync.class);

        // THEN
        // Active-set fallback now attributes the @Async worker's SELECTs to
        // BOTH concurrent recorders, so test_with_no_sql (@ExpectSelect(0))
        // sees 3 selects and fails. The warning text assertions land in the
        // contamination-flag commit on top of this count flip.
        assertThat(summary.getTestsFailedCount())
                      .isOne();

    }

    @Test
    void concurrent_tests_with_random_port() {

        // GIVEN
        TestExecutionSummary summary =
                runInParallel(CrossTestContaminationWithConcurrentRandomPort.class);

        // THEN
        // Active-set fallback now attributes the Tomcat worker's SELECTs to
        // BOTH concurrent recorders, so test_with_no_sql (@ExpectSelect(0))
        // sees 3 selects and fails. The warning text assertions land in the
        // contamination-flag commit on top of this count flip.
        assertThat(summary.getTestsFailedCount())
                      .isOne();

    }

    private static TestExecutionSummary runInParallel(Class<?> testClass) {
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(testClass))
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "true")
                .configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent")
                .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
                .configurationParameter("junit.jupiter.execution.parallel.config.fixed.parallelism", "2")
                .build();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request, listener);
        return listener.getSummary();
    }

}
