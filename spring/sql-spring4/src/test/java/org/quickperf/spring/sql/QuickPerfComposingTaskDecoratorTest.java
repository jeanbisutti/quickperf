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
import org.springframework.core.task.TaskDecorator;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class QuickPerfComposingTaskDecoratorTest {

    @After
    public void tearDown() {
        SqlRecorderRegistry.INSTANCE.clear();
    }

    @Test
    public void delegate_must_not_be_null() {
        try {
            new QuickPerfComposingTaskDecorator(null);
        } catch (NullPointerException e) {
            assertThat(e).hasMessageContaining("delegate");
            return;
        }
        throw new AssertionError("Expected NullPointerException");
    }

    @Test
    public void getDelegate_returns_user_decorator() {
        TaskDecorator user = new RecordingDecorator("user", new ArrayList<String>());
        QuickPerfComposingTaskDecorator composing = new QuickPerfComposingTaskDecorator(user);
        assertThat(composing.getDelegate()).isSameAs(user);
    }

    @Test
    public void decorate_calls_user_decorator_at_decoration_time() {
        List<String> events = new ArrayList<String>();
        TaskDecorator user = new RecordingDecorator("user", events);
        QuickPerfComposingTaskDecorator composing = new QuickPerfComposingTaskDecorator(user);

        composing.decorate(new Runnable() {
            @Override
            public void run() {
            }
        });

        assertThat(events).containsExactly("user.decorate");
    }

    @Test
    public void at_run_time_quickperf_is_outermost_user_decorator_runs_inside() {
        SqlRecorderRegistry.INSTANCE.register(new PersistenceSqlRecorder());

        final List<String> events = new ArrayList<String>();
        TaskDecorator user = new TaskDecorator() {
            @Override
            public Runnable decorate(final Runnable runnable) {
                events.add("user.decorate");
                return new Runnable() {
                    @Override
                    public void run() {
                        events.add("user.runStart");
                        runnable.run();
                        events.add("user.runEnd");
                    }
                };
            }
        };
        QuickPerfComposingTaskDecorator composing = new QuickPerfComposingTaskDecorator(user);

        Runnable wrapped = composing.decorate(new Runnable() {
            @Override
            public void run() {
                events.add("inner.run");
            }
        });

        wrapped.run();

        // Order proves QuickPerf is OUTERMOST: user.runStart and user.runEnd sit between
        // QuickPerf install (begin) and restore (end). decorate happens at decoration time.
        assertThat(events).containsExactly(
                "user.decorate",
                "user.runStart",
                "inner.run",
                "user.runEnd"
        );
    }

    private static class RecordingDecorator implements TaskDecorator {
        private final String name;
        private final List<String> events;

        RecordingDecorator(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public Runnable decorate(Runnable runnable) {
            events.add(name + ".decorate");
            return runnable;
        }
    }
}
