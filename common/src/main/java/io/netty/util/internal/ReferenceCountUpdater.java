/*
 * Copyright 2019 The Netty Project
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
package io.netty.util.internal;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCounted;

/**
 * Common logic for {@link ReferenceCounted} implementations
 *
 * 引用计数的更新器;
 */
public abstract class ReferenceCountUpdater<T extends ReferenceCounted> {
    /*
     * Implementation notes:
     *
     * For the updated int field:
     *   Even => "real" refcount is (refCnt >>> 1) 偶数代表
     *   Odd  => "real" refcount is 0 奇数代表已经没有引用
     *
     * (x & y) appears to be surprisingly expensive relative to (x == y). Thus this class uses
     * a fast-path in some places for most common low values when checking for live (even) refcounts,
     * for example: if (rawCnt == 2 || rawCnt == 4 || (rawCnt & 1) == 0) { ...
     *
     * x & y这种与操作比 x == y这种要昂贵所以如果涉及判断最好可以通过 ==;
     *
     * 这里采用偶数来计算引用数,主要是因为这样可以确定没有引用了这种状态;
     * 最重要原因是简化了保证并发时引用计数的正确性;
     * 当采用加一减一的方式: 因为当引用数为0的时候,由于并发问题可能这个时候有增加引用数的情况导致引用应该不应该再增加了却增加了;
     * 而采用奇数偶数方式: 当因用数为1的时候,发生上面情况时虽然引用数增加了但引用数还是奇数不会发生任何影响;
     */

    protected ReferenceCountUpdater() { }

    // 获取类的属性的偏移量;
    public static long getUnsafeOffset(Class<? extends ReferenceCounted> clz, String fieldName) {
        try {
            if (PlatformDependent.hasUnsafe()) {
                return PlatformDependent.objectFieldOffset(clz.getDeclaredField(fieldName));
            }
        } catch (Throwable ignore) {
            // fall-back
        }
        return -1;
    }

    // 由子类来实现AtomicIntegerFieldUpdater;
    protected abstract AtomicIntegerFieldUpdater<T> updater();

    // 由子类来实现获取属性的offset;
    protected abstract long unsafeOffset();

    // 实际的引用数 = refcount >>> 1;
    public final int initialValue() {
        return 2;
    }

    // 获取真实的引用数:因为引用数为1和2的占大多数,所以先排除2和4,&操作比 == 昂贵, 如果为偶数那么>>>1;
    private static int realRefCnt(int rawCnt) {
        return rawCnt != 2 && rawCnt != 4 && (rawCnt & 1) != 0 ? 0 : rawCnt >>> 1;
    }

    /**
     * Like {@link #realRefCnt(int)} but throws if refCnt == 0
     *
     * 和上面方法相同但是会当refCnt == 0会抛异常;
     */
    private static int toLiveRealRefCnt(int rawCnt, int decrement) {
        if (rawCnt == 2 || rawCnt == 4 || (rawCnt & 1) == 0) {
            return rawCnt >>> 1;
        }
        // odd rawCnt => already deallocated
        throw new IllegalReferenceCountException(0, -decrement);
    }

    // 获取原生的RawCnt, NonVolatile表示不保证拿到的是最新的值;
    private int nonVolatileRawCnt(T instance) {
        // TODO: Once we compile against later versions of Java we can replace the Unsafe usage here by varhandles.
        final long offset = unsafeOffset();
        return offset != -1 ? PlatformDependent.getInt(instance, offset) : updater().get(instance);
    }

    // 获取真实引用数;
    public final int refCnt(T instance) {
        return realRefCnt(updater().get(instance));
    }

    // 判断该引用是否为0了,也就是该对象已经not live;
    public final boolean isLiveNonVolatile(T instance) {
        final int rawCnt = nonVolatileRawCnt(instance);

        // The "real" ref count is > 0 if the rawCnt is even.
        return rawCnt == 2 || rawCnt == 4 || rawCnt == 6 || rawCnt == 8 || (rawCnt & 1) == 0;
    }

    /**
     * An unsafe operation that sets the reference count directly
     *
     * 设置自定义的引用值;
     */
    public final void setRefCnt(T instance, int refCnt) {
        updater().set(instance, refCnt > 0 ? refCnt << 1 : 1); // overflow OK here
    }

