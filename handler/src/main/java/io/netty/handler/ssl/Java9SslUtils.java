/*
 * Copyright 2017 The Netty Project
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
package io.netty.handler.ssl;


import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import java.util.function.BiFunction;

import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

final class Java9SslUtils {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Java9SslUtils.class);
    private static final Method SET_APPLICATION_PROTOCOLS;
    private static final Method GET_APPLICATION_PROTOCOL;
    private static final Method GET_HANDSHAKE_APPLICATION_PROTOCOL;
    private static final Method SET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR;
    private static final Method GET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR;

    static {
        Method getHandshakeApplicationProtocol = null;
        Method getApplicationProtocol = null;
        Method setApplicationProtocols = null;
        Method setHandshakeApplicationProtocolSelector = null;
        Method getHandshakeApplicationProtocolSelector = null;

        try {
            SSLContext context = SSLContext.getInstance(JdkSslContext.PROTOCOL);
            context.init(null, null, null);
            SSLEngine engine = context.createSSLEngine();
            getHandshakeApplicationProtocol = AccessController.doPrivileged((PrivilegedExceptionAction<Method>) () ->
                    SSLEngine.class.getMethod("getHandshakeApplicationProtocol"));
            getHandshakeApplicationProtocol.invoke(engine);
            getApplicationProtocol = AccessController.doPrivileged((PrivilegedExceptionAction<Method>) () ->
                    SSLEngine.class.getMethod("getApplicationProtocol"));
            getApplicationProtocol.invoke(engine);

            setApplicationProtocols = AccessController.doPrivileged((PrivilegedExceptionAction<Method>) () ->
                    SSLParameters.class.getMethod("setApplicationProtocols", String[].class));
            setApplicationProtocols.invoke(engine.getSSLParameters(), new Object[]{EmptyArrays.EMPTY_STRINGS});

            setHandshakeApplicationProtocolSelector =
                    AccessController.doPrivileged((PrivilegedExceptionAction<Method>) () ->
                            SSLEngine.class.getMethod("setHandshakeApplicationProtocolSelector", BiFunction.class));
            setHandshakeApplicationProtocolSelector.invoke(engine,
                    (BiFunction<SSLEngine, List<String>, String>) (sslEngine, strings) -> null);

            getHandshakeApplicationProtocolSelector =
                    AccessController.doPrivileged((PrivilegedExceptionAction<Method>) () ->
                            SSLEngine.class.getMethod("getHandshakeApplicationProtocolSelector"));
            getHandshakeApplicationProtocolSelector.invoke(engine);
        } catch (Throwable t) {
            logger.error("Unable to initialize Java9SslUtils, but the detected javaVersion was: {}",
                    PlatformDependent.javaVersion(), t);
            getHandshakeApplicationProtocol = null;
            getApplicationProtocol = null;
            setApplicationProtocols = null;
            setHandshakeApplicationProtocolSelector = null;
            getHandshakeApplicationProtocolSelector = null;
        }
        GET_HANDSHAKE_APPLICATION_PROTOCOL = getHandshakeApplicationProtocol;
        GET_APPLICATION_PROTOCOL = getApplicationProtocol;
        SET_APPLICATION_PROTOCOLS = setApplicationProtocols;
        SET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR = setHandshakeApplicationProtocolSelector;
        GET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR = getHandshakeApplicationProtocolSelector;
    }

    private Java9SslUtils() {
    }

    static boolean supportsAlpn() {
        return GET_APPLICATION_PROTOCOL != null;
    }

    static String getApplicationProtocol(SSLEngine sslEngine) {
        try {
            return (String) GET_APPLICATION_PROTOCOL.invoke(sslEngine);
        } catch (UnsupportedOperationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String getHandshakeApplicationProtocol(SSLEngine sslEngine) {
        try {
            return (String) GET_HANDSHAKE_APPLICATION_PROTOCOL.invoke(sslEngine);
        } catch (UnsupportedOperationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static void setApplicationProtocols(SSLEngine engine, List<String> supportedProtocols) {
        SSLParameters parameters = engine.getSSLParameters();

        String[] protocolArray = supportedProtocols.toArray(EmptyArrays.EMPTY_STRINGS);
        try {
            SET_APPLICATION_PROTOCOLS.invoke(parameters, new Object[]{protocolArray});
        } catch (UnsupportedOperationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        engine.setSSLParameters(parameters);
    }

    static void setHandshakeApplicationProtocolSelector(
            SSLEngine engine, BiFunction<SSLEngine, List<String>, String> selector) {
        try {
            SET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR.invoke(engine, selector);
        } catch (UnsupportedOperationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static BiFunction<SSLEngine, List<String>, String> getHandshakeApplicationProtocolSelector(SSLEngine engine) {
        try {
            return (BiFunction<SSLEngine, List<String>, String>)
                    GET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR.invoke(engine);
        } catch (UnsupportedOperationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
