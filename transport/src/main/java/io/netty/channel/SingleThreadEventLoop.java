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
package io.netty.channel;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.util.Objects.requireNonNull;

import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;

import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * {@link EventLoop} that execute all its submitted tasks in a single thread and uses an {@link IoHandler} for
 * IO processing.
 *
 * eventLoop反应堆;
 */
public class  SingleThreadEventLoop extends SingleThreadEventExecutor implements EventLoop {

    // 默认最大任务队列大小;
    protected static final int DEFAULT_MAX_PENDING_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventLoop.maxPendingTasks", Integer.MAX_VALUE));

    // TODO: Is this a sensible default ?
    // 默认每次处理任务的最大数量;
    protected static final int DEFAULT_MAX_TASKS_PER_RUN = Math.max(1,
            SystemPropertyUtil.getInt("io.netty.eventLoop.maxTaskPerRun", 1024 * 4));

    private final IoExecutionContext context = new IoExecutionContext() {
        @Override
        public boolean canBlock() {
            assert inEventLoop();
            return !SingleThreadEventLoop.this.hasTasks() && !SingleThreadEventLoop.this.hasScheduledTasks();
        }

        @Override
        public long delayNanos(long currentTimeNanos) {
            assert inEventLoop();
            return SingleThreadEventLoop.this.delayNanos(currentTimeNanos);
        }

        @Override
        public long deadlineNanos() {
            assert inEventLoop();
            return SingleThreadEventLoop.this.deadlineNanos();
        }
    };

    private final Unsafe unsafe = new Unsafe() {
        @Override
        public void register(Channel channel) throws Exception {
            SingleThreadEventLoop.this.register(channel);
        }

        @Override
        public void deregister(Channel channel) throws Exception {
            SingleThreadEventLoop.this.deregister(channel);
        }
    };

    // 用来处理io事件的;
    private final IoHandler ioHandler;
    // 每次处理任务的最大数量;
    private final int maxTasksPerRun;

    /**
     * Create a new instance
     *
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param ioHandler         the {@link IoHandler} to use.
     */
    public SingleThreadEventLoop(ThreadFactory threadFactory, IoHandler ioHandler) {
        this(threadFactory, ioHandler, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    /**
     * Create a new instance
     *
     * @param executor          the {@link Executor} which will be used to run this {@link EventLoop}.
     * @param ioHandler         the {@link IoHandler} to use.
     */
    public SingleThreadEventLoop(Executor executor, IoHandler ioHandler) {
        this(executor, ioHandler, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    /**
     * Create a new instance
     *
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param ioHandler         the {@link IoHandler} to use.
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    public SingleThreadEventLoop(ThreadFactory threadFactory,
                                 IoHandler ioHandler, int maxPendingTasks,
                                 RejectedExecutionHandler rejectedHandler) {
        this(threadFactory, ioHandler, maxPendingTasks, rejectedHandler, DEFAULT_MAX_TASKS_PER_RUN);
    }

    /**
     * Create a new instance
     *
     * @param executor          the {@link Executor} which will be used to run this {@link EventLoop}.
     * @param ioHandler         the {@link IoHandler} to use.
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    public SingleThreadEventLoop(Executor executor,
                                 IoHandler ioHandler, int maxPendingTasks,
                                 RejectedExecutionHandler rejectedHandler) {
        this(executor, ioHandler, maxPendingTasks, rejectedHandler, DEFAULT_MAX_TASKS_PER_RUN);
    }

    /**
     * Create a new instance
     *
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param ioHandler         the {@link IoHandler} to use.
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     * @param maxTasksPerRun    the maximum number of tasks per {@link EventLoop} run that will be processed
     *                          before trying to handle IO again.
     */
    public SingleThreadEventLoop(ThreadFactory threadFactory,
                                 IoHandler ioHandler, int maxPendingTasks,
                                 RejectedExecutionHandler rejectedHandler, int maxTasksPerRun) {
        super(threadFactory, maxPendingTasks, rejectedHandler);
        this.ioHandler = requireNonNull(ioHandler, "ioHandler");
        this.maxTasksPerRun = checkPositive(maxTasksPerRun, "maxTasksPerRun");
    }

    /**
     * Create a new instance
     *
     * @param executor          the {@link Executor} which will be used to run this {@link EventLoop}.
     * @param ioHandler         the {@link IoHandler} to use.
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     * @param maxTasksPerRun    the maximum number of tasks per {@link EventLoop} run that will be processed
     *                          before trying to handle IO again.
     */
    public SingleThreadEventLoop(Executor executor,
                                 IoHandler ioHandler, int maxPendingTasks,
                                 RejectedExecutionHandler rejectedHandler, int maxTasksPerRun) {
        super(executor, maxPendingTasks, rejectedHandler);
        this.ioHandler = requireNonNull(ioHandler, "ioHandler");
        this.maxTasksPerRun = checkPositive(maxTasksPerRun, "maxTasksPerRun");
    }

    // 创建任务队列,依赖于平台的;
    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.newMpscQueue()
                : PlatformDependent.newMpscQueue(maxPendingTasks);
    }

    // next返回自己;
    @Override
    public final EventLoop next() {
        return this;
    }

    @Override
    protected final boolean wakesUpForTask(Runnable task) {
        return !(task instanceof NonWakeupRunnable);
    }

    /**
     * Marker interface for {@link Runnable} that will not trigger an {@link #wakeup(boolean)} in all cases.
     *
     * {@link Runnable}的标记接口，在所有情况下都不会触发{@link#wakeup（布尔值）}。
     */
    interface NonWakeupRunnable extends Runnable { }

    @Override
    public final Unsafe unsafe() {
        return unsafe;
    }

    // Methods that a user can override to easily add instrumentation and other things.
    // 执行逻辑;
    @Override
    protected void run() {
        assert inEventLoop();
        do {
            // 1.执行io事件;
            runIo();
            // 2.判断执行器的状态是否大于关闭中;
            if (isShuttingDown()) {
                // 3.是的话,处理io事件的handler进行准备销毁;
                ioHandler.prepareToDestroy();
            }
            // 4.执行任务事件;
            runAllTasks(maxTasksPerRun);
            // 5.确保关闭检查;
        } while (!confirmShutdown());
    }

    /**
     * Called when IO will be processed for all the {@link Channel}s on this {@link SingleThreadEventLoop}.
     * This method returns the number of {@link Channel}s for which IO was processed.
     *
     * This method must be called from the {@link EventLoop} thread.
     *
     * 执行io事件, 返回执行了多少个io事件;
     */
    protected int runIo() {
        assert inEventLoop();
        return ioHandler.run(context);
    }

    /**
     * Called once a {@link Channel} should be registered on this {@link SingleThreadEventLoop}.
     *
     * This method must be called from the {@link EventLoop} thread.
     *
     * 进行channel的注册,这里netty5做了较大的调整,将selector等处理io事件的都放到了iohandler里,简化了eventLoop;
     */
    protected void register(Channel channel) throws Exception {
        assert inEventLoop();
        ioHandler.register(channel);
    }

    /**
     * Called once a {@link Channel} should be deregistered from this {@link SingleThreadEventLoop}.
     *
     * 取消channel注册;
     */
    protected void deregister(Channel channel) throws Exception {
        assert inEventLoop();
        ioHandler.deregister(channel);
    }

    // wakeup的操作;
    @Override
    protected final void wakeup(boolean inEventLoop) {
        ioHandler.wakeup(inEventLoop);
    }

    // 相关清理,详看doStartThread方法;
    @Override
    protected final void cleanup() {
        assert inEventLoop();
        ioHandler.destroy();
    }
}
