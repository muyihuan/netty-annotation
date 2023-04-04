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

// 用来分配page的部分空间用的;
final class PoolSubpage<T> implements PoolSubpageMetric {

    // chunk;
    final PoolChunk<T> chunk;
    // chunk(包含了 chunksize / pageSize 个page)中对应该page的id;
    private final int memoryMapIdx;
    private final int runOffset;
    // page size = 1 << 13 = 8k;
    private final int pageSize;
    // pageSize / 16 / 64
    // pagesize / 64因为long类型为64bit, / 16是因为每一个Byte代表16B的大小为单位进行标记是否使用;
    private final long[] bitmap;

    // 前置节点;
    PoolSubpage<T> prev;
    // 后置节点;
    PoolSubpage<T> next;

    // true: 表示该subpage不可销毁, false: 表示可销毁(说明该subpage的空间都释放了,没有占用了该subpage对象可以销毁了);
    boolean doNotDestroy;
    // 表示一个page拆分为多个elem的大小;
    // subpage第一次创建的时候该elemSize确定,以后该subpage只处理elemSize的大小的空间申请;
    // bitset的每个bit为代表elemSize大小的使用情况;
    int elemSize;
    // Elem的最大值 =  pageSize / elemSize;
    private int maxNumElems;
    // maxNumElems >>> 6;
    private int bitmapLength;
    // 下一个利用率;
    private int nextAvail;
    // 初始值pageSize / elemSize,没申请一个  elemSize  的空间就减一,表示当前可用的  elemSize  的数量;
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    // private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    // 用来专门创建head的;
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    // 构建非head节点使用的;
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }

    // init;
    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            // 参考属性的注释;
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            // bitset的每个bit为代表elemSize大小的使用情况;
            // bitmapLength maxNumElems % 64(long类型的长度) != 0 那么 + 1;
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            // 初始化bitmap;
            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        // 头插法;
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     *
     * 分配空间;
     */
    long allocate() {
        // 1.如果elemSize == 0说明
        if (elemSize == 0) {
            return toHandle(0);
        }

        // 2.可用空间数为0或该subpage已销毁将分配失败;
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 3.查找下个elem对应的bitmapIdx;
        final int bitmapIdx = getNextAvail();
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        // 4.将elem所对应的bitmap位对应的位设置为1;
        bitmap[q] |= 1L << r;

        // 5.判断当前可用elem数量书否大于零,如果等于0那么删除该节点;
        if (-- numAvail == 0) {
            removeFromPool();
        }

        // 6.handle的高位代表subpage的index,低32位代表该subpage所在的page的在chunk里的index;
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     *
     * 释放bitmapIdx对应的空间将对应bit位设置为0;
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        // 1.找到bitmapIdx的对应的位置,并设置为0;
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        // 2.设置nextAvail方便下一次可以快速找到空闲位置;
        setNextAvail(bitmapIdx);

        // 3.可用的elem的数量增加;
        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // 4.如果该subpage全部释放了
            // Subpage not in use (numAvail == maxNumElems)
            // prev == next说明当前link里除了head只有这一个节点,那么不进行删除;
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            // 5.设置该节点已删除,并从link里删除该节点;
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    // 采用的头插法;
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    // 删除该节点;
    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    // 获取获取下一个可用的的elem的bitmap位;
    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        // nextAvail >= 0说明是有部分释放了所以nextAvail变成刚释放的那个;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    // 获取获取下一个可用的的elem的bitmap位;
    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // 该bits的所有不全为1;
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    // 获取获取下一个可用的的elem的bitmap位;
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        // 乘以64表示起始位;
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j ++) {
            if ((bits & 1) == 0) {
                // 从起始位开始不断增加知道找到为0的bit,并判断是否超过了maxNumElems的数量;
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    // handle代表chunk中page数组的index值;
    // handle的高位代表subpage的index,低32位代表该subpage所在的page的在chunk里的index;
    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        // ???question:为什么要同步arena???
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        // ???question:为什么要同步arena???
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        // ???question:为什么要同步arena???
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    // 销毁;
    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
