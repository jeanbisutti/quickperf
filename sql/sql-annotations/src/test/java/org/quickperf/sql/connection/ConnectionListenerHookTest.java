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

import org.junit.After;
import org.junit.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ConnectionListenerHookTest {

    private final RecordingListener listener1 = new RecordingListener();
    private final RecordingListener listener2 = new RecordingListener();

    @After
    public void cleanUp() {
        ConnectionListenerHook.unregister(listener1);
        ConnectionListenerHook.unregister(listener2);
    }

    @Test public void
    register_should_make_listener_visible_via_get_active_listeners() {

        ConnectionListenerHook.register(listener1);

        assertThat(ConnectionListenerHook.getActiveListeners()).contains(listener1);
    }

    @Test public void
    unregister_should_remove_listener() {

        ConnectionListenerHook.register(listener1);
        ConnectionListenerHook.unregister(listener1);

        assertThat(ConnectionListenerHook.getActiveListeners()).doesNotContain(listener1);
    }

    @Test public void
    get_active_listeners_should_be_unmodifiable() {

        ConnectionListenerHook.register(listener1);

        final Set<SqlConnectionListener> active = ConnectionListenerHook.getActiveListeners();

        ThrowingCallable mutateActive = new ThrowingCallable() {
            @Override public void call() {
                active.add(listener2);
            }
        };
        assertThatThrownBy(mutateActive)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test public void
    listeners_registered_from_a_different_thread_should_be_visible_from_the_main_thread()
            throws InterruptedException {

        final CountDownLatch registered = new CountDownLatch(1);
        Thread other = new Thread(new Runnable() {
            @Override public void run() {
                ConnectionListenerHook.register(listener1);
                registered.countDown();
            }
        });
        other.start();
        registered.await();
        other.join();

        assertThat(ConnectionListenerHook.getActiveListeners()).contains(listener1);
    }

    @Test public void
    listeners_registered_after_a_reader_thread_started_are_still_visible_from_that_thread()
            throws InterruptedException {

        // Reactor scheduler bug repro: a thread that started BEFORE we registered must still see us.
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch readerProceed = new CountDownLatch(1);
        final AtomicInteger seen = new AtomicInteger(-1);
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                threadStarted.countDown();
                try {
                    readerProceed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                seen.set(ConnectionListenerHook.getActiveListeners().size());
            }
        });
        reader.start();
        threadStarted.await();

        // Register AFTER reader thread is already alive.
        ConnectionListenerHook.register(listener1);

        readerProceed.countDown();
        reader.join();

        assertThat(seen.get()).isGreaterThanOrEqualTo(1);
    }

    private static final class RecordingListener extends ConnectionListener {
    }

}
