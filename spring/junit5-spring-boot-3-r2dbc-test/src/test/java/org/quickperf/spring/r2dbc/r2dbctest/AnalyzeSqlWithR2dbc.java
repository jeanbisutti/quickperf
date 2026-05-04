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
import org.quickperf.sql.annotation.AnalyzeSql;
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
 * Inner test launched by {@link org.quickperf.spring.r2dbc.R2dbcAnalyzeSqlTest}
 * to verify that {@code @AnalyzeSql} produces a SQL analysis report for
 * reactive queries: counts JDBC/R2DBC executions, identifies the SELECT type,
 * and formats the rendered query string.
 */
@QuickPerfTest
@DataR2dbcTest
public class AnalyzeSqlWithR2dbc {

    public static final String FILE_PATH = findTargetPath() + File.separator + "r2dbc-analyze-sql.txt";

    private static String findTargetPath() {
        Path targetDirectory = Paths.get("target");
        return targetDirectory.toFile().getAbsolutePath();
    }

    @Autowired
    private DatabaseClient databaseClient;

    @AnalyzeSql(writerFactory = R2dbcAnalyzeSqlFileWriterBuilder.class)
    @Test
    public void execute_a_select_through_r2dbc() {
        databaseClient.sql("CREATE TABLE IF NOT EXISTS ANALYZE_R2DBC_TEST (id BIGINT PRIMARY KEY, name VARCHAR(255))")
                .fetch().rowsUpdated().block();
        databaseClient.sql("MERGE INTO ANALYZE_R2DBC_TEST KEY (id) VALUES (1, 'r2dbc')")
                .fetch().rowsUpdated().block();
        databaseClient.sql("SELECT id, name FROM ANALYZE_R2DBC_TEST")
                .fetch().all().collectList().block();
    }

    public static class R2dbcAnalyzeSqlFileWriterBuilder implements WriterFactory {

        @Override
        public Writer buildWriter() throws IOException {
            return new FileWriter(FILE_PATH);
        }

    }

}
