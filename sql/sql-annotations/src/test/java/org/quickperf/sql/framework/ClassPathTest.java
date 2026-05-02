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
package org.quickperf.sql.framework;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the new R2DBC classpath detection methods.
 *
 * <p>The actual classpath cannot easily be controlled from a unit test, so these
 * tests merely confirm the methods do not throw and return a {@code boolean}.
 * Behavior under controlled classpaths is exercised in the R2DBC starter
 * integration tests.
 */
public class ClassPathTest {

    @Test public void
    contains_r2dbc_spi_should_not_throw() {
        boolean result = ClassPath.INSTANCE.containsR2dbcSpi();
        assertThat(result).isIn(true, false);
    }

    @Test public void
    contains_r2dbc_proxy_should_not_throw() {
        boolean result = ClassPath.INSTANCE.containsR2dbcProxy();
        assertThat(result).isIn(true, false);
    }

    @Test public void
    contains_spring_data_r2dbc_should_not_throw() {
        boolean result = ClassPath.INSTANCE.containsSpringDataR2dbc();
        assertThat(result).isIn(true, false);
    }

}
