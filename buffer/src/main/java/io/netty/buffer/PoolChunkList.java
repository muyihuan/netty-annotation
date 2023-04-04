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

import io.netty.util.internal.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.lang.Math.*;

import java.nio.ByteBuffer;

final class PoolChunkList<T> implements PoolChunkListMetric {
    private static final Iterator<PoolChunkMetric> EMPTY_METRICS = Collections.<PoolChunkMetric>emptyList().iterator();
    // 该chunklist的所属的arena;
    private final PoolArena<T> arena;
    // 指向下一个chunklist,     当该chunklist的使用率大于maxUsage就会晋升到nextList;
    private final PoolChunkList<T> nextList;
    // 最小使用量限制,代表了内存利用率范围,   真实代表着该chunklist已使用的空间占比;
    private final int minUsage;
    // 最大使用量限制,代表了内存利用率范围,   真实代表着该chunklist可以使用的最大空间占比;
    private final int maxUsage;
    // 最大容量 = (chunkSize * (100 - minUsage) / 100);
    private final int maxCapacity;
    // 该chunklist的head节点;
    private PoolChunk<T> head;

    // This is only update once when create the linked like list of PoolChunkList in PoolArena constructor.
    // 在PoolArena构造函数中创建PoolChunkList的链式列表时,这只更新一次;
    // 指向前一个chunklist,     当该chunklist释放内存后的使用率小于minUsage就会回退到prevList;
    private PoolChunkList<T> prevList;

    // TODO: Test if adding padding helps under contention
    // 伪缓存测试;
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunkList(PoolArena<T> arena, PoolChunkList<T> nextList, int minUsage, int maxUsage, int chunkSize) {
        assert minUsage <= maxUsage;
        this.arena = arena;
        this.nextList = nextList;
        this.minUsage = minUsage;
        this.maxUsage = maxUsage;
        maxCapacity = calculateMaxCapacity(minUsage, chunkSize);
    }

    /**
     * Calculates the maximum capacity of a buffer that will ever be possible to allocate out of the {@link PoolChunk}s
     * that belong to the {@link PoolChunkList} with the given {@code minUsage} and {@code maxUsage} settings.
     *
     * 使用给定的{@code minUsage}和{@code maxUsage}设置,计算从属于{@link PoolChunkList}的{@link PoolChunk}中分配的最大缓冲区容量;
     */
    private static int calculateMaxCapacity(int minUsage, int chunkSize) {
        minUsage = minUsage0(minUsage);

        if (minUsage == 100) {
            // If the minUsage is 100 we can not allocate anything out of this list.
            // minUsage = 100说明已经全部使用完了,不能再分配空间了;
            return 0;
        }

        // Calculate the maximum amount of bytes that can be allocated from a PoolChunk in this PoolChunkList.
        //
        // As an example:
        // - If a PoolChunkList has minUsage == 25 we are allowed to allocate at most 75% of the chunkSize because
        //   this is the maximum amount available in any PoolChunk in this PoolChunkList.
        // 计算还可以分配的空间大小;
        return  (int) (chunkSize * (100L - minUsage) / 100L);
    }

    // 添加前置chunklist;
    void prevList(PoolChunkList<T> prevList) {
        assert this.prevList == null;
        this.prevList = prevList;
    }

    // 分配空间;
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        // 1.判断申请的空间如果大于最大允许申请的空间大小,那么直接返回false;
        if (normCapacity > maxCapacity) {
            // Either this PoolChunkList is empty or the requested capacity is larger then the capacity which can
            // be handled by the PoolChunks that are contained in this PoolChunkList.
            // 此池块列表为空或请求的容量大于此池块列表中包含的池块可以处理的容量;
            return false;
        }

        // 2.遍历chunk进行分配空间,next直至成功或者为null;
        for (PoolChunk<T> cur = head; cur != null; cur = cur.next) {
            if (cur.allocate(buf, reqCapacity, normCapacity)) {
                // 3.申请成功那么判断当前chunk的使用率是否大于该chunklist允许的;
                if (cur.usage() >= maxUsage) {
                    // 4.该chunk需要晋升到nextList;
                    remove(cur);
                    nextList.add(cur);
                }
                return true;
            }
        }

