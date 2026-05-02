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
package org.quickperf.spring.boot.r2dbc;

/**
 * Marker interface implemented by JDK proxies that already wrap a
 * {@code ConnectionFactory} with QuickPerf's R2DBC proxy listener, so the
 * {@link QuickPerfR2dbcProxyBeanPostProcessor} can detect — and skip — already
 * wrapped beans (e.g. when the BPP processes the same factory more than once
 * across child contexts).
 */
public interface QuickPerfR2dbcProxyMarker {
}
