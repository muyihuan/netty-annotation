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

import io.netty.util.internal.StringUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

//默认回调的实现;
public class DefaultPromise<V> implements Promise<V> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultPromise.class);
    private static final InternalLogger rejectedExecutionLogger =
            InternalLoggerFactory.getInstance(DefaultPromise.class.getName() + ".rejectedExecution");
    // 回调的状态更新器;
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "result");
    // 成功状态;
    private static final Object SUCCESS = new Object();
    // 不可取消状态;
    private static final Object UNCANCELLABLE = new Object();
    // 取消状态;
    private static final CauseHolder CANCELLATION_CAUSE_HOLDER = new CauseHolder(ThrowableUtil.unknownStackTrace(
            new CancellationException(), DefaultPromise.class, "cancel(...)"));
    // 异常栈;
    private static final StackTraceElement[] CANCELLATION_STACK = CANCELLATION_CAUSE_HOLDER.cause.getStackTrace();
    // 结果存储了回调的状态和异常信息;
    private volatile Object result;
    // 执行器eventLoop;
    private final EventExecutor executor;

    // It is fine to not make this volatile as even if we override the value in there it does not matter as
    // DefaultFutureCompletionStage has no state itself and is just a wrapper around this DefaultPromise instance.
    //
    // 执行到什么阶段了
    private DefaultFutureCompletionStage<V> stage;

    /**
     * One or more listeners. Can be a {@link GenericFutureListener} or a {@link DefaultFutureListeners}.
     * If {@code null}, it means either 1) no listeners were added yet or 2) all listeners were notified.
     *
     * Threading - synchronized(this). We must support adding listeners when there is no EventExecutor.
     * 回调函数;
     */
    private Object listeners;
    /**
     * Threading - synchronized(this). We are required to hold the monitor to use Java's underlying wait()/notifyAll().
     *
     * 大于零的话需要notifyAll,来唤醒等待的这些线程;
     */
    private short waiters;

    /**
     * Creates a new instance.
     *
     * It is preferable to use {@link EventExecutor#newPromise()} to create a new promise
     *
     * 最好调用eventLoop来创建promise;
     *
     * @param executor
     *        the {@link EventExecutor} which is used to notify the promise once it is complete.
     *        It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     *        The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     *        depth exceeds a threshold.
     *
     *  executor可以用来执行回调,这里<<假定>>了通过把回调当作task让eventLoop来执行,可以防止堆栈溢出;
     */
    public DefaultPromise(EventExecutor executor) {
        this.executor = requireNonNull(executor, "executor");
        stage = new DefaultFutureCompletionStage<>(this);
    }

    @Override
    public Promise<V> setSuccess(V result) {
        if (setSuccess0(result)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    @Override
    public boolean trySuccess(V result) {
        return setSuccess0(result);
    }

    @Override
    public Promise<V> setFailure(Throwable cause) {
        if (setFailure0(cause)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this, cause);
    }

    @Override
    public boolean tryFailure(Throwable cause) {
        return setFailure0(cause);
    }

    @Override
    public boolean setUncancellable() {
        // 标志无法进行取消状态,成功返回true;
        if (RESULT_UPDATER.compareAndSet(this, null, UNCANCELLABLE)) {
            return true;
        }
        Object result = this.result;
        // 不成功的话要看此时状态是否是不可取消状态;
        return !isDone0(result) || !isCancelled0(result);
    }

    @Override
    public boolean isSuccess() {
        Object result = this.result;
        return result != null && result != UNCANCELLABLE && !(result instanceof CauseHolder);
    }

    @Override
    public boolean isCancellable() {
        return result == null;
    }

    // 倾向于取消相关的异常;
    private static final class LeanCancellationException extends CancellationException {
        private static final long serialVersionUID = 2794674970981187807L;

        @Override
        public Throwable fillInStackTrace() {
            setStackTrace(CANCELLATION_STACK);
            return this;
        }

        @Override
        public String toString() {
            return CancellationException.class.getName();
        }
    }

    @Override
    public Throwable cause() {
        return cause0(result);
    }

    // 返回这个回调的任务执行是否发生异常;
    private Throwable cause0(Object result) {
        if (!(result instanceof CauseHolder)) {
            return null;
        }
        if (result == CANCELLATION_CAUSE_HOLDER) {
            CancellationException ce = new LeanCancellationException();
            if (RESULT_UPDATER.compareAndSet(this, CANCELLATION_CAUSE_HOLDER, new CauseHolder(ce))) {
                return ce;
            }
            result = this.result;
        }
        return ((CauseHolder) result).cause;
    }

    @Override
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        requireNonNull(listener, "listener");

        synchronized (this) {
            addListener0(listener);
        }

        // 已经成功了;
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        requireNonNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                addListener0(listener);
            }
        }

        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
        requireNonNull(listener, "listener");

        synchronized (this) {
            removeListener0(listener);
        }

        return this;
    }

    @Override
    public Promise<V> removeListeners(final GenericFutureListener<? extends Future<? super V>>... listeners) {
        requireNonNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                removeListener0(listener);
            }
        }

        return this;
    }

    // wait;
    @Override
    public Promise<V> await() throws InterruptedException {
        // 1.判断要回调的任务是否完成了,完成了就直接返回;
        if (isDone()) {
            return this;
        }

        // 2.如果该线程是中断状态,那么就抛异常,因为被设为中断状态的线程想要结束线程,因为想要关闭线程直接用stop会有很多问题;
        // 所以设置中断状态为true,那么就抛异常就好了,外部怎么处理也就是外部的事了;
        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        // 3.检查是否是eventLoop发起的,如果是的话就抛异常;
        checkDeadLock();

        // 4.进入真正的等待,主要是通过wait实现,等待成功后的notify;
        synchronized (this) {
            while (!isDone()) {
                // 5.增加等待的线程数量;
                incWaiters();
                try {
                    wait();
                } finally {
                    // 6.相应减少等待的线程数量;
                    decWaiters();
                }
            }
        }

        // 7.等待结束;
        return this;
    }

    // 和上面相比少了线程中断的处理不一样;
    @Override
    public Promise<V> awaitUninterruptibly() {
        if (isDone()) {
            return this;
        }

        checkDeadLock();

        boolean interrupted = false;
        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Interrupted while waiting.
                    interrupted = true;
                } finally {
                    decWaiters();
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return this;
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return await0(unit.toNanos(timeout), true);
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await0(MILLISECONDS.toNanos(timeoutMillis), true);
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        try {
            return await0(unit.toNanos(timeout), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(MILLISECONDS.toNanos(timeoutMillis), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public V getNow() {
        Object result = this.result;
        if (result instanceof CauseHolder || result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        return (V) result;
    }

    // 获取结果;
    @SuppressWarnings("unchecked")
    @Override
    public V get() throws InterruptedException, ExecutionException {
        Object result = this.result;
        // 1.判断是否完成了,没完成就等;
        if (!isDone0(result)) {
            await();
            result = this.result;
        }
        // 2.如果没有失败或者发生异常就返回null;
        if (result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        Throwable cause = cause0(result);
        // 3.如果没有发生异常了,那么也返回result;
        if (cause == null) {
            return (V) result;
        }
        // 4.如果发生异常了就抛异常;
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

    @SuppressWarnings("unchecked")
    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Object result = this.result;
        if (!isDone0(result)) {
            if (!await(timeout, unit)) {
                throw new TimeoutException();
            }
            result = this.result;
        }
        if (result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        Throwable cause = cause0(result);
        if (cause == null) {
            return (V) result;
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     *
     * 取消相应的任务;
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        // 1.修改result;
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE_HOLDER)) {
            // 2.检查是否需要进行回调;
            if (checkNotifyWaiters()) {
                notifyListeners();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled0(result);
    }

    @Override
    public boolean isDone() {
        return isDone0(result);
    }

    @Override
    public Promise<V> sync() throws InterruptedException {
        await();
        rethrowIfFailed();
        return this;
    }

    @Override
    public Promise<V> syncUninterruptibly() {
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    @Override
    public String toString() {
        return toStringBuilder().toString();
    }

    protected StringBuilder toStringBuilder() {
        StringBuilder buf = new StringBuilder(64)
                .append(StringUtil.simpleClassName(this))
                .append('@')
                .append(Integer.toHexString(hashCode()));

        Object result = this.result;
        if (result == SUCCESS) {
            buf.append("(success)");
        } else if (result == UNCANCELLABLE) {
            buf.append("(uncancellable)");
        } else if (result instanceof CauseHolder) {
            buf.append("(failure: ")
                    .append(((CauseHolder) result).cause)
                    .append(')');
        } else if (result != null) {
            buf.append("(success: ")
                    .append(result)
                    .append(')');
        } else {
            buf.append("(incomplete)");
        }

        return buf;
    }

    /**
     * Get the executor used to notify listeners when this promise is complete.
     * <p>
     * It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     * The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     * depth exceeds a threshold.
     * @return The executor used to notify listeners when this promise is complete.
     */
    @Override
    public final EventExecutor executor() {
        return executor;
    }

    protected void checkDeadLock() {
        checkDeadLock(executor);
    }

    // 检查死锁,如果一些阻塞或者等待操作是执行器线程发起的,那么执行器线程就等待了,但是只有自己才会去唤醒自己,从而导致死锁了;
    protected final void checkDeadLock(EventExecutor executor) {
        if (executor.inEventLoop()) {
            throw new BlockingOperationException(toString());
        }
    }

    private void notifyListeners() {
        safeExecute(executor(), this::notifyListenersNow);
    }

    // 执行回调;
    private void notifyListenersNow() {
        Object listeners;
        // 1.进行同步将listeners置为null,只由一个线程来完成;
        synchronized (this) {
            // Only proceed if there are listeners to notify.
            if (this.listeners == null) {
                return;
            }
            listeners = this.listeners;
            this.listeners = null;
        }
        // 2.不断查看是否有新的回调产生,一直执行到没有回调了;
        for (;;) {
            if (listeners instanceof DefaultFutureListeners) {
                notifyListeners0((DefaultFutureListeners) listeners);
            } else {
                notifyListener0(this, (GenericFutureListener<?>) listeners);
            }
            synchronized (this) {
                if (this.listeners == null) {
                    return;
                }
                listeners = this.listeners;
                this.listeners = null;
            }
        }
    }

    private void notifyListeners0(DefaultFutureListeners listeners) {
        GenericFutureListener<?>[] a = listeners.listeners();
        int size = listeners.size();
        for (int i = 0; i < size; i ++) {
            notifyListener0(this, a[i]);
        }
    }

    // 执行用户的回调;
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void notifyListener0(Future future, GenericFutureListener l) {
        try {
            l.operationComplete(future);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationComplete()", t);
            }
        }
    }

    private void addListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners == null) {
            listeners = listener;
        } else if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).add(listener);
        } else {
            listeners = new DefaultFutureListeners((GenericFutureListener<?>) listeners, listener);
        }
    }

    private void removeListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).remove(listener);
        } else if (listeners == listener) {
            listeners = null;
        }
    }

    private boolean setSuccess0(V result) {
        return setValue0(result == null ? SUCCESS : result);
    }

    private boolean setFailure0(Throwable cause) {
        return setValue0(new CauseHolder(requireNonNull(cause, "cause")));
    }

    private boolean setValue0(Object objResult) {
        if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||
            RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) {
            // 如果由等待的线程要唤醒它们;
            if (checkNotifyWaiters()) {
                notifyListeners();
            }
            return true;
        }
        return false;
    }

    /**
     * Check if there are any waiters and if so notify these.
     * @return {@code true} if there are any listeners attached to the promise, {@code false} otherwise.
     *
     * 如果由等待的线程要唤醒它们;
     */
    private synchronized boolean checkNotifyWaiters() {
        if (waiters > 0) {
            notifyAll();
        }
        return listeners != null;
    }

    private void incWaiters() {
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }
        ++waiters;
    }

    private void decWaiters() {
        --waiters;
    }

    private void rethrowIfFailed() {
        Throwable cause = cause();
        if (cause == null) {
            return;
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new CompletionException(cause);
    }

    // 带有超时事件的等待;
    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        // 1.判断相应的任务是否已经执行完了;
        if (isDone()) {
            return true;
        }

        // 2.超时事件不大于0那么直接返回是否完成的结果;
        if (timeoutNanos <= 0) {
            return isDone();
        }

        // 3.根据条件来对线程中断的处理;
        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        // 4.检查死锁;
        checkDeadLock();

        long startTime = System.nanoTime();
        long waitTime = timeoutNanos;
        boolean interrupted = false;
        try {
            for (;;) {
                // 5.执行wait进行等待;
                synchronized (this) {
                    if (isDone()) {
                        return true;
                    }
                    // 6.等待数加加;
                    incWaiters();
                    try {
                        wait(waitTime / 1000000, (int) (waitTime % 1000000));
                    } catch (InterruptedException e) {
                        if (interruptable) {
                            throw e;
                        } else {
                            interrupted = true;
                        }
                    } finally {
                        // 7.等待数减减;
                        decWaiters();
                    }
                }
                // 8.等待结束如果对应任务完成了那么返回true;
                if (isDone()) {
                    return true;
                } else {
                    // 9.等待可能是发生中断异常导致的,如果超时事件还没到,任务也没完成,那么再继续等待;
                    waitTime = timeoutNanos - (System.nanoTime() - startTime);
                    // 10.超时时间到了进行返回;
                    if (waitTime <= 0) {
                        return isDone();
                    }
                }
            }
        } finally {
            // 11.如果之前线程状态是中断状态,再将线程置为中断状态;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Notify all progressive listeners.
     * <p>
     * No attempt is made to ensure notification order if multiple calls are made to this method before
     * the original invocation completes.
     * <p>
     * This will do an iteration over all listeners to get all of type {@link GenericProgressiveFutureListener}s.
     * @param progress the new progress.
     * @param total the total progress.
     *
     * listeners里还包含listeners,这个处理的就是这些;
     */

    //-------------------------???question????------------------
    @SuppressWarnings("unchecked")
    void notifyProgressiveListeners(final long progress, final long total) {
        final Object listeners = progressiveListeners();
        if (listeners == null) {
            return;
        }

        final ProgressiveFuture<V> self = (ProgressiveFuture<V>) this;

        if (listeners instanceof GenericProgressiveFutureListener[]) {
            final GenericProgressiveFutureListener<?>[] array =
                    (GenericProgressiveFutureListener<?>[]) listeners;
            safeExecute(executor(), () -> notifyProgressiveListeners0(self, array, progress, total));
        } else {
            final GenericProgressiveFutureListener<ProgressiveFuture<V>> l =
                    (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners;
            safeExecute(executor(), () -> notifyProgressiveListener0(self, l, progress, total));
        }
    }

    /**
     * Returns a {@link GenericProgressiveFutureListener}, an array of {@link GenericProgressiveFutureListener}, or
     * {@code null}.
     */
    private synchronized Object progressiveListeners() {
        Object listeners = this.listeners;
        if (listeners == null) {
            // No listeners added
            return null;
        }

        if (listeners instanceof DefaultFutureListeners) {
            // Copy DefaultFutureListeners into an array of listeners.
            DefaultFutureListeners dfl = (DefaultFutureListeners) listeners;
            int progressiveSize = dfl.progressiveSize();
            switch (progressiveSize) {
                case 0:
                    return null;
                case 1:
                    for (GenericFutureListener<?> l: dfl.listeners()) {
                        if (l instanceof GenericProgressiveFutureListener) {
                            return l;
                        }
                    }
                    return null;
            }

            GenericFutureListener<?>[] array = dfl.listeners();
            GenericProgressiveFutureListener<?>[] copy = new GenericProgressiveFutureListener[progressiveSize];
            for (int i = 0, j = 0; j < progressiveSize; i ++) {
                GenericFutureListener<?> l = array[i];
                if (l instanceof GenericProgressiveFutureListener) {
                    copy[j ++] = (GenericProgressiveFutureListener<?>) l;
                }
            }

            return copy;
        } else if (listeners instanceof GenericProgressiveFutureListener) {
            return listeners;
        } else {
            // Only one listener was added and it's not a progressive listener.
            return null;
        }
    }

    private static void notifyProgressiveListeners0(
            ProgressiveFuture<?> future, GenericProgressiveFutureListener<?>[] listeners, long progress, long total) {
        for (GenericProgressiveFutureListener<?> l: listeners) {
            if (l == null) {
                break;
            }
            notifyProgressiveListener0(future, l, progress, total);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyProgressiveListener0(
            ProgressiveFuture future, GenericProgressiveFutureListener l, long progress, long total) {
        try {
            l.operationProgressed(future, progress, total);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationProgressed()", t);
            }
        }
    }

    private static boolean isCancelled0(Object result) {
        return result instanceof CauseHolder && ((CauseHolder) result).cause instanceof CancellationException;
    }

    // 是否结束,成功和失败都算结束;
    private static boolean isDone0(Object result) {
        return result != null && result != UNCANCELLABLE;
    }

    private static final class CauseHolder {
        final Throwable cause;
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    static void safeExecute(EventExecutor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (Throwable t) {
            rejectedExecutionLogger.error("Failed to submit a listener notification task. Event loop shut down?", t);
        }
    }

    @Override
    public FutureCompletionStage<V> asStage() {
        DefaultFutureCompletionStage<V> stageAdapter = stage;
        if (stageAdapter == null) {
            stage = stageAdapter = new DefaultFutureCompletionStage<>(this);
        }
        return stageAdapter;
    }
}
