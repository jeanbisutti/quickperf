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

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

public class BeanPostProcessorAutoTaskDecoratorTest {

    @Test
    public void bpp_installs_quickperf_task_decorator_on_fresh_executor() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        QuickPerfProxyBeanPostProcessor bpp = new QuickPerfProxyBeanPostProcessor();

        bpp.postProcessAfterInitialization(executor, "executor");

        TaskDecorator decorator = readTaskDecorator(executor);
        assertThat(decorator).isInstanceOf(QuickPerfTaskDecorator.class);
        assertThat(decorator).isNotInstanceOf(QuickPerfComposingTaskDecorator.class);
    }

    @Test
    public void bpp_does_not_decorate_unrelated_beans() {
        QuickPerfProxyBeanPostProcessor bpp = new QuickPerfProxyBeanPostProcessor();
        Object plainBean = new Object();

        Object result = bpp.postProcessAfterInitialization(plainBean, "plain");

        assertThat(result).isSameAs(plainBean);
    }

    static TaskDecorator readTaskDecorator(ThreadPoolTaskExecutor executor) throws Exception {
        Field field = ThreadPoolTaskExecutor.class.getDeclaredField("taskDecorator");
        field.setAccessible(true);
        return (TaskDecorator) field.get(executor);
    }
}
