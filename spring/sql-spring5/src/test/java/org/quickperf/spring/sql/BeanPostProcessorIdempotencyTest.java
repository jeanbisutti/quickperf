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

import org.junit.Test;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

public class BeanPostProcessorIdempotencyTest {

    @Test
    public void second_pass_does_not_double_wrap_raw_decorator() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        QuickPerfProxyBeanPostProcessor bpp = new QuickPerfProxyBeanPostProcessor();

        bpp.postProcessAfterInitialization(executor, "executor");
        TaskDecorator first = BeanPostProcessorAutoTaskDecoratorTest.readTaskDecorator(executor);

        bpp.postProcessAfterInitialization(executor, "executor");
        TaskDecorator second = BeanPostProcessorAutoTaskDecoratorTest.readTaskDecorator(executor);

        assertThat(second).isSameAs(first);
        assertThat(second).isNotInstanceOf(QuickPerfComposingTaskDecorator.class);
    }

    @Test
    public void second_pass_does_not_double_wrap_composing_decorator() throws Exception {
        TaskDecorator userDecorator = new TaskDecorator() {
            @Override
            public Runnable decorate(Runnable runnable) {
                return runnable;
            }
        };
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(userDecorator);
        QuickPerfProxyBeanPostProcessor bpp = new QuickPerfProxyBeanPostProcessor();

        bpp.postProcessAfterInitialization(executor, "executor");
        TaskDecorator first = BeanPostProcessorAutoTaskDecoratorTest.readTaskDecorator(executor);
        assertThat(first).isInstanceOf(QuickPerfComposingTaskDecorator.class);

        bpp.postProcessAfterInitialization(executor, "executor");
        TaskDecorator second = BeanPostProcessorAutoTaskDecoratorTest.readTaskDecorator(executor);
        assertThat(second).isSameAs(first);
    }
}
