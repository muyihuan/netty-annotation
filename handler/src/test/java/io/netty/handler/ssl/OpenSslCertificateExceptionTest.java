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

import io.netty.internal.tcnative.CertificateVerifier;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Field;

public class OpenSslCertificateExceptionTest {

    @Test
    public void testValidErrorCode() throws Exception {
        Assume.assumeTrue(OpenSsl.isAvailable());
        Field[] fields = CertificateVerifier.class.getFields();
        for (Field field : fields) {
            if (field.isAccessible()) {
                int errorCode = field.getInt(null);
                OpenSslCertificateException exception = new OpenSslCertificateException(errorCode);
                Assert.assertEquals(errorCode, exception.errorCode());
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNonValidErrorCode() {
        Assume.assumeTrue(OpenSsl.isAvailable());
        new OpenSslCertificateException(Integer.MIN_VALUE);
    }

    @Test
    public void testCanBeInstancedWhenOpenSslIsNotAvailable() {
        Assume.assumeFalse(OpenSsl.isAvailable());
        new OpenSslCertificateException(0);
    }
}
