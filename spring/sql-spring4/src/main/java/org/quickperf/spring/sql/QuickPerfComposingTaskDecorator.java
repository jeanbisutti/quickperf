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

import org.springframework.core.task.TaskDecorator;

/**
 * Composing task decorator that wraps a user-provided
 * {@link TaskDecorator} so that QuickPerf's snapshot capture/install
 * runs <em>outside</em> the user's decorator. The user decorator
 * therefore observes whatever thread-local state QuickPerf has just
 * installed on the worker thread.
 *
 * <p>Subclassing {@link QuickPerfTaskDecorator} keeps the
 * {@code instanceof QuickPerfTaskDecorator} idempotency check used by
 * {@link QuickPerfProxyBeanPostProcessor} valid for both raw and
 * composing variants.</p>
 */
public class QuickPerfComposingTaskDecorator extends QuickPerfTaskDecorator {

    private final TaskDecorator delegate;

    public QuickPerfComposingTaskDecorator(TaskDecorator delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        Runnable userDecorated = delegate.decorate(runnable);
        return super.decorate(userDecorated);
    }

    public TaskDecorator getDelegate() {
        return delegate;
    }
}
