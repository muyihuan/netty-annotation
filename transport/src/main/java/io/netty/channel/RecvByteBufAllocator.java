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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;
import io.netty.util.internal.UnstableApi;

import static java.util.Objects.requireNonNull;

/**
 * Allocates a new receive buffer whose capacity is probably large enough to read all inbound data and small enough
 * not to waste its space.
 * 分配一个新的容量大小正合适入站读取的消息大小接受缓冲区;
 *
 * 接受缓冲分配器;
 */
public interface RecvByteBufAllocator {
    /**
     * Creates a new handle.  The handle provides the actual operations and keeps the internal information which is
     * required for predicting an optimal buffer capacity.
     *
     * 创建handle 句柄提供实际操作，并保留预测最佳缓冲容量所需的内部信息。
     */
    Handle newHandle();

    /**
     * @deprecated Use {@link ExtendedHandle}.
     */
    @Deprecated
    interface Handle {
        /**
         * Creates a new receive buffer whose capacity is probably large enough to read all inbound data and small
         * enough not to waste its space.
         *
         * 创建一个新的容量大小正合适入站读取的消息大小接受缓冲区;
         */
        ByteBuf allocate(ByteBufAllocator alloc);

        /**
         * Similar to {@link #allocate(ByteBufAllocator)} except that it does not allocate anything but just tells the
         * capacity.
         *
         * 和allocate相似但是不创建空间只是返回容量;
         */
        int guess();

        /**
         * Reset any counters that have accumulated and recommend how many messages/bytes should be read for the next
         * read loop.
         * <p>
         * This may be used by {@link #continueReading()} to determine if the read operation should complete.
         * </p>
         * This is only ever a hint and may be ignored by the implementation.
         * @param config The channel configuration which may impact this object's behavior.
         *
         * 重置所有已累积的计数器，并建议下次读取循环应读取多少消息/字节;
         * 一般将分配器里的计数的属性重置为开始状态;
         */
        void reset(ChannelConfig config);

        /**
         * Increment the number of messages that have been read for the current read loop.
         * @param numMessages The amount to increment by.
         *
         * 增加已读消息的个数;
         */
        void incMessagesRead(int numMessages);

        /**
         * Set the bytes that have been read for the last read operation.
         * This may be used to increment the number of bytes that have been read.
         * @param bytes The number of bytes from the previous read operation. This may be negative if an read error
         * occurs. If a negative value is seen it is expected to be return on the next call to
         * {@link #lastBytesRead()}. A negative value will signal a termination condition enforced externally
         * to this class and is not required to be enforced in {@link #continueReading()}.
         *
         * 设置上次读取的大小,更新读索引;
         * 上次读取操作的字节数.如果发生读取错误,则此值可能为负值,如果出现负值,则在下次调用{@link#lastBytesRead()时将返回该值
         * 负值表示在外部对此类强制的终止条件,而不要求在{@link#continueReading（）中强制.
         */
        void lastBytesRead(int bytes);

        /**
         * Get the amount of bytes for the previous read operation.
         * @return The amount of bytes for the previous read operation.
         *
         * 获取上一个读取操作的字节数;
         */
        int lastBytesRead();

        /**
         * Set how many bytes the read operation will (or did) attempt to read.
         * @param bytes How many bytes the read operation will (or did) attempt to read.
         *
         * 设置读取操作将（或已）尝试读取的字节数;
         */
        void attemptedBytesRead(int bytes);

        /**
         * Get how many bytes the read operation will (or did) attempt to read.
         * @return How many bytes the read operation will (or did) attempt to read.
         *
         * 获取读取操作将（或已）尝试读取的字节数;
         */
        int attemptedBytesRead();

        /**
         * Determine if the current read loop should continue.
         * @return {@code true} if the read loop should continue reading. {@code false} if the read loop is complete.
         *
         * 确定当前读取循环是否应继续;
         */
        boolean continueReading();

        /**
         * The read has completed.
         *
         * 读是否完成;
         */
        void readComplete();
    }

    // 扩展类但是不稳定的实;
    @SuppressWarnings("deprecation")
    @UnstableApi
    interface ExtendedHandle extends Handle {
        /**
         * Same as {@link Handle#continueReading()} except "more data" is determined by the supplier parameter.
         * @param maybeMoreDataSupplier A supplier that determines if there maybe more data to read.
         *
         * 和continueReading作用相同但是多了个maybeMoreDataSupplier的布尔lambda表达式的影响因素;
         */
        boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier);
    }

    /**
     * A {@link Handle} which delegates all call to some other {@link Handle}.
     *
     * 将实现委托给其他handle的handle,采用了装饰者模式,不用看了实现都是交给委托的类来实现的;
     */
    class DelegatingHandle implements Handle {
        // 委托的类;
        private final Handle delegate;

        public DelegatingHandle(Handle delegate) {
            this.delegate = requireNonNull(delegate, "delegate");
        }

        /**
         * Get the {@link Handle} which all methods will be delegated to.
         * @return the {@link Handle} which all methods will be delegated to.
         */
        protected final Handle delegate() {
            return delegate;
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return delegate.allocate(alloc);
        }

        @Override
        public int guess() {
            return delegate.guess();
        }

        @Override
        public void reset(ChannelConfig config) {
            delegate.reset(config);
        }

        @Override
        public void incMessagesRead(int numMessages) {
            delegate.incMessagesRead(numMessages);
        }

        @Override
        public void lastBytesRead(int bytes) {
            delegate.lastBytesRead(bytes);
        }

        @Override
        public int lastBytesRead() {
            return delegate.lastBytesRead();
        }

        @Override
        public boolean continueReading() {
            return delegate.continueReading();
        }

        @Override
        public int attemptedBytesRead() {
            return delegate.attemptedBytesRead();
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            delegate.attemptedBytesRead(bytes);
        }

        @Override
        public void readComplete() {
            delegate.readComplete();
        }
    }
}
