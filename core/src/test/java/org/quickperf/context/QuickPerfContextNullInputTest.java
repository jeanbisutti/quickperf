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
package org.quickperf.context;

import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class QuickPerfContextNullInputTest {

    @Test public void wrap_runnable_null_throws_npe() {
        assertThatThrownBy(new ThrowingCallable() {
            @Override public void call() {
                QuickPerfContext.wrap((Runnable) null);
            }
        }).isInstanceOf(NullPointerException.class).hasMessage("runnable");
    }

    @Test public void wrap_callable_null_throws_npe() {
        assertThatThrownBy(new ThrowingCallable() {
            @Override public void call() {
                QuickPerfContext.wrap((Callable<?>) null);
            }
        }).isInstanceOf(NullPointerException.class).hasMessage("callable");
    }

    @Test public void wrap_executor_null_throws_npe() {
        assertThatThrownBy(new ThrowingCallable() {
            @Override public void call() {
                QuickPerfContext.wrap((Executor) null);
            }
        }).isInstanceOf(NullPointerException.class).hasMessage("executor");
    }

    @Test public void wrap_executor_service_null_throws_npe() {
        assertThatThrownBy(new ThrowingCallable() {
            @Override public void call() {
                QuickPerfContext.wrap((ExecutorService) null);
            }
        }).isInstanceOf(NullPointerException.class).hasMessage("executorService");
    }

    @Test public void wrap_scheduled_executor_service_null_throws_npe() {
        assertThatThrownBy(new ThrowingCallable() {
            @Override public void call() {
                QuickPerfContext.wrap((ScheduledExecutorService) null);
            }
        }).isInstanceOf(NullPointerException.class).hasMessage("scheduledExecutorService");
    }

    /** Local interface to avoid AssertJ 2.x lambda syntax (project targets JDK 1.7). */
    private interface ThrowingCallable extends org.assertj.core.api.ThrowableAssert.ThrowingCallable {}

}
