/*
 * Copyright 2014 The Netty Project
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

package io.netty.handler.proxy;

import static java.util.Objects.requireNonNull;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AsciiString;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public final class HttpProxyHandler extends ProxyHandler {

    private static final String PROTOCOL = "http";
    private static final String AUTH_BASIC = "basic";

    private static final byte[] BASIC_BYTES = "Basic ".getBytes(StandardCharsets.UTF_8);

    private final HttpClientCodec codec = new HttpClientCodec();
    private final String username;
    private final String password;
    private final CharSequence authorization;
    private final HttpHeaders outboundHeaders;
    private final boolean ignoreDefaultPortsInConnectHostHeader;
    private HttpResponseStatus status;
    private HttpHeaders inboundHeaders;

    public HttpProxyHandler(SocketAddress proxyAddress) {
        this(proxyAddress, null);
    }

    public HttpProxyHandler(SocketAddress proxyAddress, HttpHeaders headers) {
        this(proxyAddress, headers, false);
    }

    public HttpProxyHandler(SocketAddress proxyAddress,
                            HttpHeaders headers,
                            boolean ignoreDefaultPortsInConnectHostHeader) {
        super(proxyAddress);
        username = null;
        password = null;
        authorization = null;
        this.outboundHeaders = headers;
        this.ignoreDefaultPortsInConnectHostHeader = ignoreDefaultPortsInConnectHostHeader;
    }

    public HttpProxyHandler(SocketAddress proxyAddress, String username, String password) {
        this(proxyAddress, username, password, null);
    }

    public HttpProxyHandler(SocketAddress proxyAddress, String username, String password,
                            HttpHeaders headers) {
        this(proxyAddress, username, password, headers, false);
    }

    public HttpProxyHandler(SocketAddress proxyAddress,
                            String username,
                            String password,
                            HttpHeaders headers,
                            boolean ignoreDefaultPortsInConnectHostHeader) {
        super(proxyAddress);
        requireNonNull(username, "username");
        requireNonNull(password, "password");
        this.username = username;
        this.password = password;

        byte[] authzBase64 = Base64.getEncoder().encode(
                (username + ':' + password).getBytes(StandardCharsets.UTF_8));
        byte[] authzHeader = Arrays.copyOf(BASIC_BYTES, 6 + authzBase64.length);
        System.arraycopy(authzBase64, 0, authzHeader, 6, authzBase64.length);

        authorization = new AsciiString(authzHeader, /*copy=*/ false);

        this.outboundHeaders = headers;
        this.ignoreDefaultPortsInConnectHostHeader = ignoreDefaultPortsInConnectHostHeader;
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public String authScheme() {
        return authorization != null? AUTH_BASIC : AUTH_NONE;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    @Override
    protected void addCodec(ChannelHandlerContext ctx) throws Exception {
        ChannelPipeline p = ctx.pipeline();
        String name = ctx.name();
        p.addBefore(name, null, codec);
    }

    @Override
    protected void removeEncoder(ChannelHandlerContext ctx) throws Exception {
        codec.removeOutboundHandler();
    }

    @Override
    protected void removeDecoder(ChannelHandlerContext ctx) throws Exception {
        codec.removeInboundHandler();
    }

    @Override
    protected Object newInitialMessage(ChannelHandlerContext ctx) throws Exception {
        InetSocketAddress raddr = destinationAddress();

        String hostString = HttpUtil.formatHostnameForHttp(raddr);
        int port = raddr.getPort();
        String url = hostString + ":" + port;
        String hostHeader = (ignoreDefaultPortsInConnectHostHeader && (port == 80 || port == 443)) ?
                hostString :
                url;

        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.CONNECT,
                url,
                Unpooled.EMPTY_BUFFER, false);

        req.headers().set(HttpHeaderNames.HOST, hostHeader);

        if (authorization != null) {
            req.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, authorization);
        }

        if (outboundHeaders != null) {
            req.headers().add(outboundHeaders);
        }

        return req;
    }

    @Override
    protected boolean handleResponse(ChannelHandlerContext ctx, Object response) throws Exception {
        if (response instanceof HttpResponse) {
            if (status != null) {
                throw new HttpProxyConnectException(exceptionMessage("too many responses"), /*headers=*/ null);
            }
            HttpResponse res = (HttpResponse) response;
            status = res.status();
            inboundHeaders = res.headers();
        }

        boolean finished = response instanceof LastHttpContent;
        if (finished) {
            if (status == null) {
                throw new HttpProxyConnectException(exceptionMessage("missing response"), inboundHeaders);
            }
            if (status.code() != 200) {
                throw new HttpProxyConnectException(exceptionMessage("status: " + status), inboundHeaders);
            }
        }

        return finished;
    }

    /**
     * Specific case of a connection failure, which may include headers from the proxy.
     */
    public static final class HttpProxyConnectException extends ProxyConnectException {
        private static final long serialVersionUID = -8824334609292146066L;

        private final HttpHeaders headers;

        /**
         * @param message The failure message.
         * @param headers Header associated with the connection failure.  May be {@code null}.
         */
        public HttpProxyConnectException(String message, HttpHeaders headers) {
            super(message);
            this.headers = headers;
        }

        /**
         * Returns headers, if any.  May be {@code null}.
         */
        public HttpHeaders headers() {
            return headers;
        }
    }
}
