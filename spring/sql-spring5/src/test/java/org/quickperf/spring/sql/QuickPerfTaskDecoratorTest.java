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
package org.quickperf.spring.sql;

import org.junit.After;
import org.junit.Test;
import org.quickperf.sql.PersistenceSqlRecorder;
import org.quickperf.sql.SqlRecorderRegistry;

import static org.assertj.core.api.Assertions.assertThat;

public class QuickPerfTaskDecoratorTest {

    @After
    public void tearDown() {
        SqlRecorderRegistry.INSTANCE.clear();
    }

    @Test
    public void decorate_returns_input_unchanged_when_no_per_thread_state() {
        QuickPerfTaskDecorator decorator = new QuickPerfTaskDecorator();
        Runnable original = new Runnable() {
            @Override
            public void run() {
            }
        };

        Runnable decorated = decorator.decorate(original);

        assertThat(decorated).isSameAs(original);
    }

    @Test
    public void decorate_wraps_runnable_when_sql_recorder_registered_on_calling_thread() {
        SqlRecorderRegistry.INSTANCE.register(new PersistenceSqlRecorder());

        QuickPerfTaskDecorator decorator = new QuickPerfTaskDecorator();
        Runnable original = new Runnable() {
            @Override
            public void run() {
            }
        };

        Runnable decorated = decorator.decorate(original);

        assertThat(decorated).isNotSameAs(original);
    }
}
