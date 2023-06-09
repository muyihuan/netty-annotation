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

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests duplicated channel buffers
 */
public class DuplicatedByteBufTest extends AbstractByteBufTest {

    @Override
    protected ByteBuf newBuffer(int length, int maxCapacity) {
        ByteBuf wrapped = Unpooled.buffer(length, maxCapacity);
        ByteBuf buffer = new DuplicatedByteBuf(wrapped);
        assertEquals(wrapped.writerIndex(), buffer.writerIndex());
        assertEquals(wrapped.readerIndex(), buffer.readerIndex());
        return buffer;
    }

    @Test
    public void testIsContiguous() {
        ByteBuf buf = newBuffer(4);
        assertEquals(buf.unwrap().isContiguous(), buf.isContiguous());
        buf.release();
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullInConstructor() {
        new DuplicatedByteBuf(null);
    }

    // See https://github.com/netty/netty/issues/1800
    @Test
    public void testIncreaseCapacityWrapped() {
        ByteBuf buffer = newBuffer(8);
        ByteBuf wrapped = buffer.unwrap();
        wrapped.writeByte(0);
        wrapped.readerIndex(wrapped.readerIndex() + 1);
        buffer.writerIndex(buffer.writerIndex() + 1);
        wrapped.capacity(wrapped.capacity() * 2);

        assertEquals((byte) 0, buffer.readByte());
    }
}
