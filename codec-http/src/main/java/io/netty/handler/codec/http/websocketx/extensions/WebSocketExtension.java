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
package io.netty.handler.codec.http.websocketx.extensions;

/**
 * Created once the handshake phase is done.
 */
public interface WebSocketExtension {

    int RSV1 = 0x04;
    int RSV2 = 0x02;
    int RSV3 = 0x01;

    /**
     * @return the reserved bit value to ensure that no other extension should interfere.
     */
    int rsv();

    /**
     * @return create the extension encoder.
     */
    WebSocketExtensionEncoder newExtensionEncoder();

    /**
     * @return create the extension decoder.
     */
    WebSocketExtensionDecoder newExtensionDecoder();

}
