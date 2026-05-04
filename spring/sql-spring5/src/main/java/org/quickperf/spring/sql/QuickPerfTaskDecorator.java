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

import org.quickperf.context.QuickPerfContext;
import org.springframework.core.task.TaskDecorator;

/**
 * Spring {@link TaskDecorator} that hooks Spring async tasks into the
 * QuickPerf cross-thread snapshot SPI.
 *
 * <p>Wraps every {@link Runnable} submitted to a configured
 * {@code ThreadPoolTaskExecutor} so that QuickPerf-tracked thread-local
 * state captured on the submitting thread (SQL recorders, connection
 * listeners, ...) is installed on the worker thread for the lifetime
 * of the task and restored when it completes.</p>
 */
public class QuickPerfTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return QuickPerfContext.wrap(runnable);
    }
}
