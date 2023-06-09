/*
 * Copyright 2018 The Netty Project
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
package io.netty.handler.codec;

import org.junit.Test;


import static org.junit.Assert.*;

public class CodecOutputListTest {

    @Test
    public void testCodecOutputListAdd() {
        CodecOutputList codecOutputList = CodecOutputList.newInstance();
        try {
            assertEquals(0, codecOutputList.size());
            assertTrue(codecOutputList.isEmpty());

            codecOutputList.add(1);
            assertEquals(1, codecOutputList.size());
            assertFalse(codecOutputList.isEmpty());
            assertEquals(1, codecOutputList.get(0));

            codecOutputList.add(0, 0);
            assertEquals(2, codecOutputList.size());
            assertFalse(codecOutputList.isEmpty());
            assertEquals(0, codecOutputList.get(0));
            assertEquals(1, codecOutputList.get(1));

            codecOutputList.add(1, 2);
            assertEquals(3, codecOutputList.size());
            assertFalse(codecOutputList.isEmpty());
            assertEquals(0, codecOutputList.get(0));
            assertEquals(2, codecOutputList.get(1));
            assertEquals(1, codecOutputList.get(2));
        } finally {
            codecOutputList.recycle();
        }
    }
}
