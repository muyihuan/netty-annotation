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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 * PoolChunk中PageRun/PoolSubpage分配算法描述
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated page是chunk的最小单位;
 * > chunk - a chunk is a collection of pages  chunk就是page的集合;
 * > in this code chunkSize = 2^{maxOrder} * pageSize  chunk的大小是(1 << maxOrder) * pageSize;
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 *                                    说明该节点无法被分配但是他的子节点仍然可以根据它们的可用性进行分配
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 *
 * chunk没有进行同步的地方,同步的保证都在arena里;
 */
final class PoolChunk<T> implements PoolChunkMetric {

    // 31;
    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    // 所属的arena;
    final PoolArena<T> arena;
    // 真正的空间;
    final T memory;
    // 是否是池类型;
    final boolean unpooled;
    // chunk的起始位置在memory的偏移量;
    final int offset;
    // 存储当前节点的深度,该深度为子节点的中深度的max值,    memoryMap[id] > depthMap[id] 说明子节点已经有分配过空间的了;
    private final byte[] memoryMap;
    // 存储的是当前节点的理论深度;
    private final byte[] depthMap;
    // 创建  1 <<< maxorder  个subpage;
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    // 用来判断是否大于pagesize大小;
    // ~(pageSize - 1);
    private final int subpageOverflowMask;
    // pagesize;
    private final int pageSize;
    // pagesize = 1 << pageShifts;
    private final int pageShifts;
    // 表示一个chunk包含了 1 << maxOrder个pages;
    private final int maxOrder;
    // chunksize = pagesize << maxOrder;
    private final int chunkSize;
    // 等于  maxOrder + pageShifts;
    private final int log2ChunkSize;
    // 1 << maxOrder   ;
    private final int maxSubpageAllocs;
    /** Used to mark memory as unusable */
    // 用来判断chunk或者subpage是否可用;
    // 默认为  12   ;
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    // 双向队列;
    private final Deque<ByteBuffer> cachedNioBuffers;

    // 初始为chunksize,表示该chunk的可用空间大小;
    private int freeBytes;

    // 所属的chunklist;
    PoolChunkList<T> parent;
    // 前置节点;
    PoolChunk<T> prev;
    // 后置节点;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;

        // -----------------衍生变量-------------------

        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        // if maxOrder == 31 ; pageSize = 0;
        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;

        // -----------------初始化  memoryMap  depthMap  -------------------

