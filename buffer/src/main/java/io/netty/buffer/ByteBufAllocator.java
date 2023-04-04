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
package io.netty.buffer;

/**
 * Implementations are responsible to allocate buffers. Implementations of this interface are expected to be
 * thread-safe.
 *
 * netty的用来获取缓存空间;
 */
public interface ByteBufAllocator {

    ByteBufAllocator DEFAULT = ByteBufUtil.DEFAULT_ALLOCATOR;

    /**
     * Allocate a {@link ByteBuf}. If it is a direct or heap buffer
     * depends on the actual implementation.
     *
     * 分配{@link ByteBuf}.它是直接缓冲区还是堆缓冲区取决于实际的实现.
     */
    ByteBuf buffer();

    /**
     * Allocate a {@link ByteBuf} with the given initial capacity.
     * If it is a direct or heap buffer depends on the actual implementation.
     *
     * 分配具有给定初始容量的{@link ByteBuf}.它是直接缓冲区还是堆缓冲区取决于实际的实现.
     */
    ByteBuf buffer(int initialCapacity);

    /**
     * Allocate a {@link ByteBuf} with the given initial capacity and the given
     * maximal capacity. If it is a direct or heap buffer depends on the actual
     * implementation.
     * 
     * 用给定的初始容量和最大容量分配{@link ByteBuf}.它是直接缓冲区还是堆缓冲区取决于实际的实现.
     */
    ByteBuf buffer(int initialCapacity, int maxCapacity);

    /**
     * Allocate a {@link ByteBuf}, preferably a direct buffer which is suitable for I/O.
     *
     * 分配{@link ByteBuf},最好是适合I/O的直接缓冲区.
     */
    ByteBuf ioBuffer();

    /**
     * Allocate a {@link ByteBuf}, preferably a direct buffer which is suitable for I/O.
     *
     * 同上;
     */
    ByteBuf ioBuffer(int initialCapacity);

    /**
     * Allocate a {@link ByteBuf}, preferably a direct buffer which is suitable for I/O.
     *
     * 同上;
     */
    ByteBuf ioBuffer(int initialCapacity, int maxCapacity);

    /**
     * Allocate a heap {@link ByteBuf}.
     *
     * 申请堆内存;
     */
    ByteBuf heapBuffer();

    /**
     * Allocate a heap {@link ByteBuf} with the given initial capacity.
     *
     * 申请堆内存;
     */
    ByteBuf heapBuffer(int initialCapacity);

    /**
     * Allocate a heap {@link ByteBuf} with the given initial capacity and the given
     * maximal capacity.
     *
     * 申请堆内存;
     */
    ByteBuf heapBuffer(int initialCapacity, int maxCapacity);

    /**
     * Allocate a direct {@link ByteBuf}.
     *
     * 申请堆外内存;
     */
    ByteBuf directBuffer();

    /**
     * Allocate a direct {@link ByteBuf} with the given initial capacity.
     *
     * 申请堆外内存;
     */
    ByteBuf directBuffer(int initialCapacity);

    /**
     * Allocate a direct {@link ByteBuf} with the given initial capacity and the given
     * maximal capacity.
     *
     * 申请堆外内存;
     */
    ByteBuf directBuffer(int initialCapacity, int maxCapacity);

    /**
     * Allocate a {@link CompositeByteBuf}.
     * If it is a direct or heap buffer depends on the actual implementation.
     *
     * 申请组合内存,可能包含堆内存也可能包含堆外内存;
     */
    CompositeByteBuf compositeBuffer();

    /**
     * Allocate a {@link CompositeByteBuf} with the given maximum number of components that can be stored in it.
     * If it is a direct or heap buffer depends on the actual implementation.
     *
     * 申请组合内存,可能包含堆内存也可能包含堆外内存;
     */
    CompositeByteBuf compositeBuffer(int maxNumComponents);

    /**
     * Allocate a heap {@link CompositeByteBuf}.
     *
     * 申请一个堆内存的CompositeByteBuf;
     */
    CompositeByteBuf compositeHeapBuffer();

    /**
     * Allocate a heap {@link CompositeByteBuf} with the given maximum number of components that can be stored in it.
     *
     * 申请一个堆内存的CompositeByteBuf;
     */
    CompositeByteBuf compositeHeapBuffer(int maxNumComponents);

    /**
     * Allocate a direct {@link CompositeByteBuf}.
     *
     * 申请一个堆外内存的CompositeByteBuf;
     */
    CompositeByteBuf compositeDirectBuffer();

    /**
     * Allocate a direct {@link CompositeByteBuf} with the given maximum number of components that can be stored in it.
     *
     * 申请一个堆外内存的CompositeByteBuf;
     */
    CompositeByteBuf compositeDirectBuffer(int maxNumComponents);

    /**
     * Returns {@code true} if direct {@link ByteBuf}'s are pooled
     *
     * 是否是堆外内存池;
     */
    boolean isDirectBufferPooled();

    /**
     * Calculate the new capacity of a {@link ByteBuf} that is used when a {@link ByteBuf} needs to expand by the
     * {@code minNewCapacity} with {@code maxCapacity} as upper-bound.
     *
     * 用来计算扩容大小的;
     */
    int calculateNewCapacity(int minNewCapacity, int maxCapacity);
 }
