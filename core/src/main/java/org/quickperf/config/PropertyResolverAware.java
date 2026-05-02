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
package org.quickperf.config;

/**
 * Capability interface implemented by {@link org.quickperf.perfrecording.PerfRecord}s
 * that need a {@link PropertyResolver} at format time.
 *
 * <p>Allows {@link org.quickperf.issue.PerfIssuesEvaluator} to inject the
 * resolver without core depending on sql-annotations or any other downstream
 * module.
 */
public interface PropertyResolverAware {

    /**
     * Sets the property resolver to use when formatting perf issues.
     *
     * <p>Implementations on shared singletons (e.g. {@code SqlExecutions.NONE})
     * must guard against cross-test contamination by ignoring the call on
     * those singleton instances.
     */
    void setPropertyResolver(PropertyResolver propertyResolver);

}
