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
package org.quickperf.sql.framework.quickperf;

import org.quickperf.sql.framework.ClassPath;
import org.quickperf.sql.framework.QuickPerfSuggestion;

import static org.quickperf.sql.framework.quickperf.QuickPerfDependency.*;

class SpringDataSourceConfig implements QuickPerfSuggestion {

    private static final String LINE_SEPARATOR = System.lineSeparator();

    private final ClassPath classPath;

    SpringDataSourceConfig(ClassPath classPath) {
        this.classPath = classPath;
    }

    @Override
    public String getMessage() {

        StringBuilder out = new StringBuilder();

        if (classPath.containsR2dbcSpi()) {
            if (classPath.contains(QUICKPERF_SPRING_BOOT_R2DBC_SQL_STARTER)) {
                out.append(LINE_SEPARATOR).append(buildSpringRESTControllerMessage());
            } else {
                out.append("To configure the reactive proxy, add the following dependency: ")
                   .append(LINE_SEPARATOR).append(format(QUICKPERF_SPRING_BOOT_R2DBC_SQL_STARTER));
            }
        }

        if (classPath.containsSpringBoot1()) {
            appendSeparatorIfNotEmpty(out);
            if (classPath.contains(QUICKPERF_SPRING_BOOT_1_SQL_STARTER)) {
                out.append(LINE_SEPARATOR).append(buildSpringRESTControllerMessage());
            } else {
                out.append("To configure it, add the following dependency: ")
                   .append(LINE_SEPARATOR).append(format(QUICKPERF_SPRING_BOOT_1_SQL_STARTER));
            }
            return out.toString();
        }

        if (classPath.containsSpringBoot2() || classPath.containsSpringBoot3()) {
            appendSeparatorIfNotEmpty(out);
            if (classPath.contains(QUICKPERF_SPRING_BOOT_2_SQL_STARTER)) {
                out.append(LINE_SEPARATOR).append(buildSpringRESTControllerMessage());
            } else {
                out.append("To configure it, add the following dependency: ")
                   .append(LINE_SEPARATOR).append(format(QUICKPERF_SPRING_BOOT_2_SQL_STARTER));
            }
            return out.toString();
        }

        if (classPath.containsSpring4()) {
            appendSeparatorIfNotEmpty(out);
            if (classPath.contains(QUICKPERF_SQL_SPRING_4)) {
                out.append("Import QuickPerfSqlConfig:")
                   .append(LINE_SEPARATOR).append(buildImportQuickPerfSqlConfigExample())
                   .append(LINE_SEPARATOR)
                   .append(LINE_SEPARATOR).append(buildSpringRESTControllerMessage());
            } else {
                out.append("To configure the proxy, add the following dependency: ")
                   .append(LINE_SEPARATOR).append(format(QUICKPERF_SQL_SPRING_4))
                   .append(LINE_SEPARATOR).append("You have also to import QuickPerfSqlConfig:")
                   .append(LINE_SEPARATOR).append(buildImportQuickPerfSqlConfigExample());
            }
            return out.toString();
        }

        if (classPath.containsSpring5()) {
            appendSeparatorIfNotEmpty(out);
            if (classPath.contains(QUICKPERF_SQL_SPRING_5)) {
                out.append("Import QuickPerfSqlConfig:")
                   .append(LINE_SEPARATOR).append(buildImportQuickPerfSqlConfigExample())
                   .append(LINE_SEPARATOR)
                   .append(LINE_SEPARATOR).append(buildSpringRESTControllerMessage());
            } else {
                out.append("To configure the proxy, add the following dependency: ")
                   .append(LINE_SEPARATOR).append(format(QUICKPERF_SQL_SPRING_5))
                   .append(LINE_SEPARATOR).append("You have also to import QuickPerfSqlConfig:")
                   .append(LINE_SEPARATOR).append(buildImportQuickPerfSqlConfigExample());
            }
            return out.toString();
        }

        return out.toString();

    }

    private static void appendSeparatorIfNotEmpty(StringBuilder out) {
        if (out.length() > 0) {
            out.append(LINE_SEPARATOR).append(LINE_SEPARATOR);
        }
    }

    private String buildSpringRESTControllerMessage() {
        return                     "Are you testing a REST controller without MockMvc? Execute the test in"
                + LINE_SEPARATOR + "a dedicated JVM by adding" + " @HeapSize(value = ..., unit = AllocationUnit.MEGA_BYTE)."
                + LINE_SEPARATOR + "A heap size value around 50 megabytes may allow the test to run.";
    }

    private String buildImportQuickPerfSqlConfigExample() {
        return                     "\timport org.quickperf.spring.sql.QuickPerfSqlConfig;"
                + LINE_SEPARATOR + "\t..."
                + LINE_SEPARATOR + "\t@Import(QuickPerfSqlConfig.class)"
                + LINE_SEPARATOR + "\tpublic class TestClass {";
    }


    public String format(QuickPerfDependency quickPerfDependency) {
        return    "\t* Maven"
                + LINE_SEPARATOR
                + quickPerfDependency.toMavenWithVersion()
                + LINE_SEPARATOR
                + "\t* Gradle"
                + LINE_SEPARATOR
                + quickPerfDependency.toGradleWithVersion()
                + LINE_SEPARATOR
                + "\t* Other: " + quickPerfDependency.getMavenSearchLink()
                ;
    }

}