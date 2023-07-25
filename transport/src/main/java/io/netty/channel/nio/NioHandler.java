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
package io.netty.channel.nio;

import static java.util.Objects.requireNonNull;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.EventLoopException;
import io.netty.channel.IoExecutionContext;
import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SelectStrategyFactory;
import io.netty.util.IntSupplier;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link IoHandler} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 * Nio的io事件处理handler;
 */
public final class NioHandler implements IoHandler {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioHandler.class);

    //清理周期;
    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.
    //netty优化原生selector否可用;
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);
    //
    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    //
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;
    //
    private final IntSupplier selectNowSupplier = this::selectNow;

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    // 这个bug是因为bugLevel变量不是线程安全的,当多个线程执行时,有可能会抛空指针异常,具体如上;
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                    //放入一个不为空的值,就可以保证上面的异常问题;
                    System.setProperty(key, "");
                    return null;
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     *
     * 多路复用器,epoll的逻辑;
     */
    private Selector selector;
    // netty进行改造过的selector,执行效率更好;
    private Selector unwrappedSelector;
    // selectkeys;
    private SelectedSelectionKeySet selectedKeys;
    // selector的工厂类;
    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     *
     * 为了防止selector一直阻塞,或者阻塞事件特别长,通过这个参数来解除阻塞,尽快返回好执行别的事;
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();
    // 策略
    private final SelectStrategy selectStrategy;
    // 取消key的数量;
    private int cancelledKeys;
    // 是否需要重新select一遍;
    private boolean needsToSelectAgain;

    private NioHandler() {
        this(SelectorProvider.provider(), DefaultSelectStrategyFactory.INSTANCE.newSelectStrategy());
    }

    private NioHandler(SelectorProvider selectorProvider, SelectStrategy strategy) {
        provider = selectorProvider;
        final SelectorTuple selectorTuple = openSelector();
        selector = selectorTuple.selector;
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }

    /**
     * Returns a new {@link IoHandlerFactory} that creates {@link NioHandler} instances.
     *
     * 返回自己的构造方法
     */
    public static IoHandlerFactory newFactory() {
        return NioHandler::new;
    }

    /**
     * Returns a new {@link IoHandlerFactory} that creates {@link NioHandler} instances.
     */
    public static IoHandlerFactory newFactory(final SelectorProvider selectorProvider,
                                              final SelectStrategyFactory selectStrategyFactory) {
        requireNonNull(selectorProvider, "selectorProvider");
        requireNonNull(selectStrategyFactory, "selectStrategyFactory");
        return () -> new NioHandler(selectorProvider, selectStrategyFactory.newSelectStrategy());
    }

    // slector元组,存着两个selector;
    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    // 创建selector;
    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            // 1.通过工厂类创建一个selector;
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        // 2.判断是否进行selector的优化;
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        Object maybeSelectorImplClass = AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
            try {
                return Class.forName(
                        "sun.nio.ch.SelectorImpl",
                        false,
                        PlatformDependent.getSystemClassLoader());
            } catch (Throwable cause) {
                return cause;
            }
        });

        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
            try {
                Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                    // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                    // This allows us to also do this in Java9+ without any extra flags.
                    long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                    long publicSelectedKeysFieldOffset =
                            PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                    if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                        PlatformDependent.putObject(
                                unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                        PlatformDependent.putObject(
                                unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                        return null;
                    }
                    // We could not retrieve the offset, lets try reflection as last-resort.
                }

                Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                if (cause != null) {
                    return cause;
                }
                cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                if (cause != null) {
                    return cause;
                }

                selectedKeysField.set(unwrappedSelector, selectedKeySet);
                publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                return null;
            } catch (NoSuchFieldException | IllegalAccessException e) {
                return e;
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioHandler} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     *
     * channel的事件注册;
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        requireNonNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        requireNonNull(task, "task");

        register0(ch, interestOps, task);
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     *
     * 没有任务时,如果select没有设置超时时间,那么会导致无限调用epoll的wait导致cpu迅速上升;
     */
    void rebuildSelector() {
        // 1.保存旧的selector;
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            // 2.重新创建新的selector;
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        // 3.将旧的channel重新注册到新的selector;
        int nChannels = 0;
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                // 4.如果这个key是无效或者这个key就是这个新建的注册的(新的和旧的可能是同一个),那么就不再注册了;
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                // 5.重新进行注册;
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                // 6.更新channel里存储的key;
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        // 7.将新的selector赋值给selector;
        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            // 8.将旧的selector关闭;
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    private static AbstractNioChannel cast(Channel channel) {
        if (channel instanceof AbstractNioChannel) {
            return (AbstractNioChannel) channel;
        }
        throw new IllegalArgumentException("Channel of type " + StringUtil.simpleClassName(channel) + " not supported");
    }

    @Override
    public void register(Channel channel) throws Exception {
        // 1.强转为netty的channel;
        AbstractNioChannel nioChannel = cast(channel);
        boolean selected = false;
        for (;;) {
            try {
                // 2.进行channel向selector的注册;
                nioChannel.selectionKey = nioChannel.javaChannel().register(unwrappedSelector(), 0, nioChannel);
                return;
            } catch (CancelledKeyException e) {
                if (!selected) {
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                    // 3.注册失败可能是因为被取消的key还缓存着,没有被删除掉因为一直没有执行select操作;
                    selectNow();
                    selected = true;
                } else {
                    // We forced a select operation on the selector before but the SelectionKey is still cached
                    // for whatever reason. JDK bug ?
                    throw e;
                }
            }
        }
    }

    //取消注册;
    @Override
    public void deregister(Channel channel) {
        AbstractNioChannel nioChannel = cast(channel);
        cancel(nioChannel.selectionKey());
    }

    // io事件处理;
    @Override
    public int run(IoExecutionContext runner) {
        int handled = 0;
        try {
            try {
                switch (selectStrategy.calculateStrategy(selectNowSupplier, !runner.canBlock())) {
                    case SelectStrategy.CONTINUE:
                        return 0;

                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT:
                        select(runner, wakenUp.getAndSet(false));

                        // 'wakenUp.compareAndSet(false, true)' is always evaluated
                        // before calling 'selector.wakeup()' to reduce the wake-up
                        // overhead. (Selector.wakeup() is an expensive operation.)
                        //
                        // However, there is a race condition in this approach.
                        // The race condition is triggered when 'wakenUp' is set to
                        // true too early.
                        //
                        // 'wakenUp' is set to true too early if:
                        // 1) Selector is waken up between 'wakenUp.set(false)' and
                        //    'selector.select(...)'. (BAD)
                        // 2) Selector is waken up between 'selector.select(...)' and
                        //    'if (wakenUp.get()) { ... }'. (OK)
                        //
                        // In the first case, 'wakenUp' is set to true and the
                        // following 'selector.select(...)' will wake up immediately.
                        // Until 'wakenUp' is set to false again in the next round,
                        // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                        // any attempt to wake up the Selector will fail, too, causing
                        // the following 'selector.select(...)' call to block
                        // unnecessarily.
                        //
                        // To fix this problem, we wake up the selector again if wakenUp
                        // is true immediately after selector.select(...).
                        // It is inefficient in that it wakes up the selector for both
                        // the first case (BAD - wake-up required) and the second case
                        // (OK - no wake-up required).

                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                        // fall through
                    default:
                }
            } catch (IOException e) {
                // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                // the selector and retry. https://github.com/netty/netty/issues/8566
                rebuildSelector();
                handleLoopException(e);
                return 0;
            }

            cancelledKeys = 0;
            needsToSelectAgain = false;
            handled = processSelectedKeys();
        } catch (Throwable t) {
            handleLoopException(t);
        }
        return handled;
    }

    // 处理异常,并释放一秒cpu时间,防止cpu消耗过多;
    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    // 处理key事件;
    private int processSelectedKeys() {
        // selectedKeys != null表示进行了替换原生keys的操作;
        if (selectedKeys != null) {
            return processSelectedKeysOptimized();
        } else {
            return processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    // 销毁操作,关闭selector;
    @Override
    public void destroy() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    // 取消key,同时更新取消key的个数,如果取消的key个数到达需要清理的数量时,进行清理并且将needsToSelectAgain置为true
    // 等待select真正执行时key真正被清除掉;
    private void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    // 处理key;
    private int processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        // 检查keys是否是空的,
        if (selectedKeys.isEmpty()) {
            return 0;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        int handled = 0;
        for (;;) {
            final SelectionKey k = i.next();
            i.remove();

            processSelectedKey(k);
            ++handled;

            if (!i.hasNext()) {
                break;
            }

            // 可能有取消的key或者无效的key,需要重新梳理下;
            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
        return handled;
    }

    // 优化的处理key;
    private int processSelectedKeysOptimized() {
        int handled = 0;
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            // 释放掉当channel关闭的时候可以被gc掉;
            selectedKeys.keys[i] = null;

            processSelectedKey(k);
            ++handled;

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                // 交给 gc;
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
        return handled;
    }

    // 处理selectKey对应事件;
    private void processSelectedKey(SelectionKey k) {
        final Object a = k.attachment();

        if (a instanceof AbstractNioChannel) {
            processSelectedKey(k, (AbstractNioChannel) a);
        } else {
            @SuppressWarnings("unchecked")
            NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
            processSelectedKey(k, task);
        }
    }

    // 处理;
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {

            // close the channel if the key is not valid anymore
            // 如果key不可用那么就关掉channel的回调;
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            // 1.获取已准备的事件;
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            // 在处理读写事件之前,也就是处理connect事件时,要执行finishConnect确保连接完成,防止读写时出现NotYetConnectedException;
            // 2.如果是connect事件;
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                // 3.取消connect事件;
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            // 4.先处理写事件,为了释放内存;
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                // 调用forceFlush，一旦没有东西可以写，它也会清除OP_WRITE;
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            // 还要检查readOps是否为0，以解决可能导致自旋循环的JDK错误;
            // 5.处理写和accept事件;
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    // task的;
    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    // 准备销毁;
    @Override
    public void prepareToDestroy() {
        // 1.再执行一次select;
        selectAgain();
        // 2.获取就绪的keys;
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                // 3.收集就绪的channel;
                channels.add((AbstractNioChannel) a);
            } else {
                // 4.取消key;
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            // 5.关闭channel;
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    // ???
    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    // 唤醒阻塞的selector;
    @Override
    public void wakeup(boolean inEventLoop) {
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    // 返回未包装的selector;
    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    // 执行select立即返回;
    private int selectNow() throws IOException {
        try {
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }

    // 执行select操作获取就绪keys;
    private void select(IoExecutionContext runner, boolean oldWakenUp) throws IOException {
        Selector selector = this.selector;
        try {
            int selectCnt = 0;
            long currentTimeNanos = System.nanoTime();
            // 1.计算延时任务的延时时间;
            long selectDeadLineNanos = currentTimeNanos + runner.delayNanos(currentTimeNanos);

            for (;;) {
                // 2.计算select的超时时间,延时时间+500000L纳秒然后转化为毫秒,多了0.5毫秒的预算;
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;

                // 3.判断超时时间是否小于0,也就是延时任务到期了;
                if (timeoutMillis <= 0) {
                    // 4.延时任务到期了,还没有执行select操作过,那么执行一次,且立即返回就绪keys;
                    if (selectCnt == 0) {
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                // 5.如果在wakenUp值为true时提交了任务，则该任务没有机会调用
                // 选择器#唤醒。所以在执行select操作之前，我们需要再次检查任务队列。
                // 如果不这样做，任务可能会挂起，直到选择操作超时。
                // 如果管道中存在IdleStateHandler，则它可能会挂起，直到空闲超时。
                if (!runner.canBlock() && wakenUp.compareAndSet(false, true)) {
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                // 6.执行select带有超时时间的;
                int selectedKeys = selector.select(timeoutMillis);
                // 7.select执行次数加一;
                selectCnt ++;

                // 8.如果就绪key的数量不为0, 用户唤醒了selector,任务队列有任务来了或者延时队列任务到时间了;
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || !runner.canBlock()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    // 8.如果线程已经时中断状态了,那么还原为非中断状态,因为好呢有可能是用户或者客户端失误操作,同时取消了此次的select操作;
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioHandler.shutdownGracefully() to shutdown the NioHandler.");
                    }
                    selectCnt = 1;
                    break;
                }

                // 9.如果本次执行超过一毫秒,说明不需要重建selector是正常的所以selectCnt置为1;
                long time = System.nanoTime();
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;

                // 10.如果select连续执行的次数大于selector重新创建的阈值,那么重新创建selector;
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // The code exists in an extra method to ensure the method is not too big to inline as this
                    // branch is not very likely to get hit very frequently.
                    // 代码存在于一个额外的方法中，以确保该方法不会太大而无法内联，因为此分支不太可能经常被命中。
                    // 方法不要太大,否则很容易不会被即时编译器优化,比如内联;
                    selector = selectRebuildSelector(selectCnt);
                    selectCnt = 1;
                    break;
                }

                // 11.更新当前时间;
                currentTimeNanos = time;
            }

            // 12.如果select执行次数大于最小执行次数返回的值,那么需要log下;
            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    private Selector selectRebuildSelector(int selectCnt) throws IOException {
        // The selector returned prematurely many times in a row.
        // Rebuild the selector to work around the problem.
        // selector执行select次数太多,重新生成selector来解决此问题;
        logger.warn(
                "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                selectCnt, selector);

        rebuildSelector();
        Selector selector = this.selector;

        // Select again to populate selectedKeys.
        // 再次执行重新填充keys;
        selector.selectNow();
        return selector;
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
