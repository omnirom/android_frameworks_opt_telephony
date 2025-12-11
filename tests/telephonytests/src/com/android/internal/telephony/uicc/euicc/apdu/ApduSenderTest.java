/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.telephony.uicc.euicc.apdu;

import static com.android.internal.telephony.CommandException.Error.RADIO_NOT_AVAILABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.os.Looper;
import android.platform.test.flag.junit.SetFlagsRule;
import android.preference.PreferenceManager;
import android.telephony.IccOpenLogicalChannelResponse;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.euicc.EuiccSession;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.uicc.IccIoResult;
import com.android.internal.telephony.uicc.IccUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mockito;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ApduSenderTest extends TelephonyTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static class ResponseCaptor extends ApduSenderResultCallback {
        public byte[] response;
        public Throwable exception;
        public int stopApduIndex = -1;

        @Override
        public boolean shouldContinueOnIntermediateResult(IccIoResult result) {
            if (stopApduIndex < 0) {
                return true;
            }
            if (stopApduIndex == 0) {
                return false;
            }
            stopApduIndex--;
            return true;
        }

        @Override
        public void onResult(byte[] bytes) {
            response = bytes;
        }

        @Override
        public void onException(Throwable e) {
            exception = e;
        }
    }

    private static final int PHONE_ID = 0;
    private static final String SESSION_ID = "TEST";
    // keep in sync with ApduSender.mChannelKey
    private static final String SHARED_PREFS_KEY_CHANNEL_ID = "esim-channel_0";
    // keep in sync with ApduSender.mChannelResponseKey
    private static final String SHARED_PREFS_KEY_CHANNEL_RESPONSE = "esim-res-id_0";

    // Mocked classes
    private CommandsInterface mMockCi;

    private TestableLooper mLooper;
    private Handler mHandler;
    private ResponseCaptor mResponseCaptor;
    private byte[] mSelectResponse;
    private ApduSender mSender;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());

        mSetFlagsRule.enableFlags(Flags.FLAG_OPTIMIZATION_APDU_SENDER);
        mContextFixture.putBooleanResource(
                com.android.internal.R.bool.euicc_optimize_apdu_sender, true);

        mMockCi = mock(CommandsInterface.class);
        mLooper = TestableLooper.get(this);
        mHandler = new Handler(mLooper.getLooper());
        mResponseCaptor = new ResponseCaptor();
        mSelectResponse = null;

        mSender = new ApduSender(mContext, PHONE_ID,
                            mMockCi, ApduSender.ISD_R_AID, false /* supportExtendedApdu */);
    }

    @After
    public void tearDown() throws Exception {
        // Send an APDU to verify that the channel lock is not stuck (b/382549728).
        // Sameas testSend(), but not verifying mMockCi interactions.
        checkChannelLock();

        mHandler.removeCallbacksAndMessages(null);
        mHandler = null;
        mLooper = null;
        mResponseCaptor = null;
        mSelectResponse = null;
        mSender = null;

        EuiccSession.get(mContext).endSession(SESSION_ID);
        resetEuiccSession();
        clearSharedPreferences();

        super.tearDown();
    }

    @Test
    public void testWrongAid_throwsIllegalArgumentException() throws Exception {
        String wrongAid = "-1";

        assertThrows(IllegalArgumentException.class, () -> {
            new ApduSender(mContext, 0 /* phoneId= */,
                            mMockCi, wrongAid, false /* supportExtendedApdu */);
        });
    }

    @Test
    public void testSendEmptyCommands() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "A1A1A19000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);

        mSender.send((selectResponse, requestBuilder) -> mSelectResponse = selectResponse,
                mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals("A1A1A19000", IccUtils.bytesToHexString(mSelectResponse));
        assertNull(mResponseCaptor.response);
        assertNull(mResponseCaptor.exception);
        verify(mMockCi).iccOpenLogicalChannel(eq(ApduSender.ISD_R_AID), anyInt(), any());
        verify(mMockCi).iccCloseLogicalChannel(eq(channel), eq(true /*isEs10*/), any());
    }

    @Test
    public void testOpenChannelErrorStatus() throws Exception {
        LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi,
                new CommandException(CommandException.Error.NO_SUCH_ELEMENT));

        mSender.send((selectResponse, requestBuilder) -> mSelectResponse = new byte[0],
                mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertNull("Request provider should not be called when failed to open channel.",
                mSelectResponse);
        assertTrue(mResponseCaptor.exception instanceof ApduException);
        verify(mMockCi).iccOpenLogicalChannel(eq(ApduSender.ISD_R_AID), anyInt(), any());
    }

    @Test
    public void testSend() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "A1A1A19000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);

        mSender.send((selectResponse, requestBuilder) -> requestBuilder.addApdu(
                10, 1, 2, 3, 0, "a"), mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals("A1A1A1", IccUtils.bytesToHexString(mResponseCaptor.response));
        InOrder inOrder = inOrder(mMockCi);
        inOrder.verify(mMockCi).iccOpenLogicalChannel(eq(ApduSender.ISD_R_AID), anyInt(), any());
        inOrder.verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10),
                eq(1), eq(2), eq(3), eq(0), eq("a"), anyBoolean(), any());
        inOrder.verify(mMockCi).iccCloseLogicalChannel(eq(channel), eq(true /*isEs10*/), any());
    }

    @Test
    public void testSendMultiApdus() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "A19000", "A29000",
                "A39000", "A49000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);

        mSender.send((selectResponse, requestBuilder) -> {
            requestBuilder.addApdu(10, 1, 2, 3, 0, "a");
            requestBuilder.addApdu(10, 1, 2, 3, "ab");
            requestBuilder.addApdu(10, 1, 2, 3);
            requestBuilder.addStoreData("abcd");
        }, mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals("A4", IccUtils.bytesToHexString(mResponseCaptor.response));
        InOrder inOrder = inOrder(mMockCi);
        inOrder.verify(mMockCi).iccOpenLogicalChannel(eq(ApduSender.ISD_R_AID), anyInt(), any());
        inOrder.verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10),
                eq(1), eq(2), eq(3), eq(0), eq("a"), anyBoolean(), any());
        inOrder.verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10),
                eq(1), eq(2), eq(3), eq(1), eq("ab"), anyBoolean(), any());
        inOrder.verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10),
                eq(1), eq(2),  eq(3), eq(0), eq(""), anyBoolean(), any());
        inOrder.verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(0x81),
                eq(0xE2), eq(0x91), eq(0), eq(2), eq("abcd"), anyBoolean(), any());
        inOrder.verify(mMockCi).iccCloseLogicalChannel(eq(channel), eq(true /*isEs10*/), any());
    }

    @Test
    public void testSendMultiApdusStopEarly() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "A19000", "A29000",
                "A39000", "A49000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);
        mResponseCaptor.stopApduIndex = 2;

        mSender.send((selectResponse, requestBuilder) -> {
            requestBuilder.addApdu(10, 1, 2, 3, 0, "a");
            requestBuilder.addApdu(10, 1, 2, 3, "ab");
            requestBuilder.addApdu(10, 1, 2, 3);
            requestBuilder.addStoreData("abcd");
        }, mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals("A3", IccUtils.bytesToHexString(mResponseCaptor.response));
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10), eq(1), eq(2),
                eq(3), eq(0), eq("a"), anyBoolean(), any());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10), eq(1), eq(2),
                eq(3), eq(1), eq("ab"), anyBoolean(), any());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10), eq(1), eq(2),
                eq(3), eq(0), eq(""), anyBoolean(), any());
    }

    @Test
    public void testSendLongResponse() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "A1A1A16104",
                "B2B2B2B26102", "C3C39000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);

        mSender.send((selectResponse, requestBuilder) -> requestBuilder.addApdu(
                10, 1, 2, 3, 0, "a"), mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals("A1A1A1B2B2B2B2C3C3", IccUtils.bytesToHexString(mResponseCaptor.response));
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10), eq(1), eq(2),
                eq(3), eq(0), eq("a"), anyBoolean(), any());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel), eq(0xC0), eq(0),
                eq(0), eq(4), eq(""), anyBoolean(), any());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel), eq(0xC0), eq(0),
                eq(0), eq(2), eq(""), anyBoolean(), any());
    }

    @Test
    public void testSendStoreDataLongDataLongResponse() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "A19000", "9000", "9000",
                "B22B6103", "B2222B9000", "C39000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);

        // Each segment has 0xFF (the limit of a single command) bytes.
        String s1 = new String(new char[0xFF]).replace("\0", "AA");
        String s2 = new String(new char[0xFF]).replace("\0", "BB");
        String s3 = new String(new char[16]).replace("\0", "CC");
        String longData = s1 + s2 + s3;
        mSender.send((selectResponse, requestBuilder) -> {
            requestBuilder.addApdu(10, 1, 2, 3, 0, "a");
            requestBuilder.addApdu(10, 1, 2, 3, 0, "b");
            requestBuilder.addStoreData(longData);
        }, mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals("C3", IccUtils.bytesToHexString(mResponseCaptor.response));
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10), eq(1), eq(2),
                eq(3), eq(0), eq("a"), anyBoolean(), any());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10), eq(1), eq(2),
                eq(3), eq(0), eq("b"), anyBoolean(), any());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(0x81), eq(0xE2), eq(0x11),
                eq(0), eq(0xFF), eq(s1), anyBoolean(), any());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(0x81), eq(0xE2), eq(0x11),
                eq(1), eq(0xFF), eq(s2), anyBoolean(), any());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(0x81), eq(0xE2), eq(0x91),
                eq(2), eq(16), eq(s3), anyBoolean(), any());
    }

    @Test
    public void testSendStoreDataLongDataMod0() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "9000", "B2222B9000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);

        // Each segment has 0xFF (the limit of a single command) bytes.
        String s1 = new String(new char[0xFF]).replace("\0", "AA");
        String s2 = new String(new char[0xFF]).replace("\0", "BB");
        String longData = s1 + s2;
        mSender.send((selectResponse, requestBuilder) -> {
            requestBuilder.addStoreData(longData);
        }, mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals("B2222B", IccUtils.bytesToHexString(mResponseCaptor.response));
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(0x81), eq(0xE2), eq(0x11),
                eq(0), eq(0xFF), eq(s1), anyBoolean(), any());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(0x81), eq(0xE2), eq(0x91),
                eq(1), eq(0xFF), eq(s2), anyBoolean(), any());
    }

    @Test
    public void testSendStoreDataLen0() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "B2222B9000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);

        mSender.send((selectResponse, requestBuilder) -> {
            requestBuilder.addStoreData("");
        }, mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals("B2222B", IccUtils.bytesToHexString(mResponseCaptor.response));
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(0x81), eq(0xE2), eq(0x91),
                eq(0), eq(0), eq(""), anyBoolean(), any());
    }

    @Test
    public void testSendErrorResponseInMiddle() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "A19000", "9000",
                "B22B6103", "6985");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);

        // Each segment has 0xFF (the limit of a single command) bytes.
        String s1 = new String(new char[0xFF]).replace("\0", "AA");
        String s2 = new String(new char[0xFF]).replace("\0", "BB");
        String s3 = new String(new char[16]).replace("\0", "CC");
        String longData = s1 + s2 + s3;
        mSender.send((selectResponse, requestBuilder) -> {
            requestBuilder.addApdu(10, 1, 2, 3, 0, "a");
            requestBuilder.addStoreData(longData);
        }, mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals(0x6985, ((ApduException) mResponseCaptor.exception).getApduStatus());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10), eq(1), eq(2),
                eq(3), eq(0), eq("a"), anyBoolean(), any());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(0x81), eq(0xE2), eq(0x11),
                eq(0), eq(0xFF), eq(s1), anyBoolean(), any());
        verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(0x81), eq(0xE2), eq(0x11),
                eq(1), eq(0xFF), eq(s2), anyBoolean(), any());
        verify(mMockCi, never()).iccTransmitApduLogicalChannel(eq(channel), eq(0x81), eq(0xE2),
                eq(0x91), eq(2), eq(16), eq(s3), anyBoolean(), any());
    }

    @Test
    public void testChannelAlreadyOpened() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);

        ResponseCaptor outerResponseCaptor = new ResponseCaptor();
        mSender.send(
                (selectResponse, requestBuilder) -> mSender.send(
                        (selectResponseOther, requestBuilderOther) ->
                                mSelectResponse = selectResponseOther,
                        mResponseCaptor, mHandler),
                outerResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertNull("Should not open channel when another one is already opened.", mSelectResponse);
        assertTrue(mResponseCaptor.exception instanceof ApduException);
        verify(mMockCi, times(1)).iccOpenLogicalChannel(eq(ApduSender.ISD_R_AID), anyInt(), any());
    }

    @Test
    public void testSend_OpenChannelFailedNoSuchElement_useChannelInSharedPreference()
            throws Exception {
        // Open a channel but not close, by making CI.iccTransmitApduLogicalChannel throw.
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        doThrow(new RuntimeException()).when(mMockCi).iccTransmitApduLogicalChannel(
                eq(channel), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any(),
                anyBoolean(), any());
        mSender.send((selectResponse, requestBuilder) -> requestBuilder.addApdu(
                10, 1, 2, 3, 0, "a"), mResponseCaptor, mHandler);
        mLooper.processAllMessages();
        mSender = new ApduSender(mContext, PHONE_ID,
                            mMockCi, ApduSender.ISD_R_AID, false /* supportExtendedApdu */);
        reset(mMockCi);
        // Stub open channel failure NO_SUCH_ELEMENT
        LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi,
                new CommandException(CommandException.Error.NO_SUCH_ELEMENT));
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "A1A1A19000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);

        mSender.send((selectResponse, requestBuilder) -> requestBuilder.addApdu(
                10, 1, 2, 3, 0, "a"), mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        // open channel would fail, and send/close would succeed because of
        // previous open response saved in sharedPref
        InOrder inOrder = inOrder(mMockCi);
        inOrder.verify(mMockCi).iccOpenLogicalChannel(eq(ApduSender.ISD_R_AID), anyInt(), any());
        inOrder.verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel),
                 eq(channel | 10), eq(1), eq(2), eq(3), eq(0), eq("a"), anyBoolean(), any());
        inOrder.verify(mMockCi).iccCloseLogicalChannel(eq(channel), eq(true /*isEs10*/), any());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testSend_euiccSession_shouldNotCloseChannel() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "A1A1A19000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);
        EuiccSession.get(mContext).startSession(SESSION_ID);

        mSender.send((selectResponse, requestBuilder) -> requestBuilder.addApdu(
                10, 1, 2, 3, 0, "a"), mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals("A1A1A1", IccUtils.bytesToHexString(mResponseCaptor.response));
        InOrder inOrder = inOrder(mMockCi);
        inOrder.verify(mMockCi).iccOpenLogicalChannel(eq(ApduSender.ISD_R_AID), anyInt(), any());
        inOrder.verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10),
                eq(1), eq(2), eq(3), eq(0), eq("a"), anyBoolean(), any());
        // No iccCloseLogicalChannel
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testSend_euiccSession_sendFailure_shouldCloseChannel() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        // Send failure: `sw1:0x6f sw2:0x0 Error: technical problem with no diagnostic given`
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "6F00");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);
        EuiccSession.get(mContext).startSession(SESSION_ID);

        mSender.send((selectResponse, requestBuilder) -> requestBuilder.addApdu(
                10, 1, 2, 3, 0, "a"), mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals(0x6F00, ((ApduException) mResponseCaptor.exception).getApduStatus());
        InOrder inOrder = inOrder(mMockCi);
        inOrder.verify(mMockCi).iccOpenLogicalChannel(eq(ApduSender.ISD_R_AID), anyInt(), any());
        inOrder.verify(mMockCi).iccTransmitApduLogicalChannel(eq(channel), eq(channel | 10),
                eq(1), eq(2), eq(3), eq(0), eq("a"), anyBoolean(), any());
        inOrder.verify(mMockCi).iccCloseLogicalChannel(eq(channel), eq(true /*isEs10*/), any());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testSendTwice_euiccSession_shouldOpenChannelOnceNotCloseChannel() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(
                    mMockCi, channel, "A1A1A19000", "A1A1A19000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);
        EuiccSession.get(mContext).startSession(SESSION_ID);

        mSender.send((selectResponse, requestBuilder) -> requestBuilder.addApdu(
                10, 1, 2, 3, 0, "a"), mResponseCaptor, mHandler);
        mLooper.processAllMessages();
        mSender.send((selectResponse, requestBuilder) -> requestBuilder.addApdu(
                10, 1, 2, 3, 0, "a"), mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals("A1A1A1", IccUtils.bytesToHexString(mResponseCaptor.response));
        InOrder inOrder = inOrder(mMockCi);
        // iccOpenLogicalChannel once
        inOrder.verify(mMockCi).iccOpenLogicalChannel(eq(ApduSender.ISD_R_AID), anyInt(), any());
        // iccTransmitApduLogicalChannel twice
        inOrder.verify(mMockCi, times(2)).iccTransmitApduLogicalChannel(eq(channel),
                 eq(channel | 10), eq(1), eq(2), eq(3), eq(0), eq("a"), anyBoolean(), any());
        // No iccCloseLogicalChannel
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testSendTwice_thenEndSession() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel,
                "A1A1A19000", "A1A1A19000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);
        EuiccSession.get(mContext).startSession(SESSION_ID);

        mSender.send((selectResponse, requestBuilder) -> requestBuilder.addApdu(
                10, 1, 2, 3, 0, "a"), mResponseCaptor, mHandler);
        mLooper.processAllMessages();
        mSender.send((selectResponse, requestBuilder) -> requestBuilder.addApdu(
                10, 1, 2, 3, 0, "a"), mResponseCaptor, mHandler);
        mLooper.processAllMessages();
        EuiccSession.get(mContext).endSession(SESSION_ID);
        mLooper.processAllMessages();

        assertEquals("A1A1A1", IccUtils.bytesToHexString(mResponseCaptor.response));
        InOrder inOrder = inOrder(mMockCi);
        // iccOpenLogicalChannel once
        inOrder.verify(mMockCi).iccOpenLogicalChannel(eq(ApduSender.ISD_R_AID), anyInt(), any());
        // iccTransmitApduLogicalChannel twice
        inOrder.verify(mMockCi, times(2)).iccTransmitApduLogicalChannel(eq(channel),
                 eq(channel | 10), eq(1), eq(2), eq(3), eq(0), eq("a"), anyBoolean(), any());
        // iccCloseLogicalChannel once
        inOrder.verify(mMockCi).iccCloseLogicalChannel(eq(channel), eq(true /*isEs10*/), any());
    }

    private int getChannelIdFromSharedPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(mContext)
                .getInt(SHARED_PREFS_KEY_CHANNEL_ID, -1);
    }

    private void clearSharedPreferences() {
        PreferenceManager.getDefaultSharedPreferences(mContext)
                .edit()
                .remove(SHARED_PREFS_KEY_CHANNEL_ID)
                .remove(SHARED_PREFS_KEY_CHANNEL_RESPONSE)
                .apply();
    }

    /**
     * Send an APDU to verify that the channel lock is not stuck (b/382549728).
     * Same as testSend(), but not verifying mMockCi interactions.
     */
    private void checkChannelLock() throws Exception {
        int channel = LogicalChannelMocker.mockOpenLogicalChannelResponse(mMockCi, "9000");
        LogicalChannelMocker.mockSendToLogicalChannel(mMockCi, channel, "A1A1A19000");
        LogicalChannelMocker.mockCloseLogicalChannel(mMockCi, channel, /* error= */ null);

        mSender.send((selectResponse, requestBuilder) -> requestBuilder.addApdu(
                10, 1, 2, 3, 0, "a"), mResponseCaptor, mHandler);
        mLooper.processAllMessages();

        assertEquals("A1A1A1", IccUtils.bytesToHexString(mResponseCaptor.response));
    }

    /** Clears the static states of EuiccSession. */
    private void resetEuiccSession() throws Exception {
        java.lang.reflect.Field field = EuiccSession.class.getDeclaredField("sInstance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
