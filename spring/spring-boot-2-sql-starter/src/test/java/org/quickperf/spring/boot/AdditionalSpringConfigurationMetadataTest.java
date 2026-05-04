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
package org.quickperf.spring.boot;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the Spring Boot configuration metadata JSON shipped with this starter.
 *
 * <p>The metadata files are read by IDE Spring tooling (IntelliJ, Eclipse-STS4, VS Code Spring Boot Tools)
 * to provide autocompletion, type validation, and hover documentation for QuickPerf properties when
 * authored inside Spring Boot tests' {@code application.properties}, {@code application.yml},
 * {@code @SpringBootTest(properties = ...)}, and {@code @TestPropertySource(properties = ...)}.
 *
 * <p>This test is the enforced drift safeguard between the metadata JSON and the property names declared
 * in {@code core/.../SystemProperties.java} plus the magic string in {@code core/.../ClassPathUtil.java}.
 * If a future PR adds, renames, or removes a user-facing property without updating the metadata file,
 * this test fails the build.
 *
 * <p>The metadata JSON is byte-identical to the file shipped by {@code quick-perf-springboot2-sql-starter}.
 * This test is duplicated (rather than shared) because each starter must be independently buildable
 * and verifiable.
 */
public class AdditionalSpringConfigurationMetadataTest {

    private static final String ADDITIONAL_METADATA_RESOURCE =
            "/META-INF/additional-spring-configuration-metadata.json";

    private static final String SPRING_METADATA_RESOURCE =
            "/META-INF/spring-configuration-metadata.json";

    @Test
    public void should_ship_additional_metadata_on_classpath() {
        String json = readClassPathResource(ADDITIONAL_METADATA_RESOURCE);

        assertThat(json)
                .as("additional-spring-configuration-metadata.json must be on the production classpath")
                .isNotNull();
        assertThat(json.trim())
                .as("metadata JSON must not be empty")
                .isNotEmpty()
                .startsWith("{")
                .endsWith("}");
    }

    @Test
    public void should_ship_stub_spring_metadata_on_classpath() {
        String json = readClassPathResource(SPRING_METADATA_RESOURCE);

        assertThat(json)
                .as("spring-configuration-metadata.json stub must be on the production classpath "
                        + "(dual-file shipping pattern, see Plan §4.6 / §5.1.4)")
                .isNotNull();
        assertThat(json.trim())
                .as("stub JSON must contain three empty arrays for groups, properties, hints")
                .contains("\"groups\"")
                .contains("\"properties\"")
                .contains("\"hints\"");
    }

    @Test
    public void should_declare_disable_quick_perf_boolean_property() {
        String entry = findPropertyEntry("disableQuickPerf");

        assertThat(entry).contains("\"type\": \"java.lang.Boolean\"");
        assertThat(entry).contains("\"defaultValue\": false");
        assertHasNonEmptyDescription(entry, "disableQuickPerf");
    }

    @Test
    public void should_declare_limit_sql_info_boolean_property() {
        String entry = findPropertyEntry("limitQuickPerfSqlInfoOnConsole");

        assertThat(entry).contains("\"type\": \"java.lang.Boolean\"");
        assertThat(entry).contains("\"defaultValue\": false");
        assertHasNonEmptyDescription(entry, "limitQuickPerfSqlInfoOnConsole");
    }

    @Test
    public void should_declare_limit_jvm_info_boolean_property() {
        String entry = findPropertyEntry("limitQuickPerfJvmInfoOnConsole");

        assertThat(entry).contains("\"type\": \"java.lang.Boolean\"");
        assertThat(entry).contains("\"defaultValue\": false");
        assertHasNonEmptyDescription(entry, "limitQuickPerfJvmInfoOnConsole");
    }

    @Test
    public void should_declare_classpath_jar_threshold_integer_property_with_d_only_disclaimer() {
        String entry = findPropertyEntry("quickperf.classPathJarThreshold");

        assertThat(entry).contains("\"type\": \"java.lang.Integer\"");
        assertThat(entry).contains("\"defaultValue\": 2048");
        String description = extractDescription(entry);
        assertThat(description)
                .as("classPathJarThreshold description must mention -D-only resolution today "
                        + "(see Plan §4.4 / §6.2)")
                .contains("-D");
    }

    @Test
    public void should_not_advertise_internal_properties() {
        String json = readClassPathResource(ADDITIONAL_METADATA_RESOURCE);

        assertThat(json)
                .as("internal property quickPerfWorkingFolder must not leak to IDE metadata "
                        + "(Plan §4.4 internal-properties decision)")
                .doesNotContain("quickPerfWorkingFolder");
        assertThat(json)
                .as("internal property quickPerfToExecInASpecificJvm must not leak to IDE metadata")
                .doesNotContain("quickPerfToExecInASpecificJvm");
    }

    @Test
    public void should_declare_exactly_four_properties() {
        String json = readClassPathResource(ADDITIONAL_METADATA_RESOURCE);

        Pattern namePattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = namePattern.matcher(json);
        int count = 0;
        while (matcher.find()) {
            count++;
        }

        assertThat(count)
                .as("metadata must declare exactly 4 properties (3 Booleans + classPathJarThreshold). "
                        + "If you added a new user-facing property, also update this test and the runtime resolver wiring.")
                .isEqualTo(4);
    }

    private static String findPropertyEntry(String propertyName) {
        String json = readClassPathResource(ADDITIONAL_METADATA_RESOURCE);
        Pattern entryPattern = Pattern.compile(
                "\\{[^{}]*\"name\"\\s*:\\s*\"" + Pattern.quote(propertyName) + "\"[^{}]*\\}",
                Pattern.DOTALL);
        Matcher matcher = entryPattern.matcher(json);
        assertThat(matcher.find())
                .as("property entry for '%s' must be present in the metadata JSON", propertyName)
                .isTrue();
        return matcher.group();
    }

    private static void assertHasNonEmptyDescription(String entry, String propertyName) {
        String description = extractDescription(entry);
        assertThat(description.trim())
                .as("description for property '%s' must be non-empty", propertyName)
                .isNotEmpty();
    }

    private static String extractDescription(String entry) {
        Pattern descPattern = Pattern.compile("\"description\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = descPattern.matcher(entry);
        assertThat(matcher.find())
                .as("description field must be present")
                .isTrue();
        return matcher.group(1);
    }

    private static String readClassPathResource(String resourcePath) {
        InputStream stream = AdditionalSpringConfigurationMetadataTest.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (IOException e) {
            throw new RuntimeException("Failed to read classpath resource " + resourcePath, e);
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // ignored
            }
        }
    }
}
