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

import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.lang.Math.max;

abstract class PoolArena<T> implements PoolArenaMetric {
    // 是否支持unsafe操作,可以访问堆外;
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    // 三种级别的枚举;
    enum SizeClass {
        Tiny, //  < 512
        Small, // 256 <= && < pagesize
        Normal // >= pagesize
    }

    // tiny级别的页的个数为 512 / 16 = 32个;
    static final int numTinySubpagePools = 512 >>> 4;

    // arena所属的池分配器;
    final PooledByteBufAllocator parent;
    // 表示一个chunk包含了 1 << maxOrder个pages;
    private final int maxOrder;
    // 每个page的大小;
    final int pageSize;
    // pagesize = 1 << pageShifts;
    final int pageShifts;
    // chunksize = pagesize << maxOrder;
    final int chunkSize;
    // 用来判断是否大于pagesize的;
    final int subpageOverflowMask;
    final int numSmallSubpagePools;
    // 不等于零说明申请堆外内存需要进行规范化,也就是大小规范为directMemoryCacheAlignment的整数倍;
    final int directMemoryCacheAlignment;
    final int directMemoryCacheAlignmentMask;
    // 用来专门分配tiny级别的内存请求的;
    // 总共有32个,第 i 个负责 : 16 * i ~  16 * (i + 1);
    private final PoolSubpage<T>[] tinySubpagePools;
    // 用来专门分配small级别的内存请求的;
    // 总共有4个,第 i 个负责 : 512 << i ~  512 << (i + 1);
    private final PoolSubpage<T>[] smallSubpagePools;
    // 以下6种用来分配内存三种类型均可;
    // 负责使用率 50% ~ 100%的chunks;
    private final PoolChunkList<T> q050;
    // 负责使用率 25% ~ 75%的chunks;
    private final PoolChunkList<T> q025;
    // 负责使用率 1% ~ 50%的chunks;
    private final PoolChunkList<T> q000;
    // 负责使用率 0% ~ 25%的chunks;
    private final PoolChunkList<T> qInit;
    // 负责使用率 75% ~ 100%的chunks;
    private final PoolChunkList<T> q075;
    // 负责使用率 100% ~ 的chunks;
    private final PoolChunkList<T> q100;
    // 上面6个的获取指标的list;
    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    // 分配tiny级别的空间的次数;
    private long allocationsNormal;
    // We need to use the LongAdder here as this is not guarded via synchronized block.
    // 采用long add 加法器不需要同步块来防止并发, 如果用atomicInteger 来实现还是要自己去控制而且光靠cas操作再高并发情况下效率并不高;
    // 分配tiny级别的空间的次数;
    private final LongAdder allocationsTiny = new LongAdder();
    // 分配small级别的空间的次数;
    private final LongAdder allocationsSmall = new LongAdder();
    // 分配huge(> chunksize)级别的空间的个数;
    private final LongAdder allocationsHuge = new LongAdder();
    // 分配出去的huge类型空间的总大小;
    private final LongAdder activeBytesHuge = new LongAdder();

    // 回收tiny级别的空间的次数;
    private long deallocationsTiny;
    // 回收small级别的空间的次数;
    private long deallocationsSmall;
    // 回收normal级别的空间的次数;
    private long deallocationsNormal;

    // We need to use the LongAdder here as this is not guarded via synchronized block.
    // 回收huge级别的空间的次数;
    private final LongAdder deallocationsHuge = new LongAdder();

