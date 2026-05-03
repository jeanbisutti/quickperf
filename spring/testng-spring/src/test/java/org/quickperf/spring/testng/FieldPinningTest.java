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
package org.quickperf.spring.testng;

import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.testng.annotations.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code testContextManager} private field on
 * {@code AbstractTestNGSpringContextTests} so that
 * {@link SpringTestNGPropertyResolverProvider}'s reflective walk fails fast
 * if a future Spring rename happens.
 */
public class FieldPinningTest {

    @Test
    public void abstract_testng_spring_context_tests_has_test_context_manager_field() throws Exception {
        Field testContextManagerField =
                AbstractTestNGSpringContextTests.class.getDeclaredField("testContextManager");

        assertThat(testContextManagerField).isNotNull();
        assertThat(testContextManagerField.getType().getName())
                .isEqualTo("org.springframework.test.context.TestContextManager");
    }

}
