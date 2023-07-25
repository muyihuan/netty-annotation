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

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 *
 * 这个是基于nio 消息的;
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    boolean inputShutdown;

    /**
     * @see AbstractNioChannel#AbstractNioChannel(Channel, EventLoop, SelectableChannel, int)
     */
    protected AbstractNioMessageChannel(Channel parent, EventLoop eventLoop, SelectableChannel ch, int readInterestOp) {
        super(parent, eventLoop, ch, readInterestOp);
    }

   /**
    * Unsafe在这里实现的;
    */
    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioMessageUnsafe();
    }

   /**
    * 当inputShutdown为true时，不在进行channel事件的处理;
    * @throws Exception
    */
    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        // 主要减少每次读都要新建一个list来接受读取的消息,这个消息也可能是channel等;
        private final List<Object> readBuf = new ArrayList<>();

        // 读操作;
        @Override
        public void read() {
            // 1.该方法必须在eventLoop线程执行;
            assert eventLoop().inEventLoop();
            // 2.获取channel的配置,pipeline,接收缓冲buf的分配器;
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();
            // 3.获取接受缓冲分配器;
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
            // 4.将缓冲分配器重置初始状态;
            allocHandle.reset(config);

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    do {
                        // 5.读取消息;
                        int localRead = doReadMessages(readBuf);
                        // 6.如果没有消息或者读取失败那么就推出read loop;
                        if (localRead == 0) {
                            break;
                        }
                        // 发生异常读取失败;
                        if (localRead < 0) {
                            closed = true;
                            break;
                        }

                        // 7.更新已读消息个数;
                        allocHandle.incMessagesRead(localRead);
                        // 8.判断是否继续读;
                    } while (allocHandle.continueReading());
                } catch (Throwable t) {
                    exception = t;
                }

                // 9.发起pipeline读handler会进行处理;
                int size = readBuf.size();
                for (int i = 0; i < size; i ++) {
                    readPending = false;
                    pipeline.fireChannelRead(readBuf.get(i));
                }
                // 10.清空readBuf;
                readBuf.clear();
                //
                allocHandle.readComplete();
                // 11.发起fireChannelReadComplete;
                pipeline.fireChannelReadComplete();

                // 12.如果读发生异常,那么发起fireExceptionCaught,
                if (exception != null) {
                    // 看该方法的注释;
                    closed = closeOnReadError(exception);
                    pipeline.fireExceptionCaught(exception);
                }

                // 13.如果需要关闭channel,如果channel没有关闭那么就关闭;
                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) {
                        close(voidPromise());
                    }
                } else {
                    // 如果channel设置自动读取,那么当连接成功或者连接完成就进行读取信息操作,无需用户发起读取信息;
                    readIfIsAutoRead();
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                // 如果设置不自动读取消息并且读到了消息,那么进行removeReadOp;
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    // 写操作;
    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        // 1.获取注册的事件;
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        for (;;) {
            // 2.获取写缓冲区的消息;
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                // 3.如果写缓冲区消息为空,那么说明所有的消息都已经写完了,那么取消写事件,当有消息需要写的时候需要自行注册写事件;
                if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                    key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
                }
                break;
            }
            try {
                boolean done = false;
                // 4.自旋一定次数直到信息写成功;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                // 5.如果消息写成功,那么就从写缓冲里删除;
                if (done) {
                    in.remove();
                } else {
                    // Did not write all messages.
                    // 6.写缓冲区还有消息没有写完,那么就确保写事件是注册的;
                    if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                        key.interestOps(interestOps | SelectionKey.OP_WRITE);
                    }
                    break;
                }
            } catch (Exception e) {
                // 7.如果发生异常那么判断是否忽略该消息继续写未写完的消息,还是抛异常;
                if (continueOnWriteError()) {
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     * 如果发生异常那么判断是否忽略该消息继续写未写完的消息,还是抛异常;
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    // 用来判断当读发生异常时是否关闭channel;
    protected boolean closeOnReadError(Throwable cause) {
        // 如果channel没有open或者没有建立连接或者没有端口绑定那么返回true;
        if (!isActive()) {
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        // 发生该异常一般是icmp协议,这个协议是用来网络通不通、主机是否可达、路由是否可用等网络本身的消息,这些不包含用户的消息所以可以忽略;
        if (cause instanceof PortUnreachableException) {
            return false;
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            // 发生该io异常原因一般是因为连接太多了,所以如果是服务端那么需要关闭channel,因为可能还会有大量的新的连接过来;
            // ServerChannel不用理会该错误;
            return !(this instanceof ServerChannel);
        }
        return true;
    }

    /**
     * Read messages into the given array and return the amount which was read.
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
