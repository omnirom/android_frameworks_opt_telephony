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

import org.junit.Test;

import java.util.List;

public class SequentialParserTest {
    private SequentialParser.ParseFunction mValueIndexGetter = (ctlv) -> ctlv.getValueIndex();
    private byte[] mData = {};

    @Test
    public void testParseMandatory() throws Exception {
        List<ComprehensionTlv> ctlvs = List.of(
                new ComprehensionTlv(ComprehensionTlvTag.ADDRESS.value(), false, 0, mData, 0),
                new ComprehensionTlv(ComprehensionTlvTag.ALPHA_ID.value(), false, 0, mData, 1));
        SequentialParser parser = new SequentialParser(ctlvs.iterator());

        assertThat(parser.parseMandatory(ComprehensionTlvTag.ADDRESS, mValueIndexGetter))
                .isEqualTo(0);
        assertThat(parser.parseMandatory(ComprehensionTlvTag.ALPHA_ID, mValueIndexGetter))
                .isEqualTo(1);
    }

    @Test
    public void testParseMandatoryConsumeFalse() throws Exception {
        List<ComprehensionTlv> ctlvs = List.of(
                new ComprehensionTlv(ComprehensionTlvTag.ADDRESS.value(), false, 0, mData, 0),
                new ComprehensionTlv(ComprehensionTlvTag.ALPHA_ID.value(), false, 0, mData, 1));
        SequentialParser parser = new SequentialParser(ctlvs.iterator());

        assertThat(parser.parseMandatory(ComprehensionTlvTag.ADDRESS, mValueIndexGetter, false))
                .isEqualTo(0);
        assertThat(parser.parseMandatory(ComprehensionTlvTag.ADDRESS, mValueIndexGetter, false))
                .isEqualTo(0);
    }

    @Test
    public void testParseMandatoryUsingNullFunction() throws Exception {
        List<ComprehensionTlv> ctlvs = List.of(
                new ComprehensionTlv(ComprehensionTlvTag.ADDRESS.value(), false, 0, mData, 0),
                new ComprehensionTlv(ComprehensionTlvTag.ALPHA_ID.value(), false, 0, mData, 1));
        SequentialParser parser = new SequentialParser(ctlvs.iterator());

        parser.parseMandatory(ComprehensionTlvTag.ADDRESS, null);
        assertThat(parser.parseMandatory(ComprehensionTlvTag.ALPHA_ID, mValueIndexGetter))
                .isEqualTo(1);
    }

    @Test
    public void testParseMandatoryForNoMoreElement() {
        List<ComprehensionTlv> ctlvs = List.of();
        SequentialParser parser = new SequentialParser(ctlvs.iterator());

        ResultException e = assertThrows(
                ResultException.class,
                () -> parser.parseMandatory(ComprehensionTlvTag.ADDRESS, null));
        assertThat(e.result()).isEqualTo(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
    }

    @Test
    public void testParseMandatoryForUnexpectedTag() {
        List<ComprehensionTlv> ctlvs = List.of(
                new ComprehensionTlv(ComprehensionTlvTag.ADDRESS.value(), false, 0, mData, 0));
        SequentialParser parser = new SequentialParser(ctlvs.iterator());

        ResultException e = assertThrows(
                ResultException.class,
                () -> parser.parseMandatory(ComprehensionTlvTag.ALPHA_ID, null));
        assertThat(e.result()).isEqualTo(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
    }

    @Test
    public void testParseOptional() throws Exception {
        List<ComprehensionTlv> ctlvs = List.of(
                new ComprehensionTlv(ComprehensionTlvTag.ADDRESS.value(), false, 0, mData, 0),
                new ComprehensionTlv(ComprehensionTlvTag.ALPHA_ID.value(), false, 0, mData, 1));
        SequentialParser parser = new SequentialParser(ctlvs.iterator());

        assertThat(parser.parseOptional(
                ComprehensionTlvTag.ADDRESS, mValueIndexGetter, 1234)).isEqualTo(0);
        assertThat(parser.parseOptional(
                ComprehensionTlvTag.ALPHA_ID, mValueIndexGetter, 1234)).isEqualTo(1);
    }

    @Test
    public void testParseOptionalConsumeFalse() throws Exception {
        List<ComprehensionTlv> ctlvs = List.of(
                new ComprehensionTlv(ComprehensionTlvTag.ADDRESS.value(), false, 0, mData, 0),
                new ComprehensionTlv(ComprehensionTlvTag.ALPHA_ID.value(), false, 0, mData, 1));
        SequentialParser parser = new SequentialParser(ctlvs.iterator());

        assertThat(parser.parseOptional(
                ComprehensionTlvTag.ADDRESS, mValueIndexGetter, 1234, false)).isEqualTo(0);
        assertThat(parser.parseOptional(
                ComprehensionTlvTag.ADDRESS, mValueIndexGetter, 1234, false)).isEqualTo(0);
    }

    @Test
    public void testParseOptionalUsingNullFunction() throws Exception {
        List<ComprehensionTlv> ctlvs = List.of(
                new ComprehensionTlv(ComprehensionTlvTag.ADDRESS.value(), false, 0, mData, 0),
                new ComprehensionTlv(ComprehensionTlvTag.ALPHA_ID.value(), false, 0, mData, 1));
        SequentialParser parser = new SequentialParser(ctlvs.iterator());

        parser.parseOptional(ComprehensionTlvTag.ADDRESS, null, 1234);
        assertThat(parser.parseOptional(
                ComprehensionTlvTag.ALPHA_ID, mValueIndexGetter, 1234)).isEqualTo(1);
    }

    @Test
    public void testParseOptionalForNoMoreElement() throws Exception {
        List<ComprehensionTlv> ctlvs = List.of();

        SequentialParser parser = new SequentialParser(ctlvs.iterator());
        assertThat(parser.parseOptional(
                ComprehensionTlvTag.ADDRESS, mValueIndexGetter, 1234))
                .isEqualTo(1234);
    }

    @Test
    public void testParseOptionalForUnexpectedTag() throws Exception {
        List<ComprehensionTlv> ctlvs = List.of(
                // optional value ALPHA_ID is missing here
                new ComprehensionTlv(ComprehensionTlvTag.ADDRESS.value(), false, 0, mData, 0));
        SequentialParser parser = new SequentialParser(ctlvs.iterator());

        assertThat(parser.parseOptional(
                ComprehensionTlvTag.ALPHA_ID, mValueIndexGetter, 1234))
                .isEqualTo(1234);
        assertThat(parser.parseOptional(
                ComprehensionTlvTag.ADDRESS, mValueIndexGetter, 1234))
                .isEqualTo(0);
    }
}