        // 5.全部申请失败;
        return false;
    }

    // 将申请的bytebuffer释放到指定的chunk里;
    // return false: 代表该内存可以真正释放掉不回收到池里;
    // return true: 说明成功回收到池里了;
    boolean free(PoolChunk<T> chunk, long handle, ByteBuffer nioBuffer) {
        // 1.释放到该chunk里;
        chunk.free(handle, nioBuffer);
        // 2.如果释放后该chunk的可用占比小于该chunklist的minUsage,那么降级到prevList;
        if (chunk.usage() < minUsage) {
            remove(chunk);
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }
        return true;
    }

    // 删除指定chunk, 如果不存在那么添加到该list;
    private boolean move(PoolChunk<T> chunk) {
        assert chunk.usage() < maxUsage;

        if (chunk.usage() < minUsage) {
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }

        // PoolChunk fits into this PoolChunkList, adding it here.
        add0(chunk);
        return true;
    }

    /**
     * Moves the {@link PoolChunk} down the {@link PoolChunkList} linked-list so it will end up in the right
     * {@link PoolChunkList} that has the correct minUsage / maxUsage in respect to {@link PoolChunk#usage()}.
     */
    private boolean move0(PoolChunk<T> chunk) {
        if (prevList == null) {
            // There is no previous PoolChunkList so return false which result in having the PoolChunk destroyed and
            // all memory associated with the PoolChunk will be released.
            // 没有前置的PoolChunkList,因此返回false将导致PoolChunk被销毁,并且与PoolChunk关联的所有内存都将被释放不会回收到池中了;
            assert chunk.usage() == 0;
            return false;
        }
        // 添加到prevList;
        return prevList.move(chunk);
    }

    // 添加一个chunk,会进行是否晋升和降级判断;
    void add(PoolChunk<T> chunk) {
        if (chunk.usage() >= maxUsage) {
            nextList.add(chunk);
            return;
        }
        add0(chunk);
    }

    /**
     * Adds the {@link PoolChunk} to this {@link PoolChunkList}.
     *
     * 添加chunk到list;
     */
    void add0(PoolChunk<T> chunk) {
        chunk.parent = this;
        // 如果head为null将该chunk置为head;
        if (head == null) {
            head = chunk;
            chunk.prev = null;
            chunk.next = null;
        } else {
            chunk.prev = null;
            chunk.next = head;
            head.prev = chunk;
            head = chunk;
        }
    }

    // 移除指定chunk;
    private void remove(PoolChunk<T> cur) {
        if (cur == head) {
            head = cur.next;
            if (head != null) {
                head.prev = null;
            }
        } else {
            PoolChunk<T> next = cur.next;
            cur.prev.next = next;
            if (next != null) {
                next.prev = cur.prev;
            }
        }
    }

    @Override
    public int minUsage() {
        return minUsage0(minUsage);
    }

    @Override
    public int maxUsage() {
        return min(maxUsage, 100);
    }

    // 最小使用量要大于1;
    private static int minUsage0(int value) {
        return max(1, value);
    }

    @Override
    public Iterator<PoolChunkMetric> iterator() {
        synchronized (arena) {
            if (head == null) {
                return EMPTY_METRICS;
            }
            List<PoolChunkMetric> metrics = new ArrayList<>();
            for (PoolChunk<T> cur = head;;) {
                metrics.add(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
            }
            return metrics.iterator();
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        synchronized (arena) {
            if (head == null) {
                return "none";
            }

            for (PoolChunk<T> cur = head;;) {
                buf.append(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
                buf.append(StringUtil.NEWLINE);
            }
        }
        return buf.toString();
    }

    // 销毁该chunklist;
    void destroy(PoolArena<T> arena) {
        PoolChunk<T> chunk = head;
        while (chunk != null) {
            // 遍历link chunk 进行销毁;
            arena.destroyChunk(chunk);
            chunk = chunk.next;
        }
        head = null;
    }
}
