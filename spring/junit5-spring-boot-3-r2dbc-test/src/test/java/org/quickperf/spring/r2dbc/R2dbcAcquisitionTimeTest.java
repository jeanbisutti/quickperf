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
package org.quickperf.spring.r2dbc;

import org.junit.jupiter.api.Test;
import org.quickperf.junit5.JUnit5Tests;
import org.quickperf.junit5.JUnit5Tests.JUnit5TestsResult;
import org.quickperf.spring.r2dbc.r2dbctest.AcquisitionTimeWithR2dbc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that R2DBC connection acquisition time is captured by
 * {@code R2dbcConnectionLifecycleListener} (using
 * {@code MethodExecutionInfo.getExecuteDuration()}) and reported through
 * {@code @ProfileConnection} as a {@code [time: Xms]} suffix on the
 * {@code io.r2dbc.spi.ConnectionFactory.create()} line.
 */
class R2dbcAcquisitionTimeTest {

    @Test
    void should_report_acquisition_time_on_create_line() throws IOException {

        // GIVEN
        Class<?> testClass = AcquisitionTimeWithR2dbc.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult result = jUnit5Tests.run();

        // THEN
        assertThat(result.getNumberOfFailures()).isZero();

        String profilingOutput = Files.lines(Paths.get(AcquisitionTimeWithR2dbc.FILE_PATH))
                .collect(joining(System.lineSeparator()));

        assertThat(profilingOutput)
                .contains("io.r2dbc.spi.ConnectionFactory.create()");

        Pattern createWithTime = Pattern.compile(
                "io\\.r2dbc\\.spi\\.ConnectionFactory\\.create\\(\\) \\[time: \\d+ms\\]");
        assertThat(createWithTime.matcher(profilingOutput).find())
                .as("Expected '[time: Xms]' suffix on at least one create() line in profiling output:%n%s",
                        profilingOutput)
                .isTrue();
    }

}