    // Number of thread caches backed by this arena.
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    // padding 是用来解决cpu伪缓存问题;
    private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        this.parent = parent;
        this.pageSize = pageSize;
        this.maxOrder = maxOrder;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        directMemoryCacheAlignment = cacheAlignment;
        directMemoryCacheAlignmentMask = cacheAlignment - 1;
        subpageOverflowMask = ~(pageSize - 1);
        // 创建32个用来分配tiny级别的buf的subpage;
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);
        for (int i = 0; i < tinySubpagePools.length; i ++) {
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        numSmallSubpagePools = pageShifts - 9;
        // 创建 13 - 9 = 4个用来分配small级别的buf的subpage;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        {  // 构建chunk list;
            q100 = new PoolChunkList<>(this, null, 100, Integer.MAX_VALUE, chunkSize);
            q075 = new PoolChunkList<>(this, q100, 75, 100, chunkSize);
            q050 = new PoolChunkList<>(this, q075, 50, 100, chunkSize);
            q025 = new PoolChunkList<>(this, q050, 25, 75, chunkSize);
            q000 = new PoolChunkList<>(this, q025, 1, 50, chunkSize);
            qInit = new PoolChunkList<>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

            q100.prevList(q075);
            q075.prevList(q050);
            q050.prevList(q025);
            q025.prevList(q000);
            q000.prevList(null);
            qInit.prevList(qInit);
        }

        {  // 构建指标;
            List<PoolChunkListMetric> metrics = new ArrayList<>(6);
            metrics.add(qInit);
            metrics.add(q000);
            metrics.add(q025);
            metrics.add(q050);
            metrics.add(q075);
            metrics.add(q100);
            chunkListMetrics = Collections.unmodifiableList(metrics);
        }

    }

    // 创建subpage的head;
    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<>(pageSize);
        head.prev = head;
        head.next = head;
        return head;
    }

    //
    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    // 返回内存类型;
    abstract boolean isDirect();

    // 申请空间;
    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }


    // 除以16查找到对应的index [0, 31];
    static int tinyIdx(int normCapacity) {
        return normCapacity >>> 4;
    }

    // 1 <<< 10 的 2的整数次幂的倍数 [0, 3];
    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx ++;
        }
        return tableIdx;
    }

    // capacity < pageSize
    // 判断是否小于pagesize;
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    // 判断是否小于512;
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    // 申请;
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        // 1.规范化reqCapacity;
        final int normCapacity = normalizeCapacity(reqCapacity);
        // 2.是否是tiny or small类型空间申请;
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            int tableIdx;
            PoolSubpage<T>[] table;
            boolean tiny = isTiny(normCapacity);
            if (tiny) { // < 512
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                // 3.根据申请的空间大小来查找到对应的subpage的head;
                tableIdx = tinyIdx(normCapacity);
                table = tinySubpagePools;
            } else {
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                // 4.根据申请的空间大小来查找到对应的subpage的head;
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;
            }

            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             */
            synchronized (head) {
                final PoolSubpage<T> s = head.next;
                // 5.如果不相等说明该head的link的节点数大于1,那么可以进行分配,分配失败再交给chunk来分配,并同时将新建的节点添加到该link;
                if (s != head) {
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    long handle = s.allocate();
                    assert handle >= 0;
                    // 6.开辟空间;
                    s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity);
                    incTinySmallAllocation(tiny);
                    return;
                }
            }
            // 7.保证chunk操作的线程安全;
            synchronized (this) {
                // 8.通过chunk来进行分配;
                allocateNormal(buf, reqCapacity, normCapacity);
            }

            // ???question:为啥不放到同步块里,而是用longadder呢???
            incTinySmallAllocation(tiny);
            return;
        }
        // 9.分配的空间在pagesize ~ chunksize;
        if (normCapacity <= chunkSize) {
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }
        } else {
            // Huge allocations are never served via the cache so just call allocateHuge
            // 10.申请超过chunksize的空间;
            allocateHuge(buf, reqCapacity);
        }
    }

    // Method must be called inside synchronized(this) { ... } block
    // 该方法必须在同步块里,因为chunk是非线程安全的;
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        // 这里为了权衡先用50%的来分配空间,这种顺序可以保证大空间可以申请到并且可以减少chunk的数量;
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
            q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
            q075.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }

        // Add a new chunk.
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        boolean success = c.allocate(buf, reqCapacity, normCapacity);
        assert success;
        qInit.add(c);
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            allocationsTiny.increment();
        } else {
            allocationsSmall.increment();
        }
    }

    // 申请 > chunksize的空间;
    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    // 释放空间;
    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        // > chunksize的非pool类型的;
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            // 直接进行销毁即可,非池化的;
            destroyChunk(chunk);
            // 更新huge类型的状态;
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            SizeClass sizeClass = sizeClass(normCapacity);
            // 先紧着放到cache里,然后cache满了再回收到chunk里;
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }

            // 回收到chunk里;
            freeChunk(chunk, handle, sizeClass, nioBuffer, false);
        }
    }

    // 返回size对应的大小的类型;
    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    // 通过chunk释放 pool类型的;
    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass, ByteBuffer nioBuffer, boolean finalizer) {
        final boolean destroyChunk;
        synchronized (this) {
            // We only call this if freeChunk is not called because of the PoolThreadCache finalizer as otherwise this
            // may fail due lazy class-loading in for example tomcat.
            // finalizer 表示是否是gc时执行finalize()函数调用的,但是因为类延时加载可能会导致SizeClass还没有加载导致方法执行报错,导致回收失败;
            // ???question:???
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    case Tiny:
                        ++deallocationsTiny;
                        break;
                    default:
                        throw new Error();
                }
            }
            destroyChunk = !chunk.parent.free(chunk, handle, nioBuffer);
        }
        // chunk回收失败直接进行销毁;
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    // 通过elemsize来获取对应的pool的head;
    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx;
        PoolSubpage<T>[] table;
        if (isTiny(elemSize)) { // < 512
            // 除以  16,参考tinySubpagePools的注释;
            tableIdx = elemSize >>> 4;
            table = tinySubpagePools;
        } else {
            tableIdx = 0;
            elemSize >>>= 10;
            // 参考smallSubpagePools的注释;
            while (elemSize != 0) {
                elemSize >>>= 1;
                tableIdx ++;
            }
            table = smallSubpagePools;
        }

        return table[tableIdx];
    }

    // 规范化请求的空间大小,因为如果我们假设一下没有规范化会怎么样:;
    // 情况一:缓冲空间的管理还是规范化的,每个subpage都是规范的,那么就会导致内存的利用率不是100%;
    // 情况二:如果缓冲空间是非规范化管理的,那么就要维护被申请的空间的起始索引和终点索引,当空间被释放时就要维护一个可用的零散的起始终点的索引段,那么就会导致维护成本太高很杂乱;
    int normalizeCapacity(int reqCapacity) {
        // 1.判断申请空间是否大于零;
        checkPositiveOrZero(reqCapacity, "reqCapacity");

        // 2.如果申请空间大于chunk的大小,那么进行对其处理;
        if (reqCapacity >= chunkSize) {
            // 堆外内存是否要进行size规范化;
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }

        // 3.如果 >= 512,如果reqCapacity不是512的2的整数倍那么进行补齐如果溢出了那么就向下补齐申请的空间会不够;
        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled

            // 补1操作;
            int normalizedCapacity = reqCapacity;
            normalizedCapacity --;
            normalizedCapacity |= normalizedCapacity >>>  1;
            normalizedCapacity |= normalizedCapacity >>>  2;
            normalizedCapacity |= normalizedCapacity >>>  4;
            normalizedCapacity |= normalizedCapacity >>>  8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity ++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;

            return normalizedCapacity;
        }

        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);
        }

        // Quantum-spaced
        // 如果 < 512那么就补齐16的整数倍;
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        return (reqCapacity & ~15) + 16;
    }

    // 相当于如果reqCapacity不是directMemoryCacheAlignment的整数倍那么:
    // delta = reqCapacity % directMemoryCacheAlignment, reqCapacity + directMemoryCacheAlignment - delta = directMemoryCacheAlignment的整数倍;
    int alignCapacity(int reqCapacity) {
        int delta = reqCapacity & directMemoryCacheAlignmentMask;
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;
    }

    // 重新分配;
    // 用于buf修改容量时;
    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        assert newCapacity >= 0 && newCapacity <= buf.maxCapacity();

        int oldCapacity = buf.length;
        // 如果更改的新的容量的大小和原始大小相同那么还是用原来的buf;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;

        // This does not touch buf's reader/writer indices
        // 重新分配;
        allocate(parent.threadCache(), buf, newCapacity);
        int bytesToCopy;
        if (newCapacity > oldCapacity) {
            bytesToCopy = oldCapacity;
        } else {
            // 调整buf读写索引根据新的容量;
            buf.trimIndicesToCapacity(newCapacity);
            bytesToCopy = newCapacity;
        }
        // 将旧的内容copy到新分配的空间;
        memoryCopy(oldMemory, oldOffset, buf, bytesToCopy);
        if (freeOldMemory) {
            // 释放旧的buf的空间;
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    // 返回所有subpagepool里的subpage的指标;
    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            do {
                metrics.add(s);
                s = s.next;
            } while (s != head);
        }
        return metrics;
    }

    // ------------------------------------------ 获取指标的方法  start----------------------------------------------

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.longValue() + allocationsSmall.longValue() + allocsNormal + allocationsHuge.longValue();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.longValue();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.longValue();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.longValue();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.longValue();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.longValue();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsTiny.longValue() + allocationsSmall.longValue() + allocationsHuge.longValue()
                - deallocationsHuge.longValue();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.longValue();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    // ------------------------------------------ 获取指标的方法  end----------------------------------------------

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, PooledByteBuf<T> dst, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
           .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    // toString();
    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            do {
                buf.append(s);
                s = s.next;
            } while (s != head);
        }
    }

    // Gc 处理,但是该方法不一定执行,所以资源的销毁需要用户自行掌控;
    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolSubPages(tinySubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    // 销毁资源尤其是堆外内存;
    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    // 销毁chunklist;
    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    // 堆arena;
    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, PooledByteBuf<byte[]> dst, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst.memory, dst.offset, length);
        }
    }

    // 堆外arena;
    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        // mark as package-private, only for unit test
        int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            int remainder = HAS_UNSAFE
                    ? (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask)
                    : 0;

            // offset = alignment - address & (alignment - 1)
            return directMemoryCacheAlignment - remainder;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder,
                int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<>(this,
                        allocateDirect(chunkSize), pageSize, maxOrder,
                        pageShifts, chunkSize, 0);
            }
            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<>(this, memory, pageSize,
                    maxOrder, pageShifts, chunkSize,
                    offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        private static ByteBuffer allocateDirect(int capacity) {
            // ???question:???
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        // full gc时会进行堆外内存回收但是fullgc是不可控的,fullgc之前可能会内存泄露溢出之类,需要主动释放;
        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        // bytebuffer;
        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, PooledByteBuf<ByteBuffer> dstBuf, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dstBuf.memory) + dstBuf.offset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                ByteBuffer dst = dstBuf.internalNioBuffer();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstBuf.offset);
                dst.put(src);
            }
        }
    }
}
