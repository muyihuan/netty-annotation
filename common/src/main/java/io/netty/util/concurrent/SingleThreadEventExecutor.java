/*
 * Copyright 2012 The Netty Project
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

import static java.util.Objects.requireNonNull;

import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * {@link OrderedEventExecutor}'s implementation that execute all its submitted tasks in a single thread.
 *
 */
public class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {

    //默认最大任务队列大小;
    protected static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);

    //singleThread没有启动;
    private static final int ST_NOT_STARTED = 1;
    //singleThread已启动;
    private static final int ST_STARTED = 2;
    //singleThread正在关闭;
    private static final int ST_SHUTTING_DOWN = 3;
    //singleThread已关闭;
    private static final int ST_SHUTDOWN = 4;
    //singleThread线程终止;
    private static final int ST_TERMINATED = 5;

    private static final Runnable WAKEUP_TASK = () -> {
        // Do nothing.
    };
    private static final Runnable NOOP_TASK = () -> {
        // Do nothing.
    };

    //更新状态的updater;
    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state");
    //更新线程参数的updater;
    private static final AtomicReferenceFieldUpdater<SingleThreadEventExecutor, ThreadProperties> PROPERTIES_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    SingleThreadEventExecutor.class, ThreadProperties.class, "threadProperties");

    //任务队列;
    private final Queue<Runnable> taskQueue;
    //保存执行器线程本身;
    private volatile Thread thread;
    //???question:未知???
    @SuppressWarnings("unused")
    private volatile ThreadProperties threadProperties;
    //用来起线程的;
    private final Executor executor;
    //线程是否中断;
    private volatile boolean interrupted;
    //只有执行任务完成了才会countdown,关闭的时候进行保证任务完成了才可以关闭,否则会阻塞影响执行任务的操作;
    private final CountDownLatch threadLock = new CountDownLatch(1);
    //关闭的钩子;
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<>();
    //是否可以添加wakeup task来启动执行器,因为如果是blockingQueue当队列满的时候或者为空的时候的offer或poll等操作会发生阻塞,导致线程阻塞掉;
    private final boolean addTaskWakesUp;
    //拒绝策略;
    private final RejectedExecutionHandler rejectedExecutionHandler;
    //上次执行时间;
    private long lastExecutionTime;

    @SuppressWarnings({ "FieldMayBeFinal", "unused" })
    private volatile int state = ST_NOT_STARTED;
    //优雅关闭时期;
    private volatile long gracefulShutdownQuietPeriod;
    //优雅关闭超时时间;
    private volatile long gracefulShutdownTimeout;
    //优雅关闭的开始时间;
    private long gracefulShutdownStartTime;
    //线程终止回调;
    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    /**
     * Create a new instance
     */
    public SingleThreadEventExecutor() {
        this(new DefaultThreadFactory(SingleThreadEventExecutor.class));
    }

    /**
     * Create a new instance
     *
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     */
    public SingleThreadEventExecutor(ThreadFactory threadFactory) {
        this(new ThreadPerTaskExecutor(threadFactory));
    }

    /**
     * Create a new instance
     *
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    public SingleThreadEventExecutor(ThreadFactory threadFactory,
            int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
        this(new ThreadPerTaskExecutor(threadFactory), maxPendingTasks, rejectedHandler);
    }

    /**
     * Create a new instance
     *
     * @param executor          the {@link Executor} which will be used for executing
     */
    public SingleThreadEventExecutor(Executor executor) {
        this(executor, DEFAULT_MAX_PENDING_EXECUTOR_TASKS, RejectedExecutionHandlers.reject());
    }

    /**
     * Create a new instance
     *
     * @param executor          the {@link Executor} which will be used for executing
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    public SingleThreadEventExecutor(Executor executor, int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
        this.executor = ThreadExecutorMap.apply(executor, this);
        taskQueue = newTaskQueue(Math.max(16, maxPendingTasks));
        this.addTaskWakesUp = taskQueue instanceof BlockingQueue;
        rejectedExecutionHandler = requireNonNull(rejectedHandler, "rejectedHandler");
    }

    /**
     * Create a new {@link Queue} which will holds the tasks to execute. This default implementation will return a
     * {@link LinkedBlockingQueue} but if your sub-class of {@link SingleThreadEventExecutor} will not do any blocking
     * calls on the this {@link Queue} it may make sense to {@code @Override} this and return some more performant
     * implementation that does not support blocking operations at all.
     *
     * Be aware that the implementation of {@link #run()} depends on a {@link BlockingQueue} so you will need to
     * override {@link #run()} as well if you return a non {@link BlockingQueue} from this method.
     *
     * As this method is called from within the constructor you can only use the parameters passed into the method when
     * overriding this method.
     */
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return new LinkedBlockingQueue<>(maxPendingTasks);
    }

    /**
     * Interrupt the current running {@link Thread}.
     */
    protected final void interruptThread() {
        Thread currentThread = thread;
        if (currentThread == null) {
            interrupted = true;
        } else {
            currentThread.interrupt();
        }
    }

    /**
     * @see Queue#poll()
     *
     * This method must be called from the {@link EventExecutor} thread.
     *
     * 获取task;
     */
    protected final Runnable pollTask() {
        assert inEventLoop();

        for (;;) {
            Runnable task = taskQueue.poll();
            if (task == WAKEUP_TASK) {
                continue;
            }
            return task;
        }
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     * <p>
     * Be aware that this method will throw an {@link UnsupportedOperationException} if the task queue, which was
     * created via {@link #newTaskQueue(int)}, does not implement {@link BlockingQueue}.
     * </p>
     *
     * This method must be called from the {@link EventExecutor} thread.
     *
     * @return {@code null} if the executor thread has been interrupted or waken up.
     *
     * 执行task;
     */
    protected final Runnable takeTask() {
        assert inEventLoop();
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        for (;;) {
            //1.先执行延时任务;
            RunnableScheduledFuture<?> scheduledTask = peekScheduledTask();
            //如果延时任务为空,那么执行普通任务;
            if (scheduledTask == null) {
                Runnable task = null;
                try {
                    task = taskQueue.take();
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                //延时时间大于0,说明还没到期;
                if (delayNanos > 0) {
                    try {
                        //没到期就先看普通task队列是否有task,有的话就拿取;
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                }
                if (task == null) {
                    // We need to fetch the scheduled tasks now as otherwise there may be a chance that
                    // scheduled tasks are never executed if there is always one task in the taskQueue.
                    // This is for example true for the read task of OIO Transport
                    // See https://github.com/netty/netty/issues/1614
                    //我们需要立即获取计划任务，否则如果任务队列中始终有一个任务，则可能永远不会执行计划任务。
                    // 例如，OIO Transport的read任务就是这样
                    //从延时队列里拿取task,放到普通任务队列里,到期了就先执行这个,来防止上面的情况;
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    //从延时队列里拿取task,放到普通任务队列里;
    private boolean fetchFromScheduledTaskQueue() {
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        RunnableScheduledFuture<?> scheduledTask  = pollScheduledTask(nanoTime);
        while (scheduledTask != null) {
            if (!taskQueue.offer(scheduledTask)) {
                // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
                schedule(scheduledTask);
                return false;
            }
            scheduledTask  = pollScheduledTask(nanoTime);
        }

        //返回true代表延时队列里没有到期任务了,如果返回false说明还有到期任务,只不过还没有放到任务队列里;
        return true;
    }

    /**
     * @see Queue#isEmpty()
     *
     * 返回任务队列是否为空;
     */
    protected final boolean hasTasks() {
        return !taskQueue.isEmpty();
    }

    /**
     * Return the number of tasks that are pending for processing (excluding the scheduled tasks).
     *
     * 返回任务队列的大小;
     */
    public final int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     *
     * 添加任务,如果添加失败那么执行拒绝策略;
     */
    private void addTask(Runnable task) {
        if (!offerTask(task)) {
            rejectedExecutionHandler.rejected(task, this);
        }
    }

    /**
     * @see Queue#offer(Object)
     *
     * 添加任务;
     */
    protected final boolean offerTask(Runnable task) {
        requireNonNull(task, "task");
        if (isShutdown()) {
            reject();
        }
        return taskQueue.offer(task);
    }

    /**
     * @see Queue#remove(Object)
     */
    protected final boolean removeTask(Runnable task) {
        return taskQueue.remove(task);
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * This method must be called from the {@link EventExecutor} thread.
     *
     * @return {@code true} if and only if at least one task was run
     *
     * 执行全部任务;
     */
    private boolean runAllTasks() {
        boolean fetchedAll;
        do {
            fetchedAll = fetchFromScheduledTaskQueue();
            Runnable task = pollTask();
            if (task == null) {
                return false;
            }

            do {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.warn("A task raised an exception.", t);
                }
            } while ((task = pollTask()) != null);
        } while (!fetchedAll); // keep on processing until we fetched all scheduled tasks.

        updateLastExecutionTime();
        return true;
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * This method must be called from the {@link EventExecutor} thread.
     *
     * @return the number of processed tasks.
     *
     * 执行全部任务,多了一个最大任务量限制;
     */
    protected int runAllTasks(int maxTasks) {
        assert inEventLoop();
        boolean fetchedAll;
        int processedTasks = 0;
        do {
            fetchedAll = fetchFromScheduledTaskQueue();
            for (; processedTasks < maxTasks; processedTasks++) {
                Runnable task = pollTask();
                if (task == null) {
                    break;
                }

                try {
                    task.run();
                } catch (Throwable t) {
                    logger.warn("A task raised an exception.", t);
                }
            }
        } while (!fetchedAll && processedTasks < maxTasks); // keep on processing until we fetched all scheduled tasks.

        if (processedTasks > 0) {
            // Only call if we at least executed one task.
            updateLastExecutionTime();
        }
        return processedTasks;
    }

    /**
     * Returns the amount of time left until the scheduled task with the closest dead line is executed.
     *
     * This method must be called from the {@link EventExecutor} thread.
     *
     * 返回延时队列的最小延时时间的任务;
     */
    protected final long delayNanos(long currentTimeNanos) {
        assert inEventLoop();
        RunnableScheduledFuture<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return SCHEDULE_PURGE_INTERVAL;
        }

        return scheduledTask.delayNanos(currentTimeNanos);
    }

    /**
     * Returns the absolute point in time (relative to {@link #nanoTime()}) at which the the next
     * closest scheduled task should run.
     *
     * This method must be called from the {@link EventExecutor} thread.
     */
    protected final long deadlineNanos() {
        assert inEventLoop();
        RunnableScheduledFuture<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return nanoTime() + SCHEDULE_PURGE_INTERVAL;
        }
        return scheduledTask.deadlineNanos();
    }

    /**
     * Updates the internal timestamp that tells when a submitted task was executed most recently.
     * {@link #runAllTasks(int)} updates this timestamp automatically, and thus there's usually no need to call this
     * method.  However, if you take the tasks manually using {@link #takeTask()} or {@link #pollTask()}, you have to
     * call this method at the end of task execution loop if you execute a task for accurate quiet period checks.
     *
     * This method must be called from the {@link EventExecutor} thread.
     *
     * 更新最后执行时间;
     */
    protected final void updateLastExecutionTime() {
        assert inEventLoop();
        lastExecutionTime = nanoTime();
    }

    /**
     * Run tasks that are submitted to this {@link SingleThreadEventExecutor}.
     * The implementation depends on the fact that {@link #newTaskQueue(int)} returns a
     * {@link BlockingQueue}. If you change this by overriding {@link #newTaskQueue(int)}
     * be aware that you also need to override {@link #run()}.
     *
     * This method must be called from the {@link EventExecutor} thread.
     *
     * 执行任务;
     */
    protected void run() {
        assert inEventLoop();
        do {
            Runnable task = takeTask();
            if (task != null) {
                task.run();
                updateLastExecutionTime();
            }
        } while (!confirmShutdown());
    }

    /**
     * Do nothing, sub-classes may override.
     */
    protected void cleanup() {
        // NOOP
        assert inEventLoop();
    }

    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop) {
            // Use offer as we actually only need this to unblock the thread and if offer fails we do not care as there
            // is already something in the queue.
            // 提供一个空任务,来启动线程;
            taskQueue.offer(WAKEUP_TASK);
        }
    }

    @Override
    public final boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    /**
     * Add a {@link Runnable} which will be executed on shutdown of this instance
     *
     * 向执行器的关闭钩子中添加关闭任务;
     */
    public final void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(() -> shutdownHooks.add(task));
        }
    }

    /**
     * Remove a previous added {@link Runnable} as a shutdown hook
     */
    public final void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(() -> shutdownHooks.remove(task));
        }
    }

    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        while (!shutdownHooks.isEmpty()) {
            List<Runnable> copy = new ArrayList<>(shutdownHooks);
            shutdownHooks.clear();
            for (Runnable task: copy) {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
            updateLastExecutionTime();
        }

        return ran;
    }

    //优雅关闭,其实只是更新了执行器的状态而已;
    @Override
    public final Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        if (quietPeriod < 0) {
            throw new IllegalArgumentException("quietPeriod: " + quietPeriod + " (expected >= 0)");
        }
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        requireNonNull(unit, "unit");

        //1.已经关闭中就返回终止回调;
        if (isShuttingDown()) {
            return terminationFuture();
        }

        //2.是否是本线程执行;
        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return terminationFuture();
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                //3.更新执行器的状态为关闭中;
                newState = ST_SHUTTING_DOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                        newState = ST_SHUTTING_DOWN;
                        break;
                    default:
                        newState = oldState;
                        // ???question:???
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                //System.err.println(oldState + " " + newState + " " + this);
                break;
            }
        }
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        if (ensureThreadStarted(oldState)) {
            return terminationFuture;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }

        return terminationFuture();
    }

    @Override
    public final Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public final void shutdown() {
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTDOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        newState = ST_SHUTDOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }

        if (ensureThreadStarted(oldState)) {
            return;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }
    }

    @Override
    public final boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override
    public final boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override
    public final boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * Confirm that the shutdown if the instance should be done now!
     *
     * This method must be called from the {@link EventExecutor} thread.
     */
    protected final boolean confirmShutdown() {
        return confirmShutdown0();
    }

    //这里是真正的关闭逻辑,之前的只是修改状态而已;
    boolean confirmShutdown0() {
        assert inEventLoop();

        if (!isShuttingDown()) {
            return false;
        }

        //1.取消延时队列里的任务;
        cancelScheduledTasks();

        //2.更新关闭的开始时间;
        if (gracefulShutdownStartTime == 0) {
            gracefulShutdownStartTime = nanoTime();
        }

        //3.执行全部任务
        if (runAllTasks() || runShutdownHooks()) {
            if (isShutdown()) {
                // Executor shut down - no new tasks anymore.
                return true;
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period or
            // terminate if the quiet period is 0.
            // See https://github.com/netty/netty/issues/4241
            if (gracefulShutdownQuietPeriod == 0) {
                return true;
            }

            taskQueue.offer(WAKEUP_TASK);
            return false;
        }

        final long nanoTime = nanoTime();

        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true;
        }

        //???question:???
        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            // Check if any tasks were added to the queue every 100ms.
            // TODO: Change the behavior of takeTask() so that it returns on timeout.
            taskQueue.offer(WAKEUP_TASK);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            return false;
        }

        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        return true;
    }

    @Override
    public final boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        requireNonNull(unit, "unit");

        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        //等关闭收尾操作做完;
        threadLock.await(timeout, unit);

        return isTerminated();
    }

    //对外执行任务接口;
    @Override
    public void execute(Runnable task) {
        requireNonNull(task, "task");

        boolean inEventLoop = inEventLoop();
        //向任务队列里添加任务;
        addTask(task);
        //如果当前执行器没有启动,那么启动执行器;
        if (!inEventLoop) {
            //开启执行器;
            startThread();
            //如果关闭了
            if (isShutdown()) {
                boolean reject = false;
                try {
                    //先删除任务,再拒绝处理;
                    if (removeTask(task)) {
                        reject = true;
                    }
                } catch (UnsupportedOperationException e) {
                    // The task queue does not support removal so the best thing we can do is to just move on and
                    // hope we will be able to pick-up the task before its completely terminated.
                    // In worst case we will log on termination.
                }
                if (reject) {
                    reject();
                }
            }
        }

        if (!addTaskWakesUp && wakesUpForTask(task)) {
            wakeup(inEventLoop);
        }
    }

    // invokeAny意思是只执行成功其中一个就可以,返回对应的返回;
    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks, timeout, unit);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks, timeout, unit);
    }

    private void throwIfInEventLoop(String method) {
        if (inEventLoop()) {
            throw new RejectedExecutionException("Calling " + method + " from within the EventLoop is not allowed");
        }
    }

    /**
     * Returns the {@link ThreadProperties} of the {@link Thread} that powers the {@link SingleThreadEventExecutor}.
     * If the {@link SingleThreadEventExecutor} is not started yet, this operation will start it and block until
     * it is fully started.
     *
     * ThreadProperties可以获取到执行器线程的各个属性;
     */
    public final ThreadProperties threadProperties() {
        ThreadProperties threadProperties = this.threadProperties;
        if (threadProperties == null) {
            Thread thread = this.thread;
            if (thread == null) {
                assert !inEventLoop();
                submit(NOOP_TASK).syncUninterruptibly();
                thread = this.thread;
                assert thread != null;
            }

            threadProperties = new DefaultThreadProperties(thread);
            if (!PROPERTIES_UPDATER.compareAndSet(this, null, threadProperties)) {
                threadProperties = this.threadProperties;
            }
        }

        return threadProperties;
    }

    /**
     * Returns {@code true} if {@link #wakeup(boolean)} should be called for this {@link Runnable}, {@code false}
     * otherwise.
     */
    protected boolean wakesUpForTask(@SuppressWarnings("unused") Runnable task) {
        return true;
    }

    protected static void reject() {
        throw new RejectedExecutionException("event executor terminated");
    }

    // ScheduledExecutorService implementation
    //计划清除时间,十亿纳秒等于1s???question为什么要多这1s???,答:这个是配合处理io事件的,没有延时任务那么selector也只能最大超时等待1s时间;
    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    private void startThread() {
        if (state == ST_NOT_STARTED) {
            if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
                boolean success = false;
                try {
                    doStartThread();
                    success = true;
                } finally {
                    if (!success) {
                        STATE_UPDATER.compareAndSet(this, ST_STARTED, ST_NOT_STARTED);
                    }
                }
            }
        }
    }

    private boolean ensureThreadStarted(int oldState) {
        if (oldState == ST_NOT_STARTED) {
            try {
                doStartThread();
            } catch (Throwable cause) {
                STATE_UPDATER.set(this, ST_TERMINATED);
                terminationFuture.tryFailure(cause);

                if (!(cause instanceof Exception)) {
                    // Also rethrow as it may be an OOME for example
                    PlatformDependent.throwException(cause);
                }
                return true;
            }
        }
        return false;
    }

    // 启动线程;
    private void doStartThread() {
        assert thread == null;
        executor.execute(() -> {
            //保存线程;
            thread = Thread.currentThread();
            if (interrupted) {
                thread.interrupt();
            }

            boolean success = false;
            // 更新最后执行时间;
            updateLastExecutionTime();
            try {
                // 执行器真正开始工作;
                SingleThreadEventExecutor.this.run();
                success = true;
            } catch (Throwable t) {
                logger.warn("Unexpected exception from an event executor: ", t);
            } finally {
                // ----------------以下都是执行器run结束的收尾工作-----------------------------;
                // 1.run结束了,更新状态为关闭状态;
                for (;;) {
                    int oldState = state;
                    if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(
                            SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN)) {
                        break;
                    }
                }

                // 2.检查confirmShutdown是否执行了;
                // Check if confirmShutdown() was called at the end of the loop.
                // gracefulShutdownStartTime == 0说明confirmShutdown没有被调用;
                if (success && gracefulShutdownStartTime == 0) {
                    if (logger.isErrorEnabled()) {
                        logger.error("Buggy " + EventExecutor.class.getSimpleName() + " implementation; " +
                                SingleThreadEventExecutor.class.getSimpleName() + ".confirmShutdown() must " +
                                "be called before run() implementation terminates.");
                    }
                }

                try {
                    // Run all remaining tasks and shutdown hooks. At this point the event loop
                    // is in ST_SHUTTING_DOWN state still accepting tasks which is needed for
                    // graceful shutdown with quietPeriod.
                    // 3.确保confirmShutdown得执行,完成关闭的收尾工作,收尾任务和关闭的钩子;
                    for (;;) {
                        if (confirmShutdown()) {
                            break;
                        }
                    }

                    // Now we want to make sure no more tasks can be added from this point. This is
                    // achieved by switching the state. Any new tasks beyond this point will be rejected.
                    // 4.将执行器状态更新为已关闭;
                    for (;;) {
                        int oldState = state;
                        if (oldState >= ST_SHUTDOWN || STATE_UPDATER.compareAndSet(
                                SingleThreadEventExecutor.this, oldState, ST_SHUTDOWN)) {
                            break;
                        }
                    }

                    // We have the final set of tasks in the queue now, no more can be added, run all remaining.
                    // No need to loop here, this is the final pass.
                    // 5.最终再确保下;
                    confirmShutdown();
                } finally {
                    try {
                        // 6.执行相应的打扫工作;
                        cleanup();
                    } finally {
                        // Lets remove all FastThreadLocals for the Thread as we are about to terminate and notify
                        // the future. The user may block on the future and once it unblocks the JVM may terminate
                        // and start unloading classes.
                        // See https://github.com/netty/netty/issues/6596.
                        // 7.清除该执行器线程在threadLocal的存储;
                        FastThreadLocal.removeAll();

                        // 8.更新执行器线程状态为终止状态;
                        STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                        // 9.结束阻塞;
                        threadLock.countDown();
                        // 10.确认任务队列是否是空的了;
                        int numUserTasks = drainTasks();
                        if (numUserTasks > 0 && logger.isWarnEnabled()) {
                            logger.warn("An event executor terminated with " +
                                    "non-empty task queue (" + numUserTasks + ')');
                        }
                        // 11.设置终止回调;
                        terminationFuture.setSuccess(null);
                    }
                }
            }
        });
    }

    // 统计任务队列里的有效任务的数量;
    final int drainTasks() {
        int numTasks = 0;
        for (;;) {
            Runnable runnable = taskQueue.poll();
            if (runnable == null) {
                break;
            }
            // WAKEUP_TASK should be just discarded as these are added internally.
            // The important bit is that we not have any user tasks left.
            if (WAKEUP_TASK != runnable) {
                numTasks++;
            }
        }
        return numTasks;
    }

    // 线程参数的包装;
    private static final class DefaultThreadProperties implements ThreadProperties {
        private final Thread t;

        DefaultThreadProperties(Thread t) {
            this.t = t;
        }

        @Override
        public State state() {
            return t.getState();
        }

        @Override
        public int priority() {
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
            return t.isDaemon();
        }

        @Override
        public String name() {
            return t.getName();
        }

        @Override
        public long id() {
            return t.getId();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return t.getStackTrace();
        }

        @Override
        public boolean isAlive() {
            return t.isAlive();
        }
    }
}
