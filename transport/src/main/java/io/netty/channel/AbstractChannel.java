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

import static java.util.Objects.requireNonNull;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.ChannelOutputShutdownEvent;
import io.netty.channel.socket.ChannelOutputShutdownException;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A skeletal {@link Channel} implementation.
 *
 * 各个方法的说明在channel里,abschannel的实现都在pipeline里;
 */
public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannel.class);

    /**
     * 一般情况下为null,如果是客户端channel那么可能放的是服务端的channel;
     */
    private final Channel parent;
    // channel的唯一标识;
    private final ChannelId id;
    // unsafe;
    private final Unsafe unsafe;
    // pipeline;
    private final ChannelPipeline pipeline;
    // ???question:???
    private final ChannelFuture succeedFuture;
    //
    private final VoidChannelPromise unsafeVoidPromise = new VoidChannelPromise(this, false);
    // close的回调,只在执行close方法进行回调;
    private final CloseFuture closeFuture;
    // 本地地址;
    private volatile SocketAddress localAddress;
    // 远程地址;
    private volatile SocketAddress remoteAddress;
    // eventLoop;
    private final EventLoop eventLoop;
    // 是否已经注册到eventloop;
    private volatile boolean registered;
    // 是否关闭,或者关闭中;
    private boolean closeInitiated;
    // 关闭的原因;
    private Throwable initialCloseCause;

    /** Cache for the string representation of this channel */
    // channel是否可用;
    private boolean strValActive;
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param parent
     *        the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent, EventLoop eventLoop) {
        this.parent = parent;
        this.eventLoop = validateEventLoop(eventLoop);
        closeFuture = new CloseFuture(this, eventLoop);
        succeedFuture = new SucceededChannelFuture(this, eventLoop);
        id = newId();
        unsafe = newUnsafe();
        pipeline = newChannelPipeline();
    }

    /**
     * Creates a new instance.
     *
     * @param parent
     *        the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent, EventLoop eventLoop, ChannelId id) {
        this.parent = parent;
        this.eventLoop = validateEventLoop(eventLoop);
        closeFuture = new CloseFuture(this, eventLoop);
        succeedFuture = new SucceededChannelFuture(this, eventLoop);
        this.id = id;
        unsafe = newUnsafe();
        pipeline = newChannelPipeline();
    }

    private EventLoop validateEventLoop(EventLoop eventLoop) {
        return requireNonNull(eventLoop, "eventLoop");
    }

    @Override
    public final ChannelId id() {
        return id;
    }

    /**
     * Returns a new {@link DefaultChannelId} instance. Subclasses may override this method to assign custom
     * {@link ChannelId}s to {@link Channel}s that use the {@link AbstractChannel#AbstractChannel(Channel, EventLoop)}
     * constructor.
     */
    protected ChannelId newId() {
        return DefaultChannelId.newInstance();
    }

    /**
     * Returns a new {@link ChannelPipeline} instance.
     */
    protected ChannelPipeline newChannelPipeline() {
        return new DefaultChannelPipeline(this);
    }

    @Override
    public boolean isWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        return buf != null && buf.isWritable();
    }

    @Override
    public long bytesBeforeUnwritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeUnwritable() : 0;
    }

    @Override
    public long bytesBeforeWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeWritable() : Long.MAX_VALUE;
    }

    @Override
    public Channel parent() {
        return parent;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    @Override
    public EventLoop eventLoop() {
        return eventLoop;
    }

    @Override
    public SocketAddress localAddress() {
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            try {
                this.localAddress = localAddress = unsafe().localAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return localAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateLocalAddress() {
        localAddress = null;
    }

    @Override
    public SocketAddress remoteAddress() {
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            try {
                this.remoteAddress = remoteAddress = unsafe().remoteAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return remoteAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateRemoteAddress() {
        remoteAddress = null;
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return pipeline.bind(localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return pipeline.disconnect();
    }

    @Override
    public ChannelFuture close() {
        return pipeline.close();
    }

    @Override
    public ChannelFuture register() {
        return pipeline.register();
    }

    @Override
    public ChannelFuture deregister() {
        return pipeline.deregister();
    }

    @Override
    public Channel flush() {
        pipeline.flush();
        return this;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return pipeline.close(promise);
    }

    /**
     * 通过pipeline来完成注册;
     *
     * {
     *    tip: 5.0版本做了调整注册任务交给pipeline;
     * }
     */
    @Override
    public ChannelFuture register(ChannelPromise promise) {
        return pipeline.register(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return pipeline.deregister(promise);
    }

    @Override
    public Channel read() {
        pipeline.read();
        return this;
    }

    @Override
    public ChannelFuture write(Object msg) {
        return pipeline.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return pipeline.write(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return pipeline.writeAndFlush(msg);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return pipeline.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelPromise newPromise() {
        return new DefaultChannelPromise(this, eventLoop);
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return new DefaultChannelProgressivePromise(this, eventLoop);
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return succeedFuture;
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return new FailedChannelFuture(this, eventLoop, cause);
    }

    @Override
    public ChannelFuture closeFuture() {
        return closeFuture;
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }

    /**
     * Create a new {@link AbstractUnsafe} instance which will be used for the life-time of the {@link Channel}
     */
    protected abstract AbstractUnsafe newUnsafe();

    /**
     * Returns the ID of this channel.
     */
    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    /**
     * Returns {@code true} if and only if the specified object is identical
     * with this channel (i.e: {@code this == o}).
     */
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }

        return id().compareTo(o.id());
    }

    /**
     * Returns the {@link String} representation of this channel.  The returned
     * string contains the {@linkplain #hashCode() ID}, {@linkplain #localAddress() local address},
     * and {@linkplain #remoteAddress() remote address} of this channel for
     * easier identification.
     */
    @Override
    public String toString() {
        boolean active = isActive();
        if (strValActive == active && strVal != null) {
            return strVal;
        }

        SocketAddress remoteAddr = remoteAddress();
        SocketAddress localAddr = localAddress();
        if (remoteAddr != null) {
            StringBuilder buf = new StringBuilder(96)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(active? " - " : " ! ")
                .append("R:")
                .append(remoteAddr)
                .append(']');
            strVal = buf.toString();
        } else if (localAddr != null) {
            StringBuilder buf = new StringBuilder(64)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(']');
            strVal = buf.toString();
        } else {
            StringBuilder buf = new StringBuilder(16)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(']');
            strVal = buf.toString();
        }

        strValActive = active;
        return strVal;
    }

    @Override
    public final ChannelPromise voidPromise() {
        return pipeline.voidPromise();
    }

    // 如果channel设置自动读取,那么当连接成功或者连接完成就进行读取信息操作,无需用户发起读取信息;
    protected final void readIfIsAutoRead() {
        if (config().isAutoRead()) {
            read();
        }
    }

    /**
     * {@link Unsafe} implementation which sub-classes must extend and use.
     */
    protected abstract class AbstractUnsafe implements Unsafe {

        // 出栈缓冲区;
        private volatile ChannelOutboundBuffer outboundBuffer = new ChannelOutboundBuffer(AbstractChannel.this);
        // 接收信息时进行缓冲空间申请的handle;
        private RecvByteBufAllocator.Handle recvHandle;
        // 信息大小估计器;
        private MessageSizeEstimator.Handle estimatorHandler;
        // 标志当前channel处理刷新缓冲中,所以可能它注册到eventLoop无法腾出时间干别的事;
        private boolean inFlush0;
        /** true if the channel has never been registered, false otherwise */
        // 如果为true那么说明channel还没注册过任何eventLoop;
        private boolean neverRegistered = true;
        // debug 当前线程是否是eventLoop自己线程,或者event线程是否启动;
        private void assertEventLoop() {
            assert eventLoop.inEventLoop();
        }

        @Override
        public RecvByteBufAllocator.Handle recvBufAllocHandle() {
            if (recvHandle == null) {
                recvHandle = config().getRecvByteBufAllocator().newHandle();
            }
            return recvHandle;
        }

        @Override
        public final ChannelOutboundBuffer outboundBuffer() {
            return outboundBuffer;
        }

        @Override
        public final SocketAddress localAddress() {
            return localAddress0();
        }

        @Override
        public final SocketAddress remoteAddress() {
            return remoteAddress0();
        }

        // channel注册;
        @Override
        public final void register(final ChannelPromise promise) {
            assertEventLoop();

            // 1.判断channel是否已经注册了,注册了就不再允许注册;
            if (isRegistered()) {
                promise.setFailure(new IllegalStateException("registered to an event loop already"));
                return;
            }

            try {
                // check if the channel is still open as it could be closed in the mean time when the register
                // call was outside of the eventLoop
                // 2.检查channel是否开启,因为在注册的时候可能会被别处关闭,导致注册一个不可用的channel;
                if (!promise.setUncancellable() || !ensureOpen(promise)) {
                    return;
                }

                boolean firstRegistration = neverRegistered;
                // 3.进行注册;
                doRegister();
                // 4.更新注册后的一些状态;
                neverRegistered = false;
                registered = true;

                // 5.更新回调,也就是调用设置的回调;
                safeSetSuccess(promise);
                // 6.执行pipe里的重写这个入站操作的handler;
                pipeline.fireChannelRegistered();
                // Only fire a channelActive if the channel has never been registered. This prevents firing
                // multiple channel actives if the channel is deregistered and re-registered.
                // 7.如果neverRegistered为true,也就是channel之前没有注册过,那么就发起channelActive,如果之前注册过那么就发起了;
                if (isActive()) {
                    if (firstRegistration) {
                        pipeline.fireChannelActive();
                    }
                    // 8.如果channel设置了自动读取,那么进行读取操作;
                    readIfIsAutoRead();
                }
            } catch (Throwable t) {
                // Close the channel directly to avoid FD leak.
                // 9.如果注册失败,那么就强制关闭channel,不管这个channel有没有消息要处理;
                closeForcibly();
                // 10.进行以下的回调处理;
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        // 绑定;
        @Override
        public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
            assertEventLoop();

            // 1.channel是否可用;
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            // 2.这个地方是因为:SO_BROADCAST为true表示接受udp的广播,但是linux有个问题,如果不是超级管理员root
            // 无法收到广播,除非channel绑定的是通配的本地地址;
            // See: https://github.com/netty/netty/issues/576
            if (Boolean.TRUE.equals(config().getOption(ChannelOption.SO_BROADCAST)) &&
                localAddress instanceof InetSocketAddress &&
                !((InetSocketAddress) localAddress).getAddress().isAnyLocalAddress() &&
                !PlatformDependent.isWindows() && !PlatformDependent.maybeSuperUser()) {
                // Warn a user about the fact that a non-root user can't receive a
                // broadcast packet on *nix if the socket is bound on non-wildcard address.
                logger.warn(
                        "A non-root user can't receive a broadcast packet if the socket " +
                        "is not bound to a wildcard address; binding to a non-wildcard " +
                        "address (" + localAddress + ") anyway as requested.");
            }

            boolean wasActive = isActive();
            try {
                // 3.地址绑定;
                doBind(localAddress);
            } catch (Throwable t) {
                // 4.绑定失败进行相应回调处理,如果channel不可用就关掉;
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            // 4.如果wasActive为true那么说明之前已经绑定过了,如果之前没有绑定过且这次绑定成功那么就发起入站操作的fireChannelActive;
            if (!wasActive && isActive()) {
                invokeLater(() -> {
                    pipeline.fireChannelActive();
                    readIfIsAutoRead();
                });
            }

            // 5.进行成功回调处理;
            safeSetSuccess(promise);
        }

        // 断开连接;
        @Override
        public final void disconnect(final ChannelPromise promise) {
            assertEventLoop();

            // 1.判断是否取消了,如果回调取消了,那么这次事件也不做了,如果没有取消设置为不可取消状态;
            if (!promise.setUncancellable()) {
                return;
            }

            boolean wasActive = isActive();
            try {
                // 2.执行断开连接操作;
                doDisconnect();
                // Reset remoteAddress and localAddress
                // 3.将本地地址和远程地址重置;
                remoteAddress = null;
                localAddress = null;
            } catch (Throwable t) {
                // 4.断开异常,那么处理相应的回调;
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            // 5.发起channel断开的操作;
            if (wasActive && !isActive()) {
                invokeLater(pipeline::fireChannelInactive);
            }

            // 6.处理回调;
            safeSetSuccess(promise);
            // 7.如果channel已经无效了,那么就关闭相应的回调;
            closeIfClosed(); // doDisconnect() might have closed the channel
        }

        @Override
        public final void close(final ChannelPromise promise) {
            assertEventLoop();

            ClosedChannelException closedChannelException = new ClosedChannelException();
            close(promise, closedChannelException, closedChannelException, false);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         *
         * 关闭channel相应的输出流,不在允许任何写的操作;
         */
        @UnstableApi
        public final void shutdownOutput(final ChannelPromise promise) {
            assertEventLoop();
            shutdownOutput(promise, null);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         * @param cause The cause which may provide rational for the shutdown.
         *              为关闭提供合理的异常;
         */
        private void shutdownOutput(final ChannelPromise promise, Throwable cause) {
            // 1.判断是否需要取消操作,如果不需要那么设置为不可取消状态,一般执行完状态就会改变不在是不可取消状态有效时间一般只是一个方法;
            if (!promise.setUncancellable()) {
                return;
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            // 2.判断channel缓冲区是否为null,是则向回调中设置失败;
            if (outboundBuffer == null) {
                promise.setFailure(new ClosedChannelException());
                return;
            }
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.
                                        // 不允许添加任何消息并且刷新到outboundBuffer;

            final Throwable shutdownCause = cause == null ?
                    new ChannelOutputShutdownException("Channel output shutdown") :
                    new ChannelOutputShutdownException("Channel output shutdown", cause);
            // 3.获取用来关闭的执行器;
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                // 4.执行器不为空,那么就用该执行器执行关闭任务;
                closeExecutor.execute(() -> {
                    try {
                        // Execute the shutdown.
                        // 5.执行关闭,最终会执行到关闭channel操作;
                        doShutdownOutput();
                        // 6.成功设置回调;
                        promise.setSuccess();
                    } catch (Throwable err) {
                        // 7.发生异常处理;
                        promise.setFailure(err);
                    } finally {
                        //
                        // Dispatch to the EventLoop
                        // 8.处理outboundBuffer;
                        eventLoop().execute(() ->
                                closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause));
                    }
                });
            } else {
                // 9.如果为null,那么就本线程执行相关关闭操作;
                try {
                    // Execute the shutdown.
                    doShutdownOutput();
                    promise.setSuccess();
                } catch (Throwable err) {
                    promise.setFailure(err);
                } finally {
                    closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
                }
            }
        }

        // 关闭outboundBuffer;
        private void closeOutboundBufferForShutdown(
                ChannelPipeline pipeline, ChannelOutboundBuffer buffer, Throwable cause) {
            // 将正在刷新的操作失败掉;
            buffer.failFlushed(cause, false);
            // 关闭buffer;
            buffer.close(cause, true);
            // pipeline发起fireUserEventTriggered操作;
            pipeline.fireUserEventTriggered(ChannelOutputShutdownEvent.INSTANCE);
        }

        // unsafe的关闭;
        private void close(final ChannelPromise promise, final Throwable cause,
                           final ClosedChannelException closeCause, final boolean notify) {
            // 1.是否需要取消该操作,否的话则设置为不可取消状态;
            if (!promise.setUncancellable()) {
                return;
            }

            // 2.是否已经执行过关闭了;
            if (closeInitiated) {
                // 3.判断关闭操作是否已经完成了;
                if (closeFuture.isDone()) {
                    // Closed already.
                    // 4.如果完成了那么就回调成功即可;
                    safeSetSuccess(promise);
                } else if (!(promise instanceof VoidChannelPromise)) { // Only needed if no VoidChannelPromise(这种void回调是不需要返回任何状态的).
                    // This means close() was called before so we just register a listener and return
                    // 如果关闭还没有完成,那么就注册一个监听器,等待回调然后设置promise成功;
                    closeFuture.addListener((ChannelFutureListener) future -> promise.setSuccess());
                }
                return;
            }

            // 5.设置是否关闭过的状态;
            closeInitiated = true;

            final boolean wasActive = isActive();
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.
            // 6.获取执行关闭的执行器;
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                closeExecutor.execute(() -> {
                    try {
                        // Execute the close.
                        // 7.执行关闭,最终也会执行到channel关闭;
                        doClose0(promise);
                    } finally {
                        // Call invokeLater so closeAndDeregister is executed in the EventLoop again!
                        // 8.处理outboundBuffer,并发起channel失效的入站操作和取消channel注册的出站操作;
                        invokeLater(() -> {
                            if (outboundBuffer != null) {
                                // Fail all the queued messages
                                outboundBuffer.failFlushed(cause, notify);
                                outboundBuffer.close(closeCause);
                            }
                            fireChannelInactiveAndDeregister(wasActive);
                        });
                    }
                });
            } else {
                // 9.本线程处理上边的逻辑;
                try {
                    // Close the channel and fail the queued messages in all cases.
                    doClose0(promise);
                } finally {
                    if (outboundBuffer != null) {
                        // Fail all the queued messages.
                        outboundBuffer.failFlushed(cause, notify);
                        outboundBuffer.close(closeCause);
                    }
                }
                // 详看该属性解释 ???question:为什么呢???;
                if (inFlush0) {
                    invokeLater(() -> fireChannelInactiveAndDeregister(wasActive));
                } else {
                    fireChannelInactiveAndDeregister(wasActive);
                }
            }
        }

        private void doClose0(ChannelPromise promise) {
            try {
                doClose();
                closeFuture.setClosed();
                safeSetSuccess(promise);
            } catch (Throwable t) {
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        private void fireChannelInactiveAndDeregister(final boolean wasActive) {
            deregister(voidPromise(), wasActive && !isActive());
        }

        // 强制关闭;
        @Override
        public final void closeForcibly() {
            try {
                doClose();
            } catch (Exception e) {
                logger.warn("Failed to close a channel.", e);
            }
        }

        @Override
        public final void deregister(final ChannelPromise promise) {
            assertEventLoop();

            deregister(promise, false);
        }

        // 取消channel的注册;
        private void deregister(final ChannelPromise promise, final boolean fireChannelInactive) {
            // 1.详情看上边的;
            if (!promise.setUncancellable()) {
                return;
            }

            // 2.查看是否注册成功过,如果没有那么就直接返回成功;
            if (!registered) {
                safeSetSuccess(promise);
                return;
            }

            // As a user may call deregister() from within any method while doing processing in the ChannelPipeline,
            // we need to ensure we do the actual deregister operation later. This is needed as for example,
            // we may be in the ByteToMessageDecoder.callDecode(...) method and so still try to do processing in
            // the old EventLoop while the user already registered the Channel to a new EventLoop. Without delay,
            // the deregister operation this could lead to have a handler invoked by different EventLoop and so
            // threads.
            // 3.由于用户可以在ChannelPipeline中进行处理时从任何方法中调用deregister（），因此我们需要确保稍后进行实际的注销操作.
            // 这是必需的
            // 例如，我们可能在ByteToMessageDecoder.callDecode（...）方法中，因此，当用户已经将Channel注册到新的EventLoop时,
            // 仍然尝试在旧的EventLoop中进行处理。
            // 没有延迟，注销操作可能会导致由不同的EventLoop等线程调用处理程序.
            // 大致意思是如果我把取消注册任务交给了当前的eventLoop,但是这个channel可能被重新注册到新的eventLoop中,然后又从这个eventLoop中执行了
            //  取消注册任务,那么就可能有两个eventLoop执行取消注册任务;
            // 之前ByteToMessageDecoder.decode（..）中调用deregister（）是不安全的,现在修改为later执行不是调用就立即执行了。
            // https://github.com/netty/netty/issues/4435
            invokeLater(() -> {
                try {
                    // 4.执行解除注册;
                    doDeregister();
                } catch (Throwable t) {
                    logger.warn("Unexpected exception occurred while deregistering a channel.", t);
                } finally {
                    if (fireChannelInactive) {
                        pipeline.fireChannelInactive();
                    }
                    // Some transports like local and AIO does not allow the deregistration of
                    // an open channel.  Their doDeregister() calls close(). Consequently,
                    // close() calls deregister() again - no need to fire channelUnregistered, so check
                    // if it was registered.
                    // 5.防止fireChannelUnregistered被执行多次;
                    if (registered) {
                        registered = false;
                        pipeline.fireChannelUnregistered();
                    }
                    safeSetSuccess(promise);
                }
            });
        }

        // 开始读取;
        @Override
        public final void beginRead() {
            assertEventLoop();

            // 1.channel是否活跃;
            if (!isActive()) {
                return;
            }

            try {
                // 2.执行开始读取;
                doBeginRead();
            } catch (final Exception e) {
                // 3.异常处理,发起fireExceptionCaught和关闭channel;
                invokeLater(() -> pipeline.fireExceptionCaught(e));
                close(voidPromise());
            }
        }

        // 写操作;
        @Override
        public final void write(Object msg, ChannelPromise promise) {
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            // 1.查看outboundBuffer是否为null,如果为null那么说明channel已经关闭了;
            if (outboundBuffer == null) {
                // If the outboundBuffer is null we know the channel was closed and so
                // need to fail the future right away. If it is not null the handling of the rest
                // will be done in flush0()
                // See https://github.com/netty/netty/issues/2362
                // 2.之前这里是通过isActive()来判断的,但是开销较大,所以采用更快的outboundBuffer == null来判断;
                safeSetFailure(promise, newClosedChannelException(initialCloseCause));
                // release message now to prevent resource-leak
                // 3.释放资源防止资源泄露;
                ReferenceCountUtil.release(msg);
                return;
            }

            int size;
            try {
                // 4.进行信息的过滤和包装;
                msg = filterOutboundMessage(msg);
                // 5.如果消息大小估计器为空那么新建一个消息大小估计器;
                if (estimatorHandler == null) {
                    estimatorHandler = config().getMessageSizeEstimator().newHandle();
                }
                // 6.估计出消息的大小;
                size = estimatorHandler.size(msg);
                if (size < 0) {
                    size = 0;
                }
            } catch (Throwable t) {
                // 7.异常处理;
                safeSetFailure(promise, t);
                ReferenceCountUtil.release(msg);
                return;
            }

            // 8.将消息添加到outboundBuffer,等待flush;
            outboundBuffer.addMessage(msg, size, promise);
        }

        @Override
        public final void flush() {
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            // 1.同上如果为null,说明channel已经关闭了;
            if (outboundBuffer == null) {
                return;
            }

            // 2.更新outboundBuffer的刷新entry;
            outboundBuffer.addFlush();
            flush0();
        }

        @SuppressWarnings("deprecation")
        protected void flush0() {
            // 1.判断是否正在flush操作,防止重复操作;
            if (inFlush0) {
                // Avoid re-entrance
                return;
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            // 2.判断当前channel是否关闭,缓冲区的信息是否为空;
            if (outboundBuffer == null || outboundBuffer.isEmpty()) {
                return;
            }

            // 3.将正在flush置为true;
            inFlush0 = true;

            // Mark all pending write requests as failure if the channel is inactive.
            // 4.判断channel是否可用;
            if (!isActive()) {
                try {
                    if (isOpen()) {
                        // 5.如果channel是开启的但是没有绑定成功或者没有连接,那么返回NotYetConnectedException;
                        outboundBuffer.failFlushed(new NotYetConnectedException(), true);
                    } else {
                        // Do not trigger channelWritabilityChanged because the channel is closed already.
                        // 不用触发channelWritabilityChanged,因为channel已经关闭;
                        // 6.channel已关闭,那么返回ClosedChannelException;
                        outboundBuffer.failFlushed(newClosedChannelException(initialCloseCause), false);
                    }
                } finally {
                    // 7.将正在flush置为false;
                    inFlush0 = false;
                }
                return;
            }

            try {
                // 8.开始将出站缓冲区进行写;
                doWrite(outboundBuffer);
            } catch (Throwable t) {
                // 9.异常处理,如果发生IO异常并且channel设置自动关闭,那么就关闭channel,否则执行shutdownOutput;
                if (t instanceof IOException && config().isAutoClose()) {
                    /**
                     * Just call {@link #close(ChannelPromise, Throwable, boolean)} here which will take care of
                     * failing all flushed messages and also ensure the actual close of the underlying transport
                     * will happen before the promises are notified.
                     *
                     * This is needed as otherwise {@link #isActive()} , {@link #isOpen()} and {@link #isWritable()}
                     * may still return {@code true} even if the channel should be closed as result of the exception.
                     */
                    initialCloseCause = t;
                    close(voidPromise(), t, newClosedChannelException(t), false);
                } else {
                    try {
                        shutdownOutput(voidPromise(), t);
                    } catch (Throwable t2) {
                        initialCloseCause = t;
                        close(voidPromise(), t2, newClosedChannelException(t), false);
                    }
                }
            } finally {
                // 7.最后将正在flush置为false;
                inFlush0 = false;
            }
        }

        private ClosedChannelException newClosedChannelException(Throwable cause) {
            ClosedChannelException exception = new ClosedChannelException();
            if (cause != null) {
                exception.initCause(cause);
            }
            return exception;
        }

        @Override
        public final ChannelPromise voidPromise() {
            assertEventLoop();

            return unsafeVoidPromise;
        }

        protected final boolean ensureOpen(ChannelPromise promise) {
            if (isOpen()) {
                return true;
            }

            safeSetFailure(promise, newClosedChannelException(initialCloseCause));
            return false;
        }

        /**
         * Marks the specified {@code promise} as success.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetSuccess(ChannelPromise promise) {
            if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {
                logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
            }
        }

        /**
         * Marks the specified {@code promise} as failure.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetFailure(ChannelPromise promise, Throwable cause) {
            if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
                logger.warn("Failed to mark a promise as failure because it's done already: {}", promise, cause);
            }
        }

        protected final void closeIfClosed() {
            if (isOpen()) {
                return;
            }
            close(voidPromise());
        }

        private void invokeLater(Runnable task) {
            try {
                // This method is used by outbound operation implementations to trigger an inbound event later.
                // They do not trigger an inbound event immediately because an outbound operation might have been
                // triggered by another inbound event handler method.  If fired immediately, the call stack
                // will look like this for example:
                //
                //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
                //   -> handlerA.ctx.close()
                //      -> channel.unsafe.close()
                //         -> handlerA.channelInactive() - (2) another inbound handler method called while in (1) yet
                //
                // which means the execution of two inbound handler methods of the same handler overlap undesirably.
                // later执行是为防止发生类似死循环,出站操作出发入站操作,入站操作也可能触发了出站操作,导致上面的例子无限循环;
                eventLoop().execute(task);
            } catch (RejectedExecutionException e) {
                logger.warn("Can't invoke task later as EventLoop rejected it", e);
            }
        }

        /**
         * Appends the remote address to the message of the exceptions caused by connection attempt failure.
         *
         * 更细化的处理;
         */
        protected final Throwable annotateConnectException(Throwable cause, SocketAddress remoteAddress) {
            if (cause instanceof ConnectException) {
                return new AnnotatedConnectException((ConnectException) cause, remoteAddress);
            }
            if (cause instanceof NoRouteToHostException) {
                return new AnnotatedNoRouteToHostException((NoRouteToHostException) cause, remoteAddress);
            }
            if (cause instanceof SocketException) {
                return new AnnotatedSocketException((SocketException) cause, remoteAddress);
            }

            return cause;
        }

        /**
         * Prepares to close the {@link Channel}. If this method returns an {@link Executor}, the
         * caller must call the {@link Executor#execute(Runnable)} method with a task that calls
         * {@link #doClose()} on the returned {@link Executor}. If this method returns {@code null},
         * {@link #doClose()} must be called from the caller thread. (i.e. {@link EventLoop})
         *
         * 如果返回不是空那么就用返回的执行器执行,否则就用当前线程来执行;
         */
        protected Executor prepareToClose() {
            return null;
        }
    }

    /**
     * Returns the {@link SocketAddress} which is bound locally.
     */
    protected abstract SocketAddress localAddress0();

    /**
     * Return the {@link SocketAddress} which the {@link Channel} is connected to.
     */
    protected abstract SocketAddress remoteAddress0();

    /**
     * Is called after the {@link Channel} is registered with its {@link EventLoop} as part of the register process.
     *
     * Sub-classes may override this method
     * channel注册;
     */
    protected void doRegister() throws Exception {
        eventLoop().unsafe().register(this);
    }

    /**
     * Bind the {@link Channel} to the {@link SocketAddress}
     */
    protected abstract void doBind(SocketAddress localAddress) throws Exception;

    /**
     * Disconnect this {@link Channel} from its remote peer
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * Close the {@link Channel}
     */
    protected abstract void doClose() throws Exception;

    /**
     * Called when conditions justify shutting down the output portion of the channel. This may happen if a write
     * operation throws an exception.
     */
    @UnstableApi
    protected void doShutdownOutput() throws Exception {
        doClose();
    }

    /**
     * Deregister the {@link Channel} from its {@link EventLoop}.
     *
     * Sub-classes may override this method
     */
    protected void doDeregister() throws Exception {
        eventLoop().unsafe().deregister(this);
    }

    /**
     * Schedule a read operation.
     */
    protected abstract void doBeginRead() throws Exception;

    /**
     * Flush the content of the given buffer to the remote peer.
     */
    protected abstract void doWrite(ChannelOutboundBuffer in) throws Exception;

    /**
     * Invoked when a new message is added to a {@link ChannelOutboundBuffer} of this {@link AbstractChannel}, so that
     * the {@link Channel} implementation converts the message to another. (e.g. heap buffer -> direct buffer)
     */
    protected Object filterOutboundMessage(Object msg) throws Exception {
        return msg;
    }

    protected void validateFileRegion(DefaultFileRegion region, long position) throws IOException {
        DefaultFileRegion.validate(region, position);
    }

    static final class CloseFuture extends DefaultChannelPromise {

        CloseFuture(AbstractChannel ch, EventExecutor eventExecutor) {
            super(ch, eventExecutor);
        }

        @Override
        public ChannelPromise setSuccess() {
            throw new IllegalStateException();
        }

        @Override
        public ChannelPromise setFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean trySuccess() {
            throw new IllegalStateException();
        }

        @Override
        public boolean tryFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        boolean setClosed() {
            return super.trySuccess();
        }
    }

    private static final class AnnotatedConnectException extends ConnectException {

        private static final long serialVersionUID = 3901958112696433556L;

        AnnotatedConnectException(ConnectException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class AnnotatedNoRouteToHostException extends NoRouteToHostException {

        private static final long serialVersionUID = -6801433937592080623L;

        AnnotatedNoRouteToHostException(NoRouteToHostException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class AnnotatedSocketException extends SocketException {

        private static final long serialVersionUID = 3896743275010454039L;

        AnnotatedSocketException(SocketException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }
}