        // Generate the memory map.
        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        // 创建  1 <<< maxorder  个subpage;
        subpages = newSubpageArray(maxSubpageAllocs);
        //
        cachedNioBuffers = new ArrayDeque<>(8);
    }

    /** Creates a special chunk that is not pooled. */
    // 创建一个特殊的非pool类型的chunk;
    // 用来分配大于chunksize(1 <<< 13)的空间的;
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    // 当前chunk的空间使用率, 100 % - (freeBytes / pageSize) * 100%;
    @Override
    public int usage() {
        final int freeBytes;
        // ???question:为什么要同步  arena???;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    // 计算使用占比 100 % - (freeBytes / pageSize) * 100%;
    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);

        // 99% ~ 100%;
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    // 分配空间;
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        final long handle;
        // 1. 如果申请的空间大小超过了pagesize的大小那么执行allocateRun;
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            handle =  allocateRun(normCapacity);
        } else {
            // 2.申请空间 < pagesize;
            handle = allocateSubpage(normCapacity);
        }

        // 3.handle小于0说明没有申请成功;
        if (handle < 0) {
            return false;
        }

        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     *
     * 更新父节点的 memoryMap[parentId] = childLeftVal1 < childLeftVal2 ? childLeftVal1 : childLeftVal2;
     * 循环执行到   root  节点;
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            // 父节点;
            int parentId = id >>> 1;
            // 本节点;
            byte val1 = value(id);
            // 兄弟节点;
            byte val2 = value(id ^ 1);
            // 更新为较小的那个;
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     *
     * 在深度d层查询空闲的node;
     */
    private int allocateNode(int d) {
        // 1. node 的起始id为1;
        int id = 1;
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        // 2.如果d  <  0那么是有问题的;
        if (val > d) { // unusable
            return -1;
        }
        // 3.循环直到找到 memoryMap[id] >= d && (id & initial) == 0 => 表示 id < (1 << d);
        // 找到合适的节点 此节点 一定是 memoryMap[id] == d;
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;
            val = value(id);
            if (val > d) {
                id ^= 1;
                val = value(id);
            }
        }
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        // 4.设置为不可用 设置为最大深度;
        setValue(id, unusable); // mark as unusable
        // 5.更新父节点信息;
        updateParentsAlloc(id);
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     *
     * 申请的空间大于 pagesize 的大小;
     */
    private long allocateRun(int normCapacity) {
        // 1.计算当前的申请空间大小的深度;
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        // 2.根据深度计算出第一个合适的node;
        int id = allocateNode(d);
        // 3.没有找到  <  0;
        if (id < 0) {
            return id;
        }
        // 4.更新可用空间大小freeBytes;
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     *
     * 申请空间小于pagesize的通过 PoolSubpage 来处理;
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        // 获取PoolArena拥有的PoolSubPage池的头部并在其上进行同步。这是需要的，因为我们可以将其添加回去，从而更改链接列表结构。
        // 因为可能会修改链表结构所以需要进行同步;
        // 1.查找对应的subpagepool;
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        synchronized (head) {
            // 2.subpage一定是叶子结点;
            int id = allocateNode(d);
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            // 3.更新可用空间大小;
            freeBytes -= pageSize;

            int subpageIdx = subpageIdx(id);
            PoolSubpage<T> subpage = subpages[subpageIdx];
            // 4.如果subpage为创建那么就创建;
            if (subpage == null) {
                subpage = new PoolSubpage<>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity);
            }
            // 5.申请操作;
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     *
     * 释放handle对应的空间,如果是subpage那么可能添加回对应elemsize大小的subpage pool,以便之后释放整个page和以后的分配工作;
     */
    void free(long handle, ByteBuffer nioBuffer) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);

        // bitmapIdx != 0说明要释放的是subpage;
        if (bitmapIdx != 0) { // free a subpage
            // 找到对应的subpage;
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                // ???question:为什么要小于0x3FFFFFFF,理论上肯定小于呀???;
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        // 非subpage的free;
        // 添加空闲空间大小;
        freeBytes += runLength(memoryMapIdx);
        // memoryMap[id]恢复原始深度值;
        setValue(memoryMapIdx, depth(memoryMapIdx));
        // 更新父节点深度值;
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    // init PooledByteBuf 给用户申请的buf开辟空间;
    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);
        // bitmapIdx == 0 说明申请的空间大小是大于pagesize的;
        if (bitmapIdx == 0) {
            // 返回 memoryMap[id] 的深度,如果深度大于unusable说明是不可用的已经被申请完了没有空间可申请了;
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            // 执行  PooledByteBuf  init;
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            // 给用户申请的buf开辟空间, 通过subpage;
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    // 给用户申请的buf开辟空间, 通过subpage;
    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    // 给用户申请的buf开辟空间, 通过subpage;
    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;
        // 计算memoryMapIdx;
        int memoryMapIdx = memoryMapIdx(handle);

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        // offset:
        buf.init(
            this, nioBuffer, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    // 获取该节点的实际深度值;
    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    // 计算该节点的大小 = 1 << (maxOrder - depthMap[id] + pageShifts);
    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    // 表示在同一深度层的偏移量,申请的空间在整个空间的偏移量;
    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        // 表示在同一深度层的偏移量,申请的空间在整个空间的偏移量;
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    // 防止memoryMapIdx越界, 将超出为置0;
    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    // 取handle低32位;
    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    // 取handle高32位;
    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    ///
    @Override
    public int chunkSize() {
        return chunkSize;
    }

    ///
    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
