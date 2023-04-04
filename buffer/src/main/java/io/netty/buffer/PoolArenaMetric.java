/*
 * Copyright 2015 The Netty Project
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

import java.util.List;

/**
 * Expose metrics for an arena.
 *
 * arena的可对外公开的一些指标,通过该接口方法进行访问;
 */
public interface PoolArenaMetric {

    /**
     * Returns the number of thread caches backed by this arena.
     *
     * arena支持的线程缓存数;
     */
    int numThreadCaches();

    /**
     * Returns the number of tiny sub-pages for the arena.
     *
     * 返回arena的tiny级别的页的数量;
     */
    int numTinySubpages();

    /**
     * Returns the number of small sub-pages for the arena.
     *
     * 返回arena的small级别的页的数量;
     */
    int numSmallSubpages();

    /**
     * Returns the number of chunk lists for the arena.
     *
     * 返回arena的chunk的数量;
     */
    int numChunkLists();

    /**
     * Returns an unmodifiable {@link List} which holds {@link PoolSubpageMetric}s for tiny sub-pages.
     *
     * 返回arena的tiny级别的页;
     */
    List<PoolSubpageMetric> tinySubpages();

    /**
     * Returns an unmodifiable {@link List} which holds {@link PoolSubpageMetric}s for small sub-pages.
     *
     * 返回arena的small级别的页;
     */
    List<PoolSubpageMetric> smallSubpages();

    /**
     * Returns an unmodifiable {@link List} which holds {@link PoolChunkListMetric}s.
     *
     * 返回arena的所有chunks,并且是不可修改的;
     */
    List<PoolChunkListMetric> chunkLists();

    /**
     * Return the number of allocations done via the arena. This includes all sizes.
     *
     * 返回经由arena分配的数量;
     */
    long numAllocations();

    /**
     * Return the number of tiny allocations done via the arena.
     *
     * 返回经由arena分配的tiny级别数量;
     */
    long numTinyAllocations();

    /**
     * Return the number of small allocations done via the arena.
     *
     * 返回经由arena分配的small级别数量;
     */
    long numSmallAllocations();

    /**
     * Return the number of normal allocations done via the arena.
     *
     * 返回经由arena分配的normal级别数量;
     */
    long numNormalAllocations();

    /**
     * Return the number of huge allocations done via the arena.
     *
     * 返回经由arena分配的huge级别数量;
     */
    long numHugeAllocations();

    /**
     * Return the number of deallocations done via the arena. This includes all sizes.
     *
     * 返回经由arena取消分配的数量;
     */
    long numDeallocations();

    /**
     * Return the number of tiny deallocations done via the arena.
     *
     * 返回经由arena分配的tiny级别数量;
     */
    long numTinyDeallocations();

    /**
     * Return the number of small deallocations done via the arena.
     *
     * 返回经由arena分配的small级别数量;
     */
    long numSmallDeallocations();

    /**
     * Return the number of normal deallocations done via the arena.
     *
     * 返回经由arena分配的normal级别数量;
     */
    long numNormalDeallocations();

    /**
     * Return the number of huge deallocations done via the arena.
     *
     * 返回经由arena分配的huge级别数量;
     */
    long numHugeDeallocations();

    /**
     * Return the number of currently active allocations.
     *
     * 返回当前活动分配数;
     */
    long numActiveAllocations();

    /**
     * Return the number of currently active tiny allocations.
     *
     * 返回当前活动tiny级别的分配数;
     */
    long numActiveTinyAllocations();

    /**
     * Return the number of currently active small allocations.
     *
     * 返回当前活动small级别的分配数;
     */
    long numActiveSmallAllocations();

    /**
     * Return the number of currently active normal allocations.
     *
     * 返回当前活动normal级别的分配数;
     */
    long numActiveNormalAllocations();

    /**
     * Return the number of currently active huge allocations.
     *
     * 返回当前活动huge级别的分配数;
     */
    long numActiveHugeAllocations();

    /**
     * Return the number of active bytes that are currently allocated by the arena.
     *
     * 返回arena分配的活跃的字节数;
     */
    long numActiveBytes();
}
