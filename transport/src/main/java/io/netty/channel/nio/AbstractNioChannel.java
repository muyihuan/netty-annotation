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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link Channel} implementations which use a Selector based approach.
 *
 * absNioChannel;
 */
public abstract class AbstractNioChannel extends AbstractChannel {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractNioChannel.class);

    //关键的属性，这个是真正的channel,也就是NIO实现的根本，所以在absNioChannel里;
    private final SelectableChannel ch;
    //这个是进行注册时的事件类型;
    protected final int readInterestOp;
    //这个时注册是的key;
    volatile SelectionKey selectionKey;
    // 是否处于待读状态,也就是注册了读事件到真正读取消息动作的期间;
    boolean readPending;
    private final Runnable clearReadPendingRunnable = this::clearReadPending0;

    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     *
     * promise主要是用来支持回调的;
     */
    private ChannelPromise connectPromise;
    //超时处理回调;
    private ScheduledFuture<?> connectTimeoutFuture;
    //请求地址;
    private SocketAddress requestedRemoteAddress;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param eventLoop         the {@link EventLoop} to use for all I/O.
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     * @param readInterestOp    the ops to set to receive data from the {@link SelectableChannel}
     */
    protected AbstractNioChannel(Channel parent, EventLoop eventLoop, SelectableChannel ch, int readInterestOp) {
        super(parent, eventLoop);
        this.ch = ch;
        this.readInterestOp = readInterestOp;
        try {
            // NIO,如果为true将无法注册到selector;
            ch.configureBlocking(false);
        } catch (IOException e) {
            try {
                ch.close();
            } catch (IOException e2) {
                logger.warn(
                            "Failed to close a partially initialized socket.", e2);
            }

            throw new ChannelException("Failed to enter non-blocking mode.", e);
        }
    }

    @Override
    public boolean isOpen() {
        return ch.isOpen();
    }

    @Override
    public NioUnsafe unsafe() {
        return (NioUnsafe) super.unsafe();
    }

    protected SelectableChannel javaChannel() {
        return ch;
    }

    /**
     * Return the current {@link SelectionKey}
     */
    protected SelectionKey selectionKey() {
        assert selectionKey != null;
        return selectionKey;
    }

    /**
     * @deprecated No longer supported.
     * No longer supported.
     */
    @Deprecated
    protected boolean isReadPending() {
        return readPending;
    }

    /**
     * @deprecated Use {@link #clearReadPending()} if appropriate instead.
     * No longer supported.
     */
    @Deprecated
    protected void setReadPending(final boolean readPending) {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                setReadPending0(readPending);
            } else {
                eventLoop.execute(() -> setReadPending0(readPending));
            }
        } else {
            // Best effort if we are not registered yet clear readPending.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            this.readPending = readPending;
        }
    }

    /**
     * Set read pending to {@code false}.
     *
     * 设置当前为待读状态,意思注册读事件但是还没有真正读过消息;
     */
    protected final void clearReadPending() {
        // 1.如果channel注册eventLoop那么通过eventloop去执行具体逻辑;
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            // 2.如果eventloop启动了那么直接执行clearReadPending0;
            if (eventLoop.inEventLoop()) {
                clearReadPending0();
            } else {
                // 3.如果eventloop没有启动那么启动并且执行clearReadPending0;
                eventLoop.execute(clearReadPendingRunnable);
            }
        } else {
            // Best effort if we are not registered yet clear readPending. This happens during channel initialization.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            // 如果没有注册过,那么说明selectkey还没注册,那么不能执行clearReadPending0,直接恢复属性readPending为false;
            readPending = false;
        }
    }

    private void setReadPending0(boolean readPending) {
        this.readPending = readPending;
        if (!readPending) {
            ((AbstractNioUnsafe) unsafe()).removeReadOp();
        }
    }

    // 恢复readPending并且取消注册的读事件,发生这种情况一般是因为读关闭或者什么异常以后不会主动处理读事件了;
    private void clearReadPending0() {
        readPending = false;
        ((AbstractNioUnsafe) unsafe()).removeReadOp();
    }

    /**
     * Special {@link Unsafe} sub-type which allows to access the underlying {@link SelectableChannel}
     *
     * Nio channel特有的;
     */
    public interface NioUnsafe extends Unsafe {
        /**
         * Return underlying {@link SelectableChannel}
         * 返回原生channel;
         */
        SelectableChannel ch();

        /**
         * Finish connect
         *
         */
        void finishConnect();

        /**
         * Read from underlying {@link SelectableChannel}
         * 从原生channel读取消息;
         */
        void read();

        // 强制刷新;
        void forceFlush();
    }

    protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {

        // 删除读操作;
        protected final void removeReadOp() {
            // 1.获取当前的注册的key;
            SelectionKey key = selectionKey();
            // Check first if the key is still valid as it may be canceled as part of the deregistration
            // from the EventLoop
            // See https://github.com/netty/netty/issues/2104
            // 2.先检查key是否可用,因为key可能被取消了,如果不可用就不用处理了;
            if (!key.isValid()) {
                return;
            }
            int interestOps = key.interestOps();
            // 3.取消掉读事件,这里应该是个流程操作,读事件完成了,要自己去取消掉读操作,否则这个key再被执行时如果没去除读操作,那么会再去读;
            if ((interestOps & readInterestOp) != 0) {
                // only remove readInterestOp if needed
                key.interestOps(interestOps & ~readInterestOp);
            }
        }

        // 返回原生channel;
        @Override
        public final SelectableChannel ch() {
            return javaChannel();
        }

        // channel建立连接;
        @Override
        public final void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            // 1.将回调设置为不可取消状态,并且确保channel是否时开启状态;
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
                // 2.如果connectPromise不等于null,说明已经连接过程中;
                if (connectPromise != null) {
                    // Already a connect in process.
                    throw new ConnectionPendingException();
                }

                boolean wasActive = isActive();
                // 3.客户端建立连接,注册op_connect事件;
                if (doConnect(remoteAddress, localAddress)) {
                    // 4.如果连接立即建立连接成功;
                    fulfillConnectPromise(promise, wasActive);
                } else {
                    // 说明连接没有立即完成需要等待connect事件完成;
                    // 5.设置连接回调;
                    connectPromise = promise;
                    // 6.赋值连接地址;
                    requestedRemoteAddress = remoteAddress;

                    // Schedule connect timeout.
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();
                    // 7.如果设置的连接超时事件大于0,那么添加超时关闭channel的延时任务;
                    // 如果在超时时间内连接事件没有成功那么延时任务会设置连接回调失败并关闭channel;
                    // 如果在超时事件之前完成连接了,那么就会执行到@link finishConnect进行后续的处理;
                    if (connectTimeoutMillis > 0) {
                        connectTimeoutFuture = eventLoop().schedule(() -> {
                            ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                            ConnectTimeoutException cause =
                                    new ConnectTimeoutException("connection timed out: " + remoteAddress);
                            if (connectPromise != null && connectPromise.tryFailure(cause)) {
                                close(voidPromise());
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }

                    // 如果超时之前取消了那么上面的回调任务将不会执行;
                    // 注意connectPromise在连接成功后必定为null;
                    promise.addListener((ChannelFutureListener) future -> {
                        if (future.isCancelled()) {
                            if (connectTimeoutFuture != null) {
                                connectTimeoutFuture.cancel(false);
                            }
                            connectPromise = null;
                            close(voidPromise());
                        }
                    });
                }
            } catch (Throwable t) {
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                closeIfClosed();
            }
        }

        // 处理建立连接成功的回调;
        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
            // 1.如果回调为null,那么不做任何事;
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                // 为空说明可能连接取消或者关闭了;
                return;
            }

            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            // 2.对于服务端来说channel打开并且地址绑定成功,对于客户端来说channel打开并且建立连接成功;
            boolean active = isActive();

            // trySuccess() will return false if a user cancelled the connection attempt.
            // 3.设置回调成功状态;
            boolean promiseSet = promise.trySuccess();

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            // 4.如果channel active 发起fireChannelActive;
            if (!wasActive && active) {
                pipeline().fireChannelActive();
                // 5.如果channel设置自动读取,那么当连接成功或者连接完成就进行读取信息操作,无需用户发起读取信息;
                readIfIsAutoRead();
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            // 6.如果连接失败或者发生异常那么就关闭channel;
            if (!promiseSet) {
                close(voidPromise());
            }
        }

        // 建立连接发生异常进行处理;
        private void fulfillConnectPromise(ChannelPromise promise, Throwable cause) {
            // 1.如果回调为null,那么不做任何事;
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                // 为空说明可能连接取消或者关闭了;
                return;
            }

            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            // 使用tryFailure（）而不是setFailure（）来避免与cancel（）的竞争???question:为什么???;
            // 因为setFailure可能会抛异常导致调用栈的某个catch还会执行修改回调的状态的地方导致瞬间多个修改发生竞争导致cancel失败;
            promise.tryFailure(cause);
            closeIfClosed();
        }

        // 如果连接事件没有立即成功需要io事件处理,那么io事件处理完就会调用此方法完成后续任务;
        @Override
        public final void finishConnect() {
            // Note this method is invoked by the event loop only if the connection attempt was
            // neither cancelled nor timed out.
            // 1.此方法执行在eventLoop线程中执行;
            assert eventLoop().inEventLoop();

            try {
                // 2.channel是否可通信了???question:???这个肯定是true呀感觉有问题;
                boolean wasActive = isActive();
                // 3.执行channel连接成功的事情,主要是判断连接是否真正成功;
                doFinishConnect();
                // 4.处理连接回调;
                fulfillConnectPromise(connectPromise, wasActive);
            } catch (Throwable t) {
                // 5.发生异常处理连接回调;
                fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
            } finally {
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
                // 如果用户取消了连接操作,这里不判null可能会发生NPE异常;
                if (connectTimeoutFuture != null) {
                    connectTimeoutFuture.cancel(false);
                }
                connectPromise = null;
            }
        }

        @Override
        protected final void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
            // 如果当前channel写缓冲区的的消息为空那么会立即执行flush,否则不会立即执行会等eventLoop稍后执行forceFlush;
            if (!isFlushPending()) {
                super.flush0();
            }
        }

        // 强制刷新;
        @Override
        public final void forceFlush() {
            // directly call super.flush0() to force a flush now
            super.flush0();
        }

        private boolean isFlushPending() {
            SelectionKey selectionKey = selectionKey();
            return selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
        }
    }

    @Override
    protected void doRegister() throws Exception {
       eventLoop().unsafe().register(this);
    }

    @Override
    protected void doDeregister() throws Exception {
        eventLoop().unsafe().deregister(this);
    }

    // 开始读为真正的读做准备,注册读时间;
    @Override
    protected void doBeginRead() throws Exception {
        // Channel.read() or ChannelHandlerContext.read() was called
        final SelectionKey selectionKey = this.selectionKey;
        if (!selectionKey.isValid()) {
            return;
        }

        readPending = true;

        final int interestOps = selectionKey.interestOps();
        if ((interestOps & readInterestOp) == 0) {
            selectionKey.interestOps(interestOps | readInterestOp);
        }
    }

    /**
     * Connect to the remote peer
     */
    protected abstract boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception;

    /**
     * Finish the connect
     */
    protected abstract void doFinishConnect() throws Exception;

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the original one.
     * Note that this method does not create an off-heap copy if the allocation / deallocation cost is too high,
     * but just returns the original {@link ByteBuf}..
     * 返回指定{@link ByteBuf}的堆外副本，并释放原始副本。注意，如果分配/释放成本太高，此方法不会创建堆外副本，而只是返回原始的{@link ByteBuf}
     */
    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        // 1.判断当前消息的实际大小如果等于零那么释放该消息返回一个空的EMPTY_BUFFER;
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(buf);
            return Unpooled.EMPTY_BUFFER;
        }

        // 2.获取内存分配器,如果该分配器是分配对外内存的那么进行内存申请并将消息内容copy过去;
        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        // 3.???question:???
        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        // 分配和释放非缓存的堆外内存非常昂贵；放弃。
        return buf;
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the specified holder.
     * The caller must ensure that the holder releases the original {@link ByteBuf} when the holder is released by
     * this method.  Note that this method does not create an off-heap copy if the allocation / deallocation cost is
     * too high, but just returns the original {@link ByteBuf}..
     *
     * 和上面的方法相同,但是这个方法释放的是指定的持有者;???question:指定的持有者是什么???
     */
    protected final ByteBuf newDirectBuffer(ReferenceCounted holder, ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(holder);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        if (holder != buf) {
            // Ensure to call holder.release() to give the holder a chance to release other resources than its content.
            buf.retain();
            ReferenceCountUtil.safeRelease(holder);
        }

        return buf;
    }

    // 关闭关闭和清空连接回调和连接超时回调;
    @Override
    protected void doClose() throws Exception {
        ChannelPromise promise = connectPromise;
        if (promise != null) {
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(new ClosedChannelException());
            connectPromise = null;
        }

        ScheduledFuture<?> future = connectTimeoutFuture;
        if (future != null) {
            future.cancel(false);
            connectTimeoutFuture = null;
        }
    }
}