    /**
     * Resets the reference count to 1
     *
     * 重置引用数为初始值2;
     */
    public final void resetRefCnt(T instance) {
        updater().set(instance, initialValue());
    }

    // 增加引用计数;
    public final T retain(T instance) {
        return retain0(instance, 1, 2);
    }

    public final T retain(T instance, int increment) {
        // all changes to the raw count are 2x the "real" change - overflow is OK
        int rawIncrement = checkPositive(increment, "increment") << 1;
        return retain0(instance, increment, rawIncrement);
    }

    // rawIncrement == increment << 1
    // 增加引用计数;
    private T retain0(T instance, final int increment, final int rawIncrement) {
        int oldRef = updater().getAndAdd(instance, rawIncrement);
        // 1.如果原始引用数为奇数那么说明已经对象已不可用,抛异常;
        if (oldRef != 2 && oldRef != 4 && (oldRef & 1) != 0) {
            throw new IllegalReferenceCountException(0, increment);
        }
        // don't pass 0!
        // 2.判断引用数是否越界了,超过了int范围;
        if ((oldRef <= 0 && oldRef + rawIncrement >= 0)
                || (oldRef >= 0 && oldRef + rawIncrement < oldRef)) {
            // overflow case
            updater().getAndAdd(instance, -rawIncrement);
            throw new IllegalReferenceCountException(realRefCnt(oldRef), increment);
        }
        return instance;
    }

    // 减少引用数;
    // 如果rawCnt = 2 那么进行最终释放,如果最终释放失败那么进行重试直到成功,如果rawCnt = 1了那么返回true;
    public final boolean release(T instance) {
        int rawCnt = nonVolatileRawCnt(instance);
        return rawCnt == 2 ? tryFinalRelease0(instance, 2) || retryRelease0(instance, 1)
                : nonFinalRelease0(instance, 1, rawCnt, toLiveRealRefCnt(rawCnt, 1));
    }

    // 释放指定数量,逻辑同上;
    public final boolean release(T instance, int decrement) {
        int rawCnt = nonVolatileRawCnt(instance);
        int realCnt = toLiveRealRefCnt(rawCnt, checkPositive(decrement, "decrement"));
        return decrement == realCnt ? tryFinalRelease0(instance, rawCnt) || retryRelease0(instance, decrement)
                : nonFinalRelease0(instance, decrement, rawCnt, realCnt);
    }

    // 尝试最终的释放,也就是将rawCut置为1;
    private boolean tryFinalRelease0(T instance, int expectRawCnt) {
        return updater().compareAndSet(instance, expectRawCnt, 1); // any odd number will work
    }

    // 普通释放 dec < realCnt;
    private boolean nonFinalRelease0(T instance, int decrement, int rawCnt, int realCnt) {
        if (decrement < realCnt
                // all changes to the raw count are 2x the "real" change - overflow is OK
                && updater().compareAndSet(instance, rawCnt, rawCnt - (decrement << 1))) {
            return false;
        }

        // 失败重试;
        return retryRelease0(instance, decrement);
    }

    // 重试释放知道释放成功;
    private boolean retryRelease0(T instance, int decrement) {
        for (;;) {
            // 1.获取当前rawCut和realCnt;
            int rawCnt = updater().get(instance), realCnt = toLiveRealRefCnt(rawCnt, decrement);
            // 2.相等的情况要全部释放了需要进行最终释放处理;
            if (decrement == realCnt) {
                if (tryFinalRelease0(instance, rawCnt)) {
                    return true;
                }
            // 3.正常情况正常减;
            } else if (decrement < realCnt) {
                // all changes to the raw count are 2x the "real" change
                if (updater().compareAndSet(instance, rawCnt, rawCnt - (decrement << 1))) {
                    return false;
                }
            // 4.decrement > realCnt,异常情况;
            } else {
                throw new IllegalReferenceCountException(realCnt, -decrement);
            }

            // 防止占用cpu资源过高的一种安慰;
            Thread.yield(); // this benefits throughput under high contention
        }
    }
}
