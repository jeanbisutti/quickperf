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
import org.quickperf.spring.r2dbc.r2dbctest.ProfileConnectionWithR2dbc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link org.quickperf.sql.annotation.ProfileConnection
 * @ProfileConnection} produces lifecycle profiling output for reactive
 * R2DBC connections. Launches {@link ProfileConnectionWithR2dbc} via the
 * JUnit 5 launcher and asserts the profiling file contains both an
 * {@code r2dbc-...} acquire entry and a corresponding close entry, with
 * the expected method-name format produced by
 * {@code R2dbcConnectionEventsProfilerListener}.
 */
class R2dbcProfileConnectionTest {

    @Test
    void should_profile_r2dbc_connection_lifecycle() throws IOException {

        // GIVEN
        Class<?> testClass = ProfileConnectionWithR2dbc.class;
        JUnit5Tests jUnit5Tests = JUnit5Tests.createInstance(testClass);

        // WHEN
        JUnit5TestsResult result = jUnit5Tests.run();

        // THEN
        assertThat(result.getNumberOfFailures()).isZero();

        String profilingOutput = Files.lines(Paths.get(ProfileConnectionWithR2dbc.FILE_PATH))
                .collect(joining(System.lineSeparator()));

        assertThat(profilingOutput)
                .contains("connection r2dbc-")
                .contains("io.r2dbc.spi.ConnectionFactory.create()")
                .contains("io.r2dbc.spi.Connection.close()");
    }

}
