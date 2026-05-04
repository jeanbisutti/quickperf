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

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Serializes {@link QuickPerfContext}-mutating test methods across the JVM by holding the
 * monitor on {@link QuickPerfContext}'s class for the duration of each test.
 *
 * <p>Required because the project's Surefire config runs {@code parallel=all,
 * threadCount=5}, and these tests mutate the shared static {@code PROVIDERS} list via
 * {@link QuickPerfContext#setProvidersForTesting}.
 */
public final class QuickPerfContextTestLock implements TestRule {

    @Override
    public Statement apply(final Statement base, Description description) {
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                synchronized (QuickPerfContext.class) {
                    base.evaluate();
                }
            }
        };
    }

}
