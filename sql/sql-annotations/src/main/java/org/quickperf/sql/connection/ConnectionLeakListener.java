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
package org.quickperf.sql.connection;

import org.quickperf.TestExecutionContext;
import org.quickperf.WorkingFolder;
import org.quickperf.measure.BooleanMeasure;
import org.quickperf.perfrecording.RecordablePerformance;
import org.quickperf.repository.BooleanMeasureRepository;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks connection acquisition and release events using opaque connection ids
 * supplied by {@link SqlConnectionEvent}, so that the same listener instance
 * can detect leaks for both JDBC connections (via the neutral events fired by
 * {@link QuickPerfDatabaseConnection}) and reactive R2DBC connections (via the
 * R2DBC connection-lifecycle listener that dispatches against
 * {@link ConnectionListenerHook}).
 *
 * <p>The listener registers exclusively with {@link ConnectionListenerHook};
 * it does not subscribe to {@link ConnectionListenerRegistry} because the
 * registry's {@link InheritableThreadLocal} storage is unreliable from
 * Reactor scheduler threads, and the JDBC neutral events dispatched from
 * {@link QuickPerfDatabaseConnection} already reach the hook.
 */
public class ConnectionLeakListener extends ConnectionListener
        implements RecordablePerformance<BooleanMeasure> {

    private final Set<String> openConnectionIds =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private BooleanMeasure connectionLeak;

    private static final String CONNECTION_LEAK_FILE_NAME = "connection-leak.ser";

    @Override
    public void onConnectionAcquired(SqlConnectionEvent event) {
        openConnectionIds.add(event.getConnectionId());
    }

    @Override
    public void onConnectionReleased(SqlConnectionEvent event) {
        openConnectionIds.remove(event.getConnectionId());
    }

    /**
     * Number of connection ids currently observed as acquired but not released.
     */
    public int countLeakedConnections() {
        return openConnectionIds.size();
    }

    @Override
    public void startRecording(TestExecutionContext testExecutionContext) {
        openConnectionIds.clear();
        ConnectionListenerHook.register(this);
    }

    @Override
    public void stopRecording(TestExecutionContext testExecutionContext) {
        ConnectionListenerHook.unregister(this);
        connectionLeak = BooleanMeasure.of(!openConnectionIds.isEmpty());
        openConnectionIds.clear();
        if (testExecutionContext.testExecutionUsesTwoJVMs()) {
            WorkingFolder workingFolder = testExecutionContext.getWorkingFolder();
            BooleanMeasureRepository.INSTANCE.save(connectionLeak, workingFolder, CONNECTION_LEAK_FILE_NAME);
        }
    }

    @Override
    public BooleanMeasure findRecord(TestExecutionContext testExecutionContext) {
        if (testExecutionContext.testExecutionUsesTwoJVMs()) {
            WorkingFolder workingFolder = testExecutionContext.getWorkingFolder();
            return BooleanMeasureRepository.INSTANCE.find(workingFolder, CONNECTION_LEAK_FILE_NAME);
        }
        return connectionLeak;
    }

    @Override
    public void cleanResources() { }

}
