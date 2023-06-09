/*
 * Copyright 2016 The Netty Project
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

package io.netty.util.concurrent;

import org.junit.Test;

import java.security.Permission;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DefaultThreadFactoryTest {
    @Test(timeout = 2000)
    public void testDescendantThreadGroups() throws InterruptedException {
        final SecurityManager current = System.getSecurityManager();

        try {
            // install security manager that only allows parent thread groups to mess with descendant thread groups
            System.setSecurityManager(new SecurityManager() {
                @Override
                public void checkAccess(ThreadGroup g) {
                    final ThreadGroup source = Thread.currentThread().getThreadGroup();

                    if (source != null) {
                        if (!source.parentOf(g)) {
                            throw new SecurityException("source group is not an ancestor of the target group");
                        }
                        super.checkAccess(g);
                    }
                }

                // so we can restore the security manager at the end of the test
                @Override
                public void checkPermission(Permission perm) {
                }
            });

            // holder for the thread factory, plays the role of a global singleton
            final AtomicReference<DefaultThreadFactory> factory = new AtomicReference<>();
            final AtomicInteger counter = new AtomicInteger();
            final Runnable task = counter::incrementAndGet;

            final AtomicReference<Throwable> interrupted = new AtomicReference<>();

            // create the thread factory, since we are running the thread group brother, the thread
            // factory will now forever be tied to that group
            // we then create a thread from the factory to run a "task" for us
            final Thread first = new Thread(new ThreadGroup("brother"), () -> {
                factory.set(new DefaultThreadFactory("test", false, Thread.NORM_PRIORITY, null));
                final Thread t = factory.get().newThread(task);
                t.start();
                try {
                    t.join();
                } catch (InterruptedException e) {
                    interrupted.set(e);
                    Thread.currentThread().interrupt();
                }
            });
            first.start();
            first.join();

            assertNull(interrupted.get());

            // now we will use factory again, this time from a sibling thread group sister
            // if DefaultThreadFactory is "sticky" about thread groups, a security manager
            // that forbids sibling thread groups from messing with each other will strike this down
            final Thread second = new Thread(new ThreadGroup("sister"), () -> {
                final Thread t = factory.get().newThread(task);
                t.start();
                try {
                    t.join();
                } catch (InterruptedException e) {
                    interrupted.set(e);
                    Thread.currentThread().interrupt();
                }
            });
            second.start();
            second.join();

            assertNull(interrupted.get());

            assertEquals(2, counter.get());
        } finally {
            System.setSecurityManager(current);
        }
    }

    // test that when DefaultThreadFactory is constructed with a sticky thread group, threads
    // created by it have the sticky thread group
    @Test(timeout = 2000)
    public void testDefaultThreadFactoryStickyThreadGroupConstructor() throws InterruptedException {
        final ThreadGroup sticky = new ThreadGroup("sticky");
        runStickyThreadGroupTest(
                () -> new DefaultThreadFactory("test", false, Thread.NORM_PRIORITY, sticky),
                sticky);
    }

    // test that when DefaultThreadFactory is constructed it is sticky to the thread group from the thread group of the
    // thread that created it
    @Test(timeout = 2000)
    public void testDefaultThreadFactoryInheritsThreadGroup() throws InterruptedException {
        final ThreadGroup sticky = new ThreadGroup("sticky");

        runStickyThreadGroupTest(
                () -> {
                    final AtomicReference<DefaultThreadFactory> factory =
                            new AtomicReference<>();
                    final Thread thread = new Thread(sticky, () -> factory.set(new DefaultThreadFactory("test")));
                    thread.start();
                    thread.join();
                    return factory.get();
                },
                sticky);
    }

    // test that when a security manager is installed that provides a ThreadGroup, DefaultThreadFactory inherits from
    // the security manager
    @Test(timeout = 2000)
    public void testDefaultThreadFactoryInheritsThreadGroupFromSecurityManager() throws InterruptedException {
        final SecurityManager current = System.getSecurityManager();

        try {
            final ThreadGroup sticky = new ThreadGroup("sticky");
            System.setSecurityManager(new SecurityManager() {
                @Override
                public ThreadGroup getThreadGroup() {
                    return sticky;
                }

                // so we can restore the security manager at the end of the test
                @Override
                public void checkPermission(Permission perm) {
                }
            });
            runStickyThreadGroupTest(
                    () -> new DefaultThreadFactory("test"),
                    sticky);
        } finally {
            System.setSecurityManager(current);
        }
    }

    private static void runStickyThreadGroupTest(
            final Callable<DefaultThreadFactory> callable,
            final ThreadGroup expected) throws InterruptedException {
        final AtomicReference<ThreadGroup> captured = new AtomicReference<>();
        final AtomicReference<Throwable> exception = new AtomicReference<>();

        final Thread first = new Thread(new ThreadGroup("wrong"), () -> {
            final DefaultThreadFactory factory;
            try {
                factory = callable.call();
            } catch (Exception e) {
                exception.set(e);
                throw new RuntimeException(e);
            }
            final Thread t = factory.newThread(() -> {
            });
            captured.set(t.getThreadGroup());
        });
        first.start();
        first.join();

        assertNull(exception.get());

        assertEquals(expected, captured.get());
    }

    // test that when DefaultThreadFactory is constructed without a sticky thread group, threads
    // created by it inherit the correct thread group
    @Test(timeout = 2000)
    public void testDefaultThreadFactoryNonStickyThreadGroupConstructor() throws InterruptedException {

        final AtomicReference<DefaultThreadFactory> factory = new AtomicReference<>();
        final AtomicReference<ThreadGroup> firstCaptured = new AtomicReference<>();

        final ThreadGroup firstGroup = new ThreadGroup("first");
        final Thread first = new Thread(firstGroup, () -> {
            factory.set(new DefaultThreadFactory("sticky", false, Thread.NORM_PRIORITY, null));
            final Thread t = factory.get().newThread(() -> {
            });
            firstCaptured.set(t.getThreadGroup());
        });
        first.start();
        first.join();

        assertEquals(firstGroup, firstCaptured.get());

        final AtomicReference<ThreadGroup> secondCaptured = new AtomicReference<>();

        final ThreadGroup secondGroup = new ThreadGroup("second");
        final Thread second = new Thread(secondGroup, () -> {
            final Thread t = factory.get().newThread(() -> {
            });
            secondCaptured.set(t.getThreadGroup());
        });
        second.start();
        second.join();

        assertEquals(secondGroup, secondCaptured.get());
    }
}
