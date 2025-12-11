/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.cat;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import com.android.internal.telephony.uicc.IccUtils;

import org.junit.Test;

public class ValueParserTest {
    @Test
    public void testRetrieveTextCodingScheme() throws Exception {
        ComprehensionTlv ctlv = new ComprehensionTlv(
                ComprehensionTlvTag.TEXT_STRING.value(),
                false,
                4,
                IccUtils.hexStringToBytes("04000000"),
                0);

        byte result = ValueParser.retrieveTextCodingScheme(ctlv);
        assertThat(result).isEqualTo(0x04);
    }

    @Test
    public void testRetrieveTextCodingSchemeForNotSupportedScheme() throws Exception {
        ComprehensionTlv ctlv = new ComprehensionTlv(
                ComprehensionTlvTag.TEXT_STRING.value(),
                false,
                4,
                IccUtils.hexStringToBytes("0F000000"),
                0);

        ResultException e = assertThrows(
                ResultException.class,
                () -> ValueParser.retrieveTextCodingScheme(ctlv));
        assertThat(e.result()).isEqualTo(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
    }

    @Test
    public void testRetrieveTextCodingSchemeForZeroLength() throws Exception {
        ComprehensionTlv ctlv = new ComprehensionTlv(
                ComprehensionTlvTag.TEXT_STRING.value(),
                false,
                0,
                IccUtils.hexStringToBytes(""),
                0);

        ResultException e = assertThrows(
                ResultException.class,
                () -> ValueParser.retrieveTextCodingScheme(ctlv));
        assertThat(e.result()).isEqualTo(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
    }

    @Test
    public void testRetrieveAddress() throws Exception {
        ComprehensionTlv ctlv = new ComprehensionTlv(
                ComprehensionTlvTag.ADDRESS.value(),
                false,
                3,
                IccUtils.hexStringToBytes("801234"),
                0);

        String result = ValueParser.retrieveAddress(ctlv);
        assertThat(result).isEqualTo("2143");
    }

    @Test
    public void testRetrieveAddressForInvalidLength() throws Exception {
        ComprehensionTlv ctlv = new ComprehensionTlv(
                ComprehensionTlvTag.ADDRESS.value(),
                false,
                7,
                IccUtils.hexStringToBytes("801234"),
                0);

        ResultException e = assertThrows(
                ResultException.class,
                () -> ValueParser.retrieveAddress(ctlv));
        assertThat(e.result()).isEqualTo(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
    }
}
