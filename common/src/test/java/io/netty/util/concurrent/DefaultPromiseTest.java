/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util.concurrent;

import io.netty.util.Signal;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.max;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.*;

@SuppressWarnings("unchecked")
public class DefaultPromiseTest {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultPromiseTest.class);
    private static int stackOverflowDepth;

    @BeforeClass
    public static void beforeClass() {
        try {
            findStackOverflowDepth();
            throw new IllegalStateException("Expected StackOverflowError but didn't get it?!");
        } catch (StackOverflowError e) {
            logger.debug("StackOverflowError depth: {}", stackOverflowDepth);
        }
    }

    private static void findStackOverflowDepth() {
        ++stackOverflowDepth;
        findStackOverflowDepth();
    }

    private static int stackOverflowTestDepth() {
        return max(stackOverflowDepth << 1, stackOverflowDepth);
    }

    @Test
    public void testCancelDoesNotScheduleWhenNoListeners() {
        EventExecutor executor = Mockito.mock(EventExecutor.class);
        Mockito.when(executor.inEventLoop()).thenReturn(false);

        Promise<Void> promise = new DefaultPromise<Void>(executor);
        assertTrue(promise.cancel(false));
        Mockito.verify(executor, Mockito.never()).execute(Mockito.any(Runnable.class));
        assertTrue(promise.isCancelled());
    }

    @Test
    public void testSuccessDoesNotScheduleWhenNoListeners() {
        EventExecutor executor = Mockito.mock(EventExecutor.class);
        Mockito.when(executor.inEventLoop()).thenReturn(false);

        Object value = new Object();
        Promise<Object> promise = new DefaultPromise<Object>(executor);
        promise.setSuccess(value);
        Mockito.verify(executor, Mockito.never()).execute(Mockito.any(Runnable.class));
        assertSame(value, promise.getNow());
    }

    @Test
    public void testFailureDoesNotScheduleWhenNoListeners() {
        EventExecutor executor = Mockito.mock(EventExecutor.class);
        Mockito.when(executor.inEventLoop()).thenReturn(false);

        Exception cause = new Exception();
        Promise<Void> promise = new DefaultPromise<Void>(executor);
        promise.setFailure(cause);
        Mockito.verify(executor, Mockito.never()).execute(Mockito.any(Runnable.class));
        assertSame(cause, promise.cause());
    }

    @Test(expected = CancellationException.class)
    public void testCancellationExceptionIsThrownWhenBlockingGet() throws InterruptedException, ExecutionException {
        final Promise<Void> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        assertTrue(promise.cancel(false));
        promise.get();
    }

    @Test(expected = CancellationException.class)
    public void testCancellationExceptionIsThrownWhenBlockingGetWithTimeout() throws InterruptedException,
            ExecutionException, TimeoutException {
        final Promise<Void> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        assertTrue(promise.cancel(false));
        promise.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void testCancellationExceptionIsReturnedAsCause() throws InterruptedException,
    ExecutionException, TimeoutException {
        final Promise<Void> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        assertTrue(promise.cancel(false));
        assertThat(promise.cause(), instanceOf(CancellationException.class));
    }

    @Test
    public void testStackOverflowWithImmediateEventExecutorA() throws Exception {
        testStackOverFlowChainedFuturesA(stackOverflowTestDepth(), ImmediateEventExecutor.INSTANCE, true);
        testStackOverFlowChainedFuturesA(stackOverflowTestDepth(), ImmediateEventExecutor.INSTANCE, false);
    }

    @Test
    public void testNoStackOverflowWithDefaultEventExecutorA() throws Exception {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            EventExecutor executor = new SingleThreadEventExecutor(executorService);
            try {
                testStackOverFlowChainedFuturesA(stackOverflowTestDepth(), executor, true);
                testStackOverFlowChainedFuturesA(stackOverflowTestDepth(), executor, false);
            } finally {
                executor.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            }
        } finally {
            executorService.shutdown();
        }
    }

    @Test
    public void testNoStackOverflowWithImmediateEventExecutorB() throws Exception {
        testStackOverFlowChainedFuturesB(stackOverflowTestDepth(), ImmediateEventExecutor.INSTANCE, true);
        testStackOverFlowChainedFuturesB(stackOverflowTestDepth(), ImmediateEventExecutor.INSTANCE, false);
    }

    @Test
    public void testNoStackOverflowWithDefaultEventExecutorB() throws Exception {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            EventExecutor executor = new SingleThreadEventExecutor(executorService);
            try {
                testStackOverFlowChainedFuturesB(stackOverflowTestDepth(), executor, true);
                testStackOverFlowChainedFuturesB(stackOverflowTestDepth(), executor, false);
            } finally {
                executor.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            }
        } finally {
            executorService.shutdown();
        }
    }

    @Test
    public void testListenerNotifyOrder() throws Exception {
        EventExecutor executor = new TestEventExecutor();
        try {
            final BlockingQueue<FutureListener<Void>> listeners = new LinkedBlockingQueue<>();
            int runs = 100000;

            for (int i = 0; i < runs; i++) {
                final Promise<Void> promise = new DefaultPromise<>(executor);
                final FutureListener<Void> listener1 = new FutureListener<Void>() {
                    @Override
                    public void operationComplete(Future<Void> future) throws Exception {
                        listeners.add(this);
                    }
                };
                final FutureListener<Void> listener2 = new FutureListener<Void>() {
                    @Override
                    public void operationComplete(Future<Void> future) throws Exception {
                        listeners.add(this);
                    }
                };
                final FutureListener<Void> listener4 = new FutureListener<Void>() {
                    @Override
                    public void operationComplete(Future<Void> future) throws Exception {
                        listeners.add(this);
                    }
                };
                final FutureListener<Void> listener3 = new FutureListener<Void>() {
                    @Override
                    public void operationComplete(Future<Void> future) throws Exception {
                        listeners.add(this);
                        future.addListener(listener4);
                    }
                };

                GlobalEventExecutor.INSTANCE.execute(() -> promise.setSuccess(null));

                promise.addListener(listener1).addListener(listener2).addListener(listener3);

                assertSame("Fail 1 during run " + i + " / " + runs, listener1, listeners.take());
                assertSame("Fail 2 during run " + i + " / " + runs, listener2, listeners.take());
                assertSame("Fail 3 during run " + i + " / " + runs, listener3, listeners.take());
                assertSame("Fail 4 during run " + i + " / " + runs, listener4, listeners.take());
                assertTrue("Fail during run " + i + " / " + runs, listeners.isEmpty());
            }
        } finally {
            executor.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    public void testListenerNotifyLater() throws Exception {
        // Testing first execution path in DefaultPromise
        testListenerNotifyLater(1);

        // Testing second execution path in DefaultPromise
        testListenerNotifyLater(2);
    }

    @Test(timeout = 2000)
    public void testPromiseListenerAddWhenCompleteFailure() throws Exception {
        testPromiseListenerAddWhenComplete(fakeException());
    }

    @Test(timeout = 2000)
    public void testPromiseListenerAddWhenCompleteSuccess() throws Exception {
        testPromiseListenerAddWhenComplete(null);
    }

    @Test(timeout = 2000)
    public void testLateListenerIsOrderedCorrectlySuccess() throws InterruptedException {
        testLateListenerIsOrderedCorrectly(null);
    }

    @Test(timeout = 2000)
    public void testLateListenerIsOrderedCorrectlyFailure() throws InterruptedException {
        testLateListenerIsOrderedCorrectly(fakeException());
    }

    @Test
    public void testSignalRace() {
        final long wait = TimeUnit.NANOSECONDS.convert(10, TimeUnit.SECONDS);
        EventExecutor executor = null;
        try {
            executor = new TestEventExecutor();

            final int numberOfAttempts = 4096;
            final Map<Thread, DefaultPromise<Void>> promises = new HashMap<>();
            for (int i = 0; i < numberOfAttempts; i++) {
                final DefaultPromise<Void> promise = new DefaultPromise<>(executor);
                final Thread thread = new Thread(() -> promise.setSuccess(null));
                promises.put(thread, promise);
            }

            for (final Map.Entry<Thread, DefaultPromise<Void>> promise : promises.entrySet()) {
                promise.getKey().start();
                final long start = System.nanoTime();
                promise.getValue().awaitUninterruptibly(wait, TimeUnit.NANOSECONDS);
                assertThat(System.nanoTime() - start, lessThan(wait));
            }
        } finally {
            if (executor != null) {
                executor.shutdownGracefully();
            }
        }
    }

    @Test
    public void signalUncancellableCompletionValue() {
        final Promise<Signal> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        promise.setSuccess(Signal.valueOf(DefaultPromise.class, "UNCANCELLABLE"));
        assertTrue(promise.isDone());
        assertTrue(promise.isSuccess());
    }

    @Test
    public void signalSuccessCompletionValue() {
        final Promise<Signal> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        promise.setSuccess(Signal.valueOf(DefaultPromise.class, "SUCCESS"));
        assertTrue(promise.isDone());
        assertTrue(promise.isSuccess());
    }

    @Test
    public void setUncancellableGetNow() {
        final Promise<String> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        assertNull(promise.getNow());
        assertTrue(promise.setUncancellable());
        assertNull(promise.getNow());
        assertFalse(promise.isDone());
        assertFalse(promise.isSuccess());

        promise.setSuccess("success");

        assertTrue(promise.isDone());
        assertTrue(promise.isSuccess());
        assertEquals("success", promise.getNow());
    }

    @Test
    public void throwUncheckedSync() throws InterruptedException {
        Exception exception = new Exception();
        final Promise<String> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        promise.setFailure(exception);

        try {
            promise.sync();
        } catch (CompletionException e) {
            assertSame(exception, e.getCause());
        }
    }

    @Test
    public void throwUncheckedSyncUninterruptibly() {
        Exception exception = new Exception();
        final Promise<String> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        promise.setFailure(exception);

        try {
            promise.syncUninterruptibly();
        } catch (CompletionException e) {
            assertSame(exception, e.getCause());
        }
    }

    @Test(expected = CancellationException.class)
    public void throwCancelled() throws InterruptedException {
        final Promise<String> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        promise.cancel(true);
        promise.sync();
    }

    private static void testStackOverFlowChainedFuturesA(int promiseChainLength, final EventExecutor executor,
                                                         boolean runTestInExecutorThread)
            throws InterruptedException {
        final Promise<Void>[] p = new DefaultPromise[promiseChainLength];
        final CountDownLatch latch = new CountDownLatch(promiseChainLength);

        if (runTestInExecutorThread) {
            executor.execute(() -> testStackOverFlowChainedFuturesA(executor, p, latch));
        } else {
            testStackOverFlowChainedFuturesA(executor, p, latch);
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < p.length; ++i) {
            assertTrue("index " + i, p[i].isSuccess());
        }
    }

    private static void testStackOverFlowChainedFuturesA(EventExecutor executor, final Promise<Void>[] p,
                                                         final CountDownLatch latch) {
        for (int i = 0; i < p.length; i ++) {
            final int finalI = i;
            p[i] = new DefaultPromise<>(executor);
            p[i].addListener((FutureListener<Void>) future -> {
                if (finalI + 1 < p.length) {
                    p[finalI + 1].setSuccess(null);
                }
                latch.countDown();
            });
        }

        p[0].setSuccess(null);
    }

    private static void testStackOverFlowChainedFuturesB(int promiseChainLength, final EventExecutor executor,
                                                         boolean runTestInExecutorThread)
            throws InterruptedException {
        final Promise<Void>[] p = new DefaultPromise[promiseChainLength];
        final CountDownLatch latch = new CountDownLatch(promiseChainLength);

        if (runTestInExecutorThread) {
            executor.execute(() -> testStackOverFlowChainedFuturesA(executor, p, latch));
        } else {
            testStackOverFlowChainedFuturesA(executor, p, latch);
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < p.length; ++i) {
            assertTrue("index " + i, p[i].isSuccess());
        }
    }

    private static void testStackOverFlowChainedFuturesB(EventExecutor executor, final Promise<Void>[] p,
                                                         final CountDownLatch latch) {
        for (int i = 0; i < p.length; i ++) {
            final int finalI = i;
            p[i] = new DefaultPromise<>(executor);
            p[i].addListener((FutureListener<Void>) future -> future.addListener((FutureListener<Void>) future1 -> {
                if (finalI + 1 < p.length) {
                    p[finalI + 1].setSuccess(null);
                }
                latch.countDown();
            }));
        }

        p[0].setSuccess(null);
    }

    /**
     * This test is mean to simulate the following sequence of events, which all take place on the I/O thread:
     * <ol>
     * <li>A write is done</li>
     * <li>The write operation completes, and the promise state is changed to done</li>
     * <li>A listener is added to the return from the write. The {@link FutureListener#operationComplete(Future)}
     * updates state which must be invoked before the response to the previous write is read.</li>
     * <li>The write operation</li>
     * </ol>
     */
    private static void testLateListenerIsOrderedCorrectly(Throwable cause) throws InterruptedException {
        final EventExecutor executor = new TestEventExecutor();
        try {
            final AtomicInteger state = new AtomicInteger();
            final CountDownLatch latch1 = new CountDownLatch(1);
            final CountDownLatch latch2 = new CountDownLatch(1);
            final CountDownLatch latch3 = new CountDownLatch(1);

            final Promise<Void> promise = new DefaultPromise<>(executor);

            // Add a listener before completion so "lateListener" is used next time we add a listener.
            promise.addListener((FutureListener<Void>) future -> assertTrue(state.compareAndSet(0, 1)));

            // Simulate write operation completing, which will execute listeners in another thread.
            if (cause == null) {
                promise.setSuccess(null);
            } else {
                promise.setFailure(cause);
            }

            // Add a "late listener"
            promise.addListener((FutureListener<Void>) future -> {
                assertTrue(state.compareAndSet(1, 2));
                latch1.countDown();
            });

            // Wait for the listeners and late listeners to be completed.
            latch1.await();
            assertEquals(2, state.get());

            // This is the important listener. A late listener that is added after all late listeners
            // have completed, and needs to update state before a read operation (on the same executor).
            executor.execute(() -> promise.addListener((FutureListener<Void>) future -> {
                assertTrue(state.compareAndSet(2, 3));
                latch2.countDown();
            }));
            latch2.await();

            // Simulate a read operation being queued up in the executor.
            executor.execute(() -> {
                // This is the key, we depend upon the state being set in the next listener.
                assertEquals(3, state.get());
                latch3.countDown();
            });

            latch3.await();
        } finally {
            executor.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
        }
    }

    private static void testPromiseListenerAddWhenComplete(Throwable cause) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final Promise<Void> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
        promise.addListener((FutureListener<Void>) future ->
                promise.addListener((FutureListener<Void>) future1 -> latch.countDown()));
        if (cause == null) {
            promise.setSuccess(null);
        } else {
            promise.setFailure(cause);
        }
        latch.await();
    }

    private static void testListenerNotifyLater(final int numListenersBefore) throws Exception {
        EventExecutor executor = new TestEventExecutor();
        int expectedCount = numListenersBefore + 2;
        final CountDownLatch latch = new CountDownLatch(expectedCount);
        final FutureListener<Void> listener = future -> latch.countDown();
        final Promise<Void> promise = new DefaultPromise<>(executor);
        executor.execute(() -> {
            for (int i = 0; i < numListenersBefore; i++) {
                promise.addListener(listener);
            }
            promise.setSuccess(null);

            GlobalEventExecutor.INSTANCE.execute(() -> promise.addListener(listener));
            promise.addListener(listener);
        });

        assertTrue("Should have notified " + expectedCount + " listeners",
                   latch.await(5, TimeUnit.SECONDS));
        executor.shutdownGracefully().sync();
    }

    private static final class TestEventExecutor extends SingleThreadEventExecutor {
        TestEventExecutor() {
            super(Executors.defaultThreadFactory());
        }
    }

    private static RuntimeException fakeException() {
        return new RuntimeException("fake exception");
    }
}
