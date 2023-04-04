/*
 * Copyright 2013 The Netty Project
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

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 *
 * 对象回收器;
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    // 把手;
    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = object -> {
        // NOOP
    };
    // 回收器的唯一标识种子;
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    // 回收器的唯一标识;
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    // 默认初始大小;
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    // 默认最大值;
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    // 回收栈的初始容量;
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    private static final int LINK_CAPACITY;
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        // cpu核数的二倍;
        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        // 快速获取大于等于该值的2的次幂的数;
        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        // 当对象回收时,不是每个都回收,默认每8个回收一个,这样保证了回收栈不会增长很快;
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    // 每个线程存储的回收对象容量最大值,为0表示不会回收对象,不过是可以给用户程序提供新建的对象的只不过这个对象不会被回收;
    private final int maxCapacityPerThread;
    // 回收栈的共享区域的占比;
    private final int maxSharedCapacityFactor;
    private final int interval;
    private final int maxDelayedQueuesPerThread;
    // 用来将回收对象按线程区分来存储,这样的好处是可以防止并发问题,是线程安全的,但用户怎么使用就是用户的事了;
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        interval = safeFindNextPositivePowerOfTwo(ratio);
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    // 从回收器里获取一个对象;
    @SuppressWarnings("unchecked")
    public final T get() {
        // 1.等于零说明不做回收处理;
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        // 2.获取该线程的回收栈;
        Stack<T> stack = threadLocal.get();
        DefaultHandle<T> handle = stack.pop();
        // 3.回收栈为空的话,新建对象和handle;
        if (handle == null) {
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        // 4.不为空返回回收过的对象;
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     *
     * 回收,回收操作可以通过回收器来完成,也可以通过handle来完成;
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        // 1.如果handle是无效的那么直接返回false;
        if (handle == NOOP_HANDLE) {
            return false;
        }

        // 2.如果这个handle不属于该回收器那么也不会处理;
        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        // 3.进行对象回收;
        h.recycle(o);
        return true;
    }

    // 返回回收栈的容量;
    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    // 返回回收栈的可用大小;
    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    // 新建对象的逻辑;
    protected abstract T newObject(Handle<T> handle);

    // Handle 继承的ObjectPool里的;
    public interface Handle<T> extends ObjectPool.Handle<T>  { }

    // 默认的handle;
    private static final class DefaultHandle<T> implements Handle<T> {
        // 初始为0,表示最后回收这个对象的回收器的id;
        int lastRecycledId;
        // 初始为0,表示如果被回收过那就是当前回收这个对象的回收器的id,这两个值如果不为0说明被回收过;
        int recycleId;

        // 是否回收了?
        boolean hasBeenRecycled;

        // 回收对象的存储栈;
        Stack<?> stack;
        // 要回收和使用的对象;
        Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        // 回收;
        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            // 1.判断是否已回收???question:why != ???
            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }

            // 放入回收栈里;
            stack.push(this);
        }
    }

    // 延迟回收;
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    // 一种队列，它只对可见性作适度的保证：项目按正确的顺序显示，但我们不能绝对保证看到任何东西，因此保持队列的维护成本很低
    // 用于当前线程回收其他线程回收过的对象,如果不用这个还用handle的stack去回收那么就有可能同时多个线程拥有了这个对象的引用;
    // ???questtion:但是为什么要用这个呢,完全可以在回收逻辑里发现handle的stack的线程和当前线程不同,就get当前线程的stack,把这个对象回收
    // 到这里,然后更新对应handle的stack???
    private static final class WeakOrderQueue extends WeakReference<Thread> {

        // 占位用的,一个无效的woq;
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        // 很巧妙,这个是用来存储回收的对象的,它本身的值就是当前写入的index;
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            int readIndex;
            Link next;
        }

        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        // 重要的是，这不包含对Stack或WeakOrderQueue的任何引用。
        // ???question:没看完???
        private static final class Head {
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * Reclaim all used space and also unlink the nodes to prevent GC nepotism.
             */
            void reclaimAllSpaceAndUnlink() {
                Link head = link;
                link = null;
                int reclaimSpace = 0;
                while (head != null) {
                    reclaimSpace += LINK_CAPACITY;
                    Link next = head.next;
                    // Unlink to help GC and guard against GC nepotism.
                    head.next = null;
                    head = next;
                }
                if (reclaimSpace > 0) {
                    reclaimSpace(reclaimSpace);
                }
            }

            private void reclaimSpace(int space) {
                availableSharedCapacity.addAndGet(space);
            }

            void relink(Link link) {
                reclaimSpace(LINK_CAPACITY);
                this.link = link;
            }

            /**
             * Creates a new {@link} and returns it if we can reserve enough space for it, otherwise it
             * returns {@code null}.
             */
            Link newLink() {
                return reserveSpaceForLink(availableSharedCapacity) ? new Link() : null;
            }

            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                for (;;) {
                    int available = availableSharedCapacity.get();
                    if (available < LINK_CAPACITY) {
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet(available, available - LINK_CAPACITY)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        // 回收对象链;
        private final Head head;
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        // 指向同一堆栈的另一个延迟项队列的指针
        private WeakOrderQueue next;
        // id;
        private final int id = ID_GENERATOR.getAndIncrement();
        // 周期和satck的作用相同;
        private final int interval;
        // 略;
        private int handleRecycleCount;

        private WeakOrderQueue() {
            super(null);
            head = new Head(null);
            interval = 0;
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            super(thread);
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            interval = stack.interval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            // 如果stack的可用共享空间小于link的大小那么返回null;
            if (!Head.reserveSpaceForLink(stack.availableSharedCapacity)) {
                return null;
            }

            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);

            return queue;
        }

        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        void reclaimAllSpaceAndUnlink() {
            head.reclaimAllSpaceAndUnlink();
            this.next = null;
        }

        // 共享队列添加元素;
        void add(DefaultHandle<?> handle) {
            // 1.handle的回收器id置为该队列的id;
            handle.lastRecycledId = id;

            // While we also enforce the recycling ratio one we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control if the Stack
            // 2.和stack的添加频率作用相同,未到达频率直接舍弃掉;
            if (handleRecycleCount < interval) {
                handleRecycleCount++;
                // Drop the item to prevent recycling to aggressive.
                return;
            }
            handleRecycleCount = 0;

            Link tail = this.tail;
            int writeIndex;
            // 3.判断这个link是否使用完了,如果使用完了那么就再在尾部再添加一个handle;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                Link link = head.newLink();
                if (link == null) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = link;

                writeIndex = tail.get();
            }
            // 4.将handle放入;
            tail.elements[writeIndex] = handle;
            // 5.将handle的stack置为null,也就表示该handle无法再被回收了;
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            // 通过使用lazyset来保证该操作之前的写入操作不会和它发生重排序,后面的可以,这样就保证了stack先为null然后writeIndex才增加;
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            Link head = this.head.link;
            if (head == null) {
                return false;
            }

            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                head = head.next;
                this.head.relink(head);
            }

            final int srcStart = head.readIndex;
            int srcEnd = head.get();
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;

            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle<?> element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;

                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.relink(head.next);
                }

                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }

    // 用来存储回收的对象;
    private static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        // 上面的这段注释说的是,每个线程维护一个回收栈,可以防止并发问题;
        // parent表示这个回收栈属于那个回收器的;
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        // 上面注释的意思是如果线程死了,这个地方不要影响线程被回收,所以使用弱引用;
        final WeakReference<Thread> threadRef;
        // 共享区域的大小;
        final AtomicInteger availableSharedCapacity;
        // 延迟队列的最大数量;
        private final int maxDelayedQueues;

        // 回收栈的最大容量;
        private final int maxCapacity;
        // 回收栈回收频率,不是每个对象都会被回收,每过interval个才会回收一个,保证了回收站的增长不会很大;
        private final int interval;
        // 回收栈的存储地;
        DefaultHandle<?>[] elements;
        // 可用空间大小;
        int size;
        // 回收个数的计数器;
        private int handleRecycleCount;
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues) {
            this.parent = parent;
            threadRef = new WeakReference<>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.interval = interval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        // 线程安全的setHead;
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        // 扩容;
        int increaseCapacity(int expectedCapacity) {
            // 1.根据原大小和最大容量限制计算出刚好大于等于expectedCapacity的2的整数次幂的倍数;
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            // 2.根据扩容后的大小新建一个数组,并将原数组的数据copy到新数组;
            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            // 3.返回新数组;
            return newCapacity;
        }

        // 出栈操作;
        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            // 1.如果可用空间大小等于零,那么进行回收操作,回收也失败那么返回null;
            int size = this.size;
            if (size == 0) {
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
                if (size <= 0) {
                    // double check, avoid races
                    return null;
                }
            }
            // 2.可用大小减一,获取该位置的handle,然后将该位置置为null;
            size --;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            // As we already set the element[size] to null we also need to store the updated size before we do
            // any validation. Otherwise we may see a null value when later try to pop again without a new element
            // added before.
            // 3.更新可用空间大小;
            this.size = size;

            // 4.可能被多个回收器回收过;
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }

            // 5.重置为未回收状态;
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            return ret;
        }

        // 清除;
        private boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        private boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                cursor = head;
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.getNext();
                if (cursor.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        // Ensure we reclaim all space before dropping the WeakOrderQueue to be GC'ed.
                        cursor.reclaimAllSpaceAndUnlink();
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        // 入栈;
        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                // 如果stack属于当前线程那么直接进行push操作;
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                // 如果stack不属于当前线程或者拥有该stack的线程已经被gc回收了;
                pushLater(item, currentThread);
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            // 1.如果handle的这两个属性有一个不为0说明这个handle有问题,失效了不再处理;
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            // 2.将该回收器的id赋值给这两个值;
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            // 3.如果可用空间没了,或者还没到回收的周期那么就忽略掉;
            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }

            // 4.如果当前回收栈没有对象了,那么就进行缩容为原来的二分之一;
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            // 5.将回收的元素放入回收栈;
            elements[size] = item;
            this.size = size + 1;
        }

        // 理解weakorderqueue的关键;
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // 1.如果延迟队列大小为0,那么不支持跨线程回收;
            if (maxDelayedQueues == 0) {
                // We don't support recycling across threads and should just drop the item on the floor.
                return;
            }

            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            // ???question:上面注释???
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                // 2.如果延迟队列个数已经超过最大,那么就放入一个内置的占位的即可,并不可用;
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // 3.新建一个延迟队列;
                if ((queue = newWeakOrderQueue(thread)) == null) {
                    // drop object
                    return;
                }
                //  4.存储该stack和queue;
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            // 5.将对象放入共享队列里;
            queue.add(item);
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue(this, thread);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the object.
                    return true;
                }
                handleRecycleCount = 0;
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<>(this);
        }
    }
}
