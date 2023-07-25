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
package io.netty.channel.socket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;

import java.net.Socket;

/**
 * A duplex {@link Channel} that has two sides that can be shutdown independently.
 *
 * 双工channel两边可以独立关闭,也就是输入流和输出流可以独立关闭不需要关闭套接字;
 */
public interface DuplexChannel extends Channel {
    /**
     * Returns {@code true} if and only if the remote peer shut down its output so that no more
     * data is received from this channel.  Note that the semantic of this method is different from
     * that of {@link Socket#shutdownInput()} and {@link Socket#isInputShutdown()}.
     *
     * 如果返回true说明客户端channel不在写信息了,那么服务端根据该方法就可以不再去接受该channel的信息了;
     */
    boolean isInputShutdown();

    /**
     * @see Socket#shutdownInput()
     *
     * 执行该方法后就会使isInputShutdown方法返回true,意思是关闭该套接字的输入流但是不关闭套接字;
     *
     * {
     *     执行该方法端的套接字的输入流会关闭,意思是另一端发送的消息这段会返回确认报文但是会默许丢掉,该端通过输入流无法读到数据;
     * }
     */
    ChannelFuture shutdownInput();

    /**
     * Will shutdown the input and notify {@link ChannelPromise}.
     *
     * @see Socket#shutdownInput()
     *
     * 执行该方法后就会使isInputShutdown方法返回true,意思是关闭该套接字的输入流但是不关闭套接字,一定程度上可以提高该端的写入的效率;
     */
    ChannelFuture shutdownInput(ChannelPromise promise);

    /**
     * @see Socket#isOutputShutdown()
     *
     * 返回该套接字的输出流是否关闭;
     */
    boolean isOutputShutdown();

    /**
     * @see Socket#shutdownOutput()
     *
     * 执行该方法后就会使isOutputShutdown方法返回true,意思是关闭该套接字的输出流但是不关闭套接字;
     *
     * {
     *     执行该方法端的套接字的输出流会关闭,意思是该端会在发送一个报文告诉另一端我不会再发送任何消息了,一定程度上可以提高该端的读入的效率;
     * }
     */
    ChannelFuture shutdownOutput();

    /**
     * Will shutdown the output and notify {@link ChannelPromise}.
     *
     * @see Socket#shutdownOutput()
     *
     * 执行该方法后就会使isOutputShutdown方法返回true,意思是关闭该套接字的输出流但是不关闭套接字;
     */
    ChannelFuture shutdownOutput(ChannelPromise promise);

    /**
     * Determine if both the input and output of this channel have been shutdown.
     *
     * 判断是否输入流和输出流是否都关闭了,但是套接字不一定close;
     */
    boolean isShutdown();

    /**
     * Will shutdown the input and output sides of this channel.
     * @return will be completed when both shutdown operations complete.
     *
     * 关闭套接字的输出和输入流,使isShutdown返回true;
     */
    ChannelFuture shutdown();

    /**
     * Will shutdown the input and output sides of this channel.
     * @param promise will be completed when both shutdown operations complete.
     * @return will be completed when both shutdown operations complete.
     *
     * 关闭套接字的输出和输入流,使isShutdown返回true;
     */
    ChannelFuture shutdown(ChannelPromise promise);
}
