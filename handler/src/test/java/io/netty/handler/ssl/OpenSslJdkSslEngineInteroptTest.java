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
package io.netty.handler.ssl;

import io.netty.util.internal.PlatformDependent;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import javax.net.ssl.SSLEngine;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static io.netty.handler.ssl.OpenSslTestUtils.checkShouldUseKeyManagerFactory;
import static org.junit.Assume.assumeTrue;

@RunWith(Parameterized.class)
public class OpenSslJdkSslEngineInteroptTest extends SSLEngineTest {

    @Parameterized.Parameters(name = "{index}: bufferType = {0}, combo = {1}, delegate = {2}, useTasks = {3}")
    public static Collection<Object[]> data() {
        List<Object[]> params = new ArrayList<>();
        for (BufferType type: BufferType.values()) {
            params.add(new Object[] { type, ProtocolCipherCombo.tlsv12(), false, false });
            params.add(new Object[] { type, ProtocolCipherCombo.tlsv12(), false, true });

            params.add(new Object[] { type, ProtocolCipherCombo.tlsv12(), true, false});
            params.add(new Object[] { type, ProtocolCipherCombo.tlsv12(), true, true });

            if (PlatformDependent.javaVersion() >= 11 && OpenSsl.isTlsv13Supported()) {
                params.add(new Object[] { type, ProtocolCipherCombo.tlsv13(), false, false });
                params.add(new Object[] { type, ProtocolCipherCombo.tlsv13(), false, true });

                params.add(new Object[] { type, ProtocolCipherCombo.tlsv13(), true, false });
                params.add(new Object[] { type, ProtocolCipherCombo.tlsv13(), true, true });
            }
        }
        return params;
    }

    private final boolean useTasks;

    public OpenSslJdkSslEngineInteroptTest(BufferType type, ProtocolCipherCombo combo,
                                           boolean delegate, boolean useTasks) {
        super(type, combo, delegate);
        this.useTasks = useTasks;
    }

    @BeforeClass
    public static void checkOpenSsl() {
        assumeTrue(OpenSsl.isAvailable());
    }

    @Override
    protected SslProvider sslClientProvider() {
        return SslProvider.OPENSSL;
    }

    @Override
    protected SslProvider sslServerProvider() {
        return SslProvider.JDK;
    }

    @Ignore /* Does the JDK support a "max certificate chain length"? */
    @Override
    public void testMutualAuthValidClientCertChainTooLongFailOptionalClientAuth() throws Exception {
    }

    @Ignore /* Does the JDK support a "max certificate chain length"? */
    @Override
    public void testMutualAuthValidClientCertChainTooLongFailRequireClientAuth() throws Exception {
    }

    @Override
    @Test
    public void testMutualAuthInvalidIntermediateCASucceedWithOptionalClientAuth() throws Exception {
        checkShouldUseKeyManagerFactory();
        super.testMutualAuthInvalidIntermediateCASucceedWithOptionalClientAuth();
    }

    @Override
    @Test
    public void testMutualAuthInvalidIntermediateCAFailWithOptionalClientAuth() throws Exception {
        checkShouldUseKeyManagerFactory();
        super.testMutualAuthInvalidIntermediateCAFailWithOptionalClientAuth();
    }

    @Override
    @Test
    public void testMutualAuthInvalidIntermediateCAFailWithRequiredClientAuth() throws Exception {
        checkShouldUseKeyManagerFactory();
        super.testMutualAuthInvalidIntermediateCAFailWithRequiredClientAuth();
    }

    @Override
    @Test
    public void testSessionAfterHandshakeKeyManagerFactoryMutualAuth() throws Exception {
        checkShouldUseKeyManagerFactory();
        super.testSessionAfterHandshakeKeyManagerFactoryMutualAuth();
    }

    @Override
    protected boolean mySetupMutualAuthServerIsValidServerException(Throwable cause) {
        // TODO(scott): work around for a JDK issue. The exception should be SSLHandshakeException.
        return super.mySetupMutualAuthServerIsValidServerException(cause) || causedBySSLException(cause);
    }

    @Override
    public void testHandshakeSession() throws Exception {
        checkShouldUseKeyManagerFactory();
        super.testHandshakeSession();
    }

    @Override
    @Test
    public void testSupportedSignatureAlgorithms() throws Exception {
        checkShouldUseKeyManagerFactory();
        super.testSupportedSignatureAlgorithms();
    }

    @Override
    protected SSLEngine wrapEngine(SSLEngine engine) {
        return Java8SslTestUtils.wrapSSLEngineForTesting(engine);
    }

    @Override
    protected SslContext wrapContext(SslContext context) {
        if (context instanceof OpenSslContext) {
            ((OpenSslContext) context).setUseTasks(useTasks);
        }
        return context;
    }
}
