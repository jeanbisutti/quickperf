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
package org.quickperf.spring.r2dbc.r2dbctest;

import org.junit.jupiter.api.Test;
import org.quickperf.junit5.QuickPerfTest;
import org.quickperf.sql.annotation.ProfileConnection;
import org.quickperf.sql.connection.Level;
import org.quickperf.writer.WriterFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.r2dbc.core.DatabaseClient;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reactive equivalent of {@code ProfileConnectionJUnit5Test.ProfileConnectionClass}.
 * Runs a SELECT through {@link DatabaseClient} so the R2DBC connection
 * lifecycle (acquire and release) is exercised and {@code @ProfileConnection}
 * has events to record.
 */
@QuickPerfTest
@DataR2dbcTest
public class ProfileConnectionWithR2dbc {

    public static final String FILE_PATH = findTargetPath() + File.separator + "r2dbc-connection-profiling.txt";

    private static String findTargetPath() {
        Path targetDirectory = Paths.get("target");
        return targetDirectory.toFile().getAbsolutePath();
    }

    @Autowired
    private DatabaseClient databaseClient;

    @ProfileConnection(level = Level.INFO
                     , writerFactory = R2dbcProfileFileWriterBuilder.class)
    @Test
    public void execute_a_query_through_r2dbc() {
        databaseClient.sql("CREATE TABLE IF NOT EXISTS PROFILE_R2DBC_TEST (id BIGINT PRIMARY KEY, name VARCHAR(255))")
                .fetch().rowsUpdated().block();
        databaseClient.sql("SELECT id, name FROM PROFILE_R2DBC_TEST")
                .fetch().all().collectList().block();
    }

    public static class R2dbcProfileFileWriterBuilder implements WriterFactory {

        @Override
        public Writer buildWriter() throws IOException {
            return new FileWriter(FILE_PATH);
        }

    }

}
