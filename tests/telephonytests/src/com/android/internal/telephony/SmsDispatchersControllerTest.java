/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.telephony;

import static com.android.internal.telephony.SmsDispatchersController.PendingRequest;
import static com.android.internal.telephony.SmsResponse.NO_ERROR_CODE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.provider.Telephony.Sms.Intents;
import android.telephony.DisconnectCause;
import android.telephony.DomainSelectionService;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.test.FlakyTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Singleton;

import androidx.test.filters.SmallTest;

import com.android.ims.FeatureConnector;
import com.android.ims.ImsManager;
import com.android.internal.telephony.GsmAlphabet.TextEncodingDetails;
import com.android.internal.telephony.domainselection.DomainSelectionConnection;
import com.android.internal.telephony.domainselection.EmergencySmsDomainSelectionConnection;
import com.android.internal.telephony.domainselection.SmsDomainSelectionConnection;
import com.android.internal.telephony.emergency.EmergencyStateTracker;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.satellite.DatagramDispatcher;
import com.android.internal.telephony.uicc.IccUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class SmsDispatchersControllerTest extends TelephonyTest {
    /**
     * Inherits the SmsDispatchersController to verify the protected methods.
     */
    private static class TestSmsDispatchersController extends SmsDispatchersController {
        TestSmsDispatchersController(Phone phone, SmsStorageMonitor storageMonitor,
                SmsUsageMonitor usageMonitor, Looper looper, FeatureFlags featureFlags) {
            super(phone, storageMonitor, usageMonitor, looper, featureFlags);
        }

        public DomainSelectionConnectionHolder testGetDomainSelectionConnectionHolder(
                boolean emergency) {
            return getDomainSelectionConnectionHolder(emergency);
        }

        public void testSendData(String callingPackage, int callingUser,
                String destAddr, String scAddr, int destPort, byte[] data, PendingIntent sentIntent,
                PendingIntent deliveryIntent, boolean isForVvm, int uid) {
            sendData(callingPackage, callingUser, destAddr, scAddr,
                    destPort, data, sentIntent, deliveryIntent, isForVvm, uid);
        }

        public void testSendMultipartText(String destAddr, String scAddr,
                ArrayList<String> parts, ArrayList<PendingIntent> sentIntents,
                ArrayList<PendingIntent> deliveryIntents, Uri messageUri, String callingPkg,
                int callingUser, boolean persistMessage, int priority, boolean expectMore,
                int validityPeriod, long messageId, int uid) {
            sendMultipartText(destAddr, scAddr, parts, sentIntents, deliveryIntents, messageUri,
                    callingPkg, callingUser, persistMessage, priority, expectMore,
                    validityPeriod, messageId, uid);
        }

        public void testNotifySmsSentToEmergencyStateTracker(String destAddr, long messageId,
                boolean isOverIms, boolean isLastSmsPart) {
            notifySmsSent(getSmsTracker(destAddr, messageId), isOverIms,
                    isLastSmsPart, true/*success*/);
        }

        public void testNotifySmsSentFailedToEmergencyStateTracker(String destAddr,
                long messageId, boolean isOverIms) {
            notifySmsSent(getSmsTracker(destAddr, messageId), isOverIms,
                    true/*isLastSmsPart*/, false/*success*/);
        }

        public void testNotifySmsReceivedViaImsToEmergencyStateTracker(String origAddr) {
            notifySmsReceivedViaImsToEmergencyStateTracker(origAddr);
        }

        private SMSDispatcher.SmsTracker getSmsTracker(String destAddr, long messageId) {
            return new SMSDispatcher.SmsTracker(destAddr, messageId, "testMessage");
        }
    }

    /**
     * Inherits the SMSDispatcher to verify the abstract or protected methods.
     */
    protected static class TestSmsDispatcher extends SMSDispatcher {
        public TestSmsDispatcher(Phone phone, SmsDispatchersController smsDispatchersController,
                @NonNull FeatureFlags featureFlags) {
            super(phone, smsDispatchersController, featureFlags);
        }

        @Override
        public void sendData(String callingPackage, int callingUser, String destAddr,
                String scAddr, int destPort, byte[] data, PendingIntent sentIntent,
                PendingIntent deliveryIntent, boolean isForVvm, long uniqueMessageId, int uid) {
            super.sendData(callingPackage, callingUser, destAddr, scAddr, destPort,
                    data, sentIntent, deliveryIntent, isForVvm, uniqueMessageId, uid);
        }

        @Override
        public void sendSms(SmsTracker tracker) {
        }

        @Override
        public String getFormat() {
            return SmsConstants.FORMAT_3GPP;
        }

        @Override
        protected boolean shouldBlockSmsForEcbm() {
            return false;
        }

        @Override
        protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAdd, String destAdd,
                String message, boolean statusReportRequested, SmsHeader smsHeader,
                int priority,
                int validityPeriod) {
            return null;
        }

        @Override
        protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAdd, String destAdd,
                int destPort, byte[] message, boolean statusReportRequested) {
            return null;
        }

        @Override
        protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAdd, String destAdd,
                String message, boolean statusReportRequested, SmsHeader smsHeader,
                int priority,
                int validityPeriod, int messageRef) {
            return null;
        }

        @Override
        protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAdd, String destAdd,
                int destPort, byte[] message, boolean statusReportRequested, int messageRef) {
            return null;
        }

        @Override
        protected TextEncodingDetails calculateLength(CharSequence messageBody,
                boolean use7bitOnly) {
            return null;
        }
    }

    /**
     * Inherits the SMSDispatcher to verify the protected methods.
     */
    protected static class TestImsSmsDispatcher extends ImsSmsDispatcher {
        public TestImsSmsDispatcher(Phone phone, SmsDispatchersController smsDispatchersController,
                FeatureConnectorFactory factory, @NonNull FeatureFlags featureFlags) {
            super(phone, smsDispatchersController, factory, featureFlags);
        }

        @Override
        public void sendData(String callingPackage, int callingUser, String destAddr, String scAddr,
                int destPort, byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent,
                boolean isForVvm, long uniqueMessageId, int uid) {
            super.sendData(callingPackage, callingUser, destAddr, scAddr, destPort,
                    data, sentIntent, deliveryIntent, isForVvm, uniqueMessageId, uid);
        }

        @Override
        public String getFormat() {
            return SmsConstants.FORMAT_3GPP;
        }
    }

    private static final String ACTION_TEST_SMS_SENT = "TEST_SMS_SENT";

    // Mocked classes
    private FeatureFlags mFeatureFlags;
    private SMSDispatcher.SmsTracker mTracker;
    private PendingIntent mSentIntent;
    private TestImsSmsDispatcher mImsSmsDispatcher;
    private TestSmsDispatcher mGsmSmsDispatcher;
    private TestSmsDispatcher mCdmaSmsDispatcher;
    private SmsDomainSelectionConnection mSmsDsc;
    private EmergencySmsDomainSelectionConnection mEmergencySmsDsc;
    private EmergencyStateTracker mEmergencyStateTracker;
    private CompletableFuture<Integer> mEmergencySmsFuture;

    private TestSmsDispatchersController mSmsDispatchersController;
    private boolean mInjectionCallbackTriggered = false;
    private CompletableFuture<Integer> mDscFuture;
    private DatagramDispatcher mMockDatagramDispatcher;
    private int mCallingUserId;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        mTracker = mock(SMSDispatcher.SmsTracker.class);
        mFeatureFlags = mock(FeatureFlags.class);
        mMockDatagramDispatcher = mock(DatagramDispatcher.class);
        replaceInstance(DatagramDispatcher.class, "sInstance", null,
                mMockDatagramDispatcher);
        setupMockPackagePermissionChecks();
        mSmsDispatchersController = new TestSmsDispatchersController(mPhone, mSmsStorageMonitor,
                mSmsUsageMonitor, mTestableLooper.getLooper(), mFeatureFlags);
        mCallingUserId = mContext.getUserId();
        processAllMessages();
    }

    @After
    public void tearDown() throws Exception {
        mImsSmsDispatcher = null;
        mGsmSmsDispatcher = null;
        mCdmaSmsDispatcher = null;
        mSmsDsc = null;
        mEmergencySmsDsc = null;
        mDscFuture = null;
        mSmsDispatchersController.dispose();
        mSmsDispatchersController = null;
        mFeatureFlags = null;
        super.tearDown();
    }

    @Test
    @SmallTest
    @FlakyTest
    public void testSmsHandleStateUpdate() throws Exception {
        assertEquals(SmsConstants.FORMAT_UNKNOWN, mSmsDispatchersController.getImsSmsFormat());
        //Mock ImsNetWorkStateChange with GSM phone type
        switchImsSmsFormat(PhoneConstants.PHONE_TYPE_GSM);
        assertEquals(SmsConstants.FORMAT_3GPP, mSmsDispatchersController.getImsSmsFormat());
        assertTrue(mSmsDispatchersController.isIms());
    }

    @Test
    @SmallTest
    public void testReportSmsMemoryStatus() throws Exception {
        int eventReportMemoryStatusDone = 3;
        SmsStorageMonitor smsStorageMonnitor = new SmsStorageMonitor(mPhone, mFeatureFlags);
        Message result = smsStorageMonnitor.obtainMessage(eventReportMemoryStatusDone);
        ImsSmsDispatcher mImsSmsDispatcher = Mockito.mock(ImsSmsDispatcher.class);
        mSmsDispatchersController.setImsSmsDispatcher(mImsSmsDispatcher);
        mSmsDispatchersController.reportSmsMemoryStatus(result);
        AsyncResult ar = (AsyncResult) result.obj;
        verify(mImsSmsDispatcher).onMemoryAvailable();
        assertNull(ar.exception);
    }

    @Test
    @SmallTest
    public void testReportSmsMemoryStatusFailure() throws Exception {
        int eventReportMemoryStatusDone = 3;
        SmsStorageMonitor smsStorageMonnitor = new SmsStorageMonitor(mPhone, mFeatureFlags);
        Message result = smsStorageMonnitor.obtainMessage(eventReportMemoryStatusDone);
        mSmsDispatchersController.setImsSmsDispatcher(null);
        mSmsDispatchersController.reportSmsMemoryStatus(result);
        AsyncResult ar = (AsyncResult) result.obj;
        assertNotNull(ar.exception);
    }

    @Test
    @SmallTest
    @FlakyTest
    public void testSendImsGmsTest() throws Exception {
        switchImsSmsFormat(PhoneConstants.PHONE_TYPE_GSM);
        mSmsDispatchersController.sendText("111"/* desAddr*/, "222" /*scAddr*/, TAG,
                null, null, null, null, mCallingUserId, false, -1, false, -1, false, 0L,
                Process.INVALID_UID);
        verify(mSimulatedCommandsVerifier).sendImsGsmSms(eq("038122F2"),
                eq("0100038111F100001CD3F69C989EC3C3F431BA2C9F0FDF6EBAFCCD6697E5D4F29C0E"), eq(0),
                eq(0),
                any(Message.class));
    }

    @Test
    @SmallTest
    public void testSendTextMessageRefSequence() throws Exception {
        setUpSpySmsDispatchers();
        doReturn(true).when(mImsSmsDispatcher).isMessageRefIncrementViaTelephony();
        doReturn(true).when(mGsmSmsDispatcher).isMessageRefIncrementViaTelephony();
        int messageRef = mSmsDispatchersController.getMessageReference();

        doReturn(true).when(mImsSmsDispatcher).isAvailable();
        mSmsDispatchersController.sendText("1111", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        assertEquals(messageRef + 1, mSmsDispatchersController.getMessageReference());
        verify(mImsSmsDispatcher).sendText(eq("1111"), eq("2222"), eq("text"), eq(mSentIntent),
                any(), any(), eq("test-app"), eq(mCallingUserId), eq(false),
                eq(0), eq(false), eq(10), eq(false), eq(1L), eq(false), anyLong(),
                eq(Process.INVALID_UID));
        // PS->PS
        mSmsDispatchersController.sendText("1112", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        assertEquals(messageRef + 2, mSmsDispatchersController.getMessageReference());
        verify(mImsSmsDispatcher).sendText(eq("1112"), eq("2222"), eq("text"), eq(mSentIntent),
                any(), any(), eq("test-app"), eq(mCallingUserId), eq(false),
                eq(0), eq(false), eq(10), eq(false), eq(1L), eq(false), anyLong(),
                eq(Process.INVALID_UID));
        // PS->CS
        doReturn(false).when(mImsSmsDispatcher).isAvailable();
        mSmsDispatchersController.sendText("1113", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        assertEquals(messageRef + 3, mSmsDispatchersController.getMessageReference());
        verify(mGsmSmsDispatcher).sendText(eq("1113"), eq("2222"), eq("text"), eq(mSentIntent),
                any(), any(), eq("test-app"), eq(mCallingUserId), eq(false),
                eq(0), eq(false), eq(10), eq(false), eq(1L), eq(false), anyLong(),
                eq(Process.INVALID_UID));
        // CS->CS
        mSmsDispatchersController.sendText("1114", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        assertEquals(messageRef + 4, mSmsDispatchersController.getMessageReference());
        verify(mGsmSmsDispatcher).sendText(eq("1114"), eq("2222"), eq("text"), eq(mSentIntent),
                any(), any(), eq("test-app"), eq(mCallingUserId), eq(false),
                eq(0), eq(false), eq(10), eq(false), eq(1L), eq(false), anyLong(),
                eq(Process.INVALID_UID));
        // CS->PS
        doReturn(true).when(mImsSmsDispatcher).isAvailable();
        mSmsDispatchersController.sendText("1115", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        assertEquals(messageRef + 5, mSmsDispatchersController.getMessageReference());
        verify(mImsSmsDispatcher).sendText(eq("1115"), eq("2222"), eq("text"), eq(mSentIntent),
                any(), any(), eq("test-app"), eq(mCallingUserId), eq(false),
                eq(0), eq(false), eq(10), eq(false), eq(1L), eq(false), anyLong(),
                eq(Process.INVALID_UID));
    }

    @Test
    @SmallTest
    public void testMessageReferenceIncrementDuringFallback() throws Exception {
        setUpSpySmsDispatchers();
        doReturn(true).when(mImsSmsDispatcher).isAvailable();
        doReturn(true).when(mImsSmsDispatcher).isMessageRefIncrementViaTelephony();
        doReturn(true).when(mGsmSmsDispatcher).isMessageRefIncrementViaTelephony();
        int messageRef = mSmsDispatchersController.getMessageReference();

        doAnswer(invocation -> {
            mTracker = (SMSDispatcher.SmsTracker) invocation.getArgument(0);
            int token = mImsSmsDispatcher.mNextToken.get();
            mImsSmsDispatcher.mTrackers.put(token, mTracker);
            // Verify TP-MR increment only by 1
            assertEquals(messageRef + 1, mSmsDispatchersController.getMessageReference());
            // Limit retries to 1
            if (mTracker.mRetryCount < 1) {
                doReturn(false).when(mImsSmsDispatcher).isAvailable();
                mImsSmsDispatcher.getSmsListener().onSendSmsResult(token, 0,
                        ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK, 0, SmsResponse.NO_ERROR_CODE);
            }
            return 0;
        }).when(mImsSmsDispatcher).sendSms(any(SMSDispatcher.SmsTracker.class));

        // Send SMS
        mSmsDispatchersController.sendText("1113", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);

        ArgumentCaptor<SMSDispatcher.SmsTracker> captor =
                ArgumentCaptor.forClass(SMSDispatcher.SmsTracker.class);
        verify(mImsSmsDispatcher).sendSms(captor.capture());
        mTracker = captor.getValue();

        verify(mGsmSmsDispatcher).sendSms(eq(mTracker));
        // Verify TP-MR value is same as that was over IMS
        assertEquals(messageRef + 1, mSmsDispatchersController.getMessageReference());
    }

    @Test
    @SmallTest
    public void testMessageReferenceIncrementDuringImsRetry() throws Exception {
        setUpSpySmsDispatchers();
        doReturn(true).when(mImsSmsDispatcher).isAvailable();
        doReturn(true).when(mImsSmsDispatcher).isMessageRefIncrementViaTelephony();
        doReturn(true).when(mGsmSmsDispatcher).isMessageRefIncrementViaTelephony();
        int messageRef = mSmsDispatchersController.getMessageReference();

        doAnswer(invocation -> {
            mTracker = (SMSDispatcher.SmsTracker) invocation.getArgument(0);
            int token = mImsSmsDispatcher.mNextToken.get();
            mImsSmsDispatcher.mTrackers.put(token, mTracker);
            // Verify TP-MR increment by 1 only
            assertEquals(messageRef + 1, mSmsDispatchersController.getMessageReference());

            // Limit retries to 1
            if (mTracker.mRetryCount < 1) {
                mImsSmsDispatcher.getSmsListener().onSendSmsResult(token, 0,
                        ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK, 0, SmsResponse.NO_ERROR_CODE);
            }
            return 0;
        }).when(mImsSmsDispatcher).sendSms(any(SMSDispatcher.SmsTracker.class));

        // Send SMS
        mSmsDispatchersController.sendText("1113", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);

        // Verify SMS is sent over IMS twice
        verify(mImsSmsDispatcher, times(2)).sendSms(any(SMSDispatcher.SmsTracker.class));
        verify(mGsmSmsDispatcher, times(0)).sendSms(any(SMSDispatcher.SmsTracker.class));
    }

    @Test
    @SmallTest
    public void testSendImsGmsTestWithOutDesAddr() throws Exception {
        switchImsSmsFormat(PhoneConstants.PHONE_TYPE_GSM);
        mSmsDispatchersController.sendText(null, "222" /*scAddr*/, TAG,
                null, null, null, null, mCallingUserId, false, -1, false, -1, false, 0L,
                Process.INVALID_UID);
        verify(mSimulatedCommandsVerifier, times(0)).sendImsGsmSms(anyString(), anyString(),
                anyInt(), anyInt(), any(Message.class));
    }

    @Test
    @SmallTest
    @FlakyTest /* flakes 0.85% of the time on gce, 0.43% on marlin */
    public void testSendRetrySmsGsmTest() throws Exception {
        // newFormat will be based on voice technology will be GSM if phone type is not CDMA
        switchImsSmsFormat(PhoneConstants.PHONE_TYPE_GSM);
        replaceInstance(SMSDispatcher.SmsTracker.class, "mFormat", mTracker,
                SmsConstants.FORMAT_3GPP);
        mSmsDispatchersController.sendRetrySms(mTracker);
        verify(mSimulatedCommandsVerifier).sendImsGsmSms((String) isNull(), (String) isNull(),
                eq(0),
                eq(0), any(Message.class));
    }

    @Test
    @SmallTest
    public void testSendRetrySmsNullPdu() throws Exception {
        HashMap<String, Object> map = new HashMap<>();
        map.put("scAddr", "");
        map.put("destAddr", "");
        map.put("text", null);
        map.put("destPort", 0);
        switchImsSmsFormat(PhoneConstants.PHONE_TYPE_GSM);
        replaceInstance(SMSDispatcher.SmsTracker.class, "mFormat", mTracker,
                SmsConstants.FORMAT_3GPP2);
        when(mTracker.getData()).thenReturn(map);
        mSmsDispatchersController.sendRetrySms(mTracker);
        verify(mTracker).onFailed(eq(mContext), eq(SmsManager.RESULT_SMS_SEND_RETRY_FAILED),
                eq(NO_ERROR_CODE));
    }

    @Test
    @SmallTest
    public void testInjectNullSmsPdu() throws Exception {
        // unmock ActivityManager to be able to register receiver, create real PendingIntent and
        // receive TEST_INTENT
        restoreInstance(Singleton.class, "mInstance", mIActivityManagerSingleton);
        restoreInstance(ActivityManager.class, "IActivityManagerSingleton", null);

        // inject null sms pdu. This should cause intent to be received since pdu is null.
        mSmsDispatchersController.injectSmsPdu(null, SmsConstants.FORMAT_3GPP, true,
                (SmsDispatchersController.SmsInjectionCallback) result -> {
                    mInjectionCallbackTriggered = true;
                    assertEquals(Intents.RESULT_SMS_GENERIC_ERROR, result);
                }
        );
        processAllMessages();
        assertEquals(true, mInjectionCallbackTriggered);
    }

    @Test
    @SmallTest
    public void testSendImsGmsTestWithSmsc() {
        IccSmsInterfaceManager iccSmsInterfaceManager = Mockito.mock(IccSmsInterfaceManager.class);
        when(mPhone.getIccSmsInterfaceManager()).thenReturn(iccSmsInterfaceManager);
        when(iccSmsInterfaceManager.getSmscAddressFromIccEf("com.android.messaging"))
                .thenReturn("222");
        switchImsSmsFormat(PhoneConstants.PHONE_TYPE_GSM);

        mSmsDispatchersController.sendText("111", null /*scAddr*/, TAG,
                null, null, null, "com.android.messaging",
                mContext.getUserId(), false, -1, false,
                -1, false, 0L, Process.INVALID_UID);
        byte[] smscbyte = PhoneNumberUtils.networkPortionToCalledPartyBCDWithLength(
                "222");
        String smsc = IccUtils.bytesToHexString(smscbyte);
        verify(mSimulatedCommandsVerifier).sendImsGsmSms(eq(smsc), anyString(),
                anyInt(), anyInt(), any(Message.class));
    }

    @Test
    @SmallTest
    public void testSendDataWhenDomainPs() throws Exception {
        sendDataWithDomainSelection(NetworkRegistrationInfo.DOMAIN_PS, false);
    }

    @Test
    @SmallTest
    public void testSendDataWhenDomainCsAndGsm() throws Exception {
        when(mPhone.getPhoneType()).thenReturn(PhoneConstants.PHONE_TYPE_GSM);
        sendDataWithDomainSelection(NetworkRegistrationInfo.DOMAIN_PS, false);
    }

    @Test
    @SmallTest
    public void testSendTextWhenDomainPs() throws Exception {
        sendTextWithDomainSelection(NetworkRegistrationInfo.DOMAIN_PS, false);
    }

    @Test
    @SmallTest
    public void testSendTextWhenDomainCsAndGsm() throws Exception {
        when(mPhone.getPhoneType()).thenReturn(PhoneConstants.PHONE_TYPE_GSM);
        sendTextWithDomainSelection(NetworkRegistrationInfo.DOMAIN_PS, false);
    }

    @Test
    @SmallTest
    public void testSendMultipartTextWhenDomainPs() throws Exception {
        sendMultipartTextWithDomainSelection(NetworkRegistrationInfo.DOMAIN_PS, false);
    }

    @Test
    @SmallTest
    public void testSendMultipartTextWhenDomainCsAndGsm() throws Exception {
        when(mPhone.getPhoneType()).thenReturn(PhoneConstants.PHONE_TYPE_GSM);
        sendMultipartTextWithDomainSelection(NetworkRegistrationInfo.DOMAIN_PS, false);
    }

    @Test
    @SmallTest
    public void testSendRetrySmsWhenDomainPs() throws Exception {
        sendRetrySmsWithDomainSelection(NetworkRegistrationInfo.DOMAIN_PS,
                PhoneConstants.PHONE_TYPE_GSM, SmsConstants.FORMAT_3GPP);
    }

    @Test
    @SmallTest
    public void testSendRetrySmsWhenDomainCsAndGsm() throws Exception {
        sendRetrySmsWithDomainSelection(NetworkRegistrationInfo.DOMAIN_CS,
                PhoneConstants.PHONE_TYPE_GSM, SmsConstants.FORMAT_3GPP);
    }

    @Test
    @SmallTest
    public void testSendRetrySmsWhenImsAlreadyUsedAndGsm() throws Exception {
        sendRetrySmsWhenImsAlreadyUsed(PhoneConstants.PHONE_TYPE_GSM, SmsConstants.FORMAT_3GPP);
    }

    @Test
    @SmallTest
    public void testSendTextForEmergencyWhenDomainPs() throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        mSmsDispatchersController.sendText("911", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(true);
        verify(mEmergencySmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isEmergency());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        mDscFuture.complete(NetworkRegistrationInfo.DOMAIN_PS);
        processAllMessages();

        verify(mEmergencySmsDsc).finishSelection();
        verify(mImsSmsDispatcher).sendText(eq("911"), eq("2222"), eq("text"), eq(mSentIntent),
                any(), any(), eq("test-app"), eq(mCallingUserId), eq(false),
                eq(0), eq(false), eq(10), eq(false), eq(1L), eq(false), anyLong(),
                eq(Process.INVALID_UID));
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());
    }

    @Test
    @SmallTest
    public void testSendTextForEmergencyWhenEmergencyStateTrackerReturnsFailure() throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();
        setUpEmergencyStateTracker(DisconnectCause.OUT_OF_SERVICE);

        mSmsDispatchersController.sendText("911", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        // Verify the domain selection requested regardless of the result of EmergencyStateTracker.
        verify(mEmergencySmsDsc).requestDomainSelection(any(), any());
    }

    @Test
    @SmallTest
    public void testSendMultipartTextForEmergencyWhenDomainPs() throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        ArrayList<String> parts = new ArrayList<>();
        ArrayList<PendingIntent> sentIntents = new ArrayList<>();
        ArrayList<PendingIntent> deliveryIntents = new ArrayList<>();
        mSmsDispatchersController.testSendMultipartText("911", "2222", parts, sentIntents,
                deliveryIntents, null, "test-app", mCallingUserId, false, 0, false, 10, 1L,
                Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(true);
        verify(mEmergencySmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isEmergency());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        mDscFuture.complete(NetworkRegistrationInfo.DOMAIN_PS);
        processAllMessages();

        verify(mEmergencySmsDsc).finishSelection();
        verify(mImsSmsDispatcher).sendMultipartText(eq("911"), eq("2222"), eq(parts),
                eq(sentIntents), eq(deliveryIntents), any(), eq("test-app"),
                eq(mCallingUserId), eq(false), eq(0), eq(false),
                eq(10), eq(1L), anyLong(), eq(Process.INVALID_UID));
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());
    }

    @Test
    @SmallTest
    public void testSendRetrySmsForEmergencyWhenDomainPs() throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        when(mPhone.getPhoneType()).thenReturn(PhoneConstants.PHONE_TYPE_GSM);
        when(mImsSmsDispatcher.getFormat()).thenReturn(SmsConstants.FORMAT_3GPP);
        when(mCdmaSmsDispatcher.getFormat()).thenReturn(SmsConstants.FORMAT_3GPP2);
        when(mGsmSmsDispatcher.getFormat()).thenReturn(SmsConstants.FORMAT_3GPP);
        replaceInstance(SMSDispatcher.SmsTracker.class,
                "mFormat", mTracker, SmsConstants.FORMAT_3GPP);
        replaceInstance(SMSDispatcher.SmsTracker.class, "mDestAddress", mTracker, "911");

        mSmsDispatchersController.sendRetrySms(mTracker);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(true);
        verify(mEmergencySmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isEmergency());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        mDscFuture.complete(NetworkRegistrationInfo.DOMAIN_PS);
        processAllMessages();

        verify(mEmergencySmsDsc).finishSelection();
        verify(mImsSmsDispatcher).sendSms(eq(mTracker));
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());
    }

    @Test
    @SmallTest
    public void testNotifySmsSentToEmergencyStateTrackerOnDomainCs() throws Exception {
        setUpDomainSelectionEnabled(true);
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        mSmsDispatchersController.testNotifySmsSentToEmergencyStateTracker("911", 1L, false, true);
        processAllMessages();

        verify(mTelephonyManager).isEmergencyNumber(eq("911"));
        verify(mEmergencyStateTracker)
                .endSms(eq("1"), eq(true), eq(NetworkRegistrationInfo.DOMAIN_CS), eq(true));
    }

    @Test
    @SmallTest
    public void testNotifySmsSentToEmergencyStateTrackerOnDomainPs() throws Exception {
        setUpDomainSelectionEnabled(true);
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        mSmsDispatchersController.testNotifySmsSentToEmergencyStateTracker("911", 1L, true, true);
        processAllMessages();

        verify(mTelephonyManager).isEmergencyNumber(eq("911"));
        verify(mEmergencyStateTracker)
                .endSms(eq("1"), eq(true), eq(NetworkRegistrationInfo.DOMAIN_PS), eq(true));
    }

    @Test
    @SmallTest
    public void testNotifySmsSentToEmergencyStateTrackerWithNonEmergencyNumber() throws Exception {
        setUpDomainSelectionEnabled(true);
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        mSmsDispatchersController.testNotifySmsSentToEmergencyStateTracker("1234", 1L, true, true);
        processAllMessages();

        verify(mTelephonyManager).isEmergencyNumber(eq("1234"));
        verify(mEmergencyStateTracker, never())
                .endSms(anyString(), anyBoolean(), anyInt(), anyBoolean());
    }

    @Test
    @SmallTest
    public void testNotifySmsSentToEmergencyStateTrackerWithoutEmergencyStateTracker()
            throws Exception {
        setUpDomainSelectionEnabled(true);
        mSmsDispatchersController.testNotifySmsSentToEmergencyStateTracker("911", 1L, true, true);

        verify(mTelephonyManager, never()).isEmergencyNumber(anyString());
    }

    @Test
    @SmallTest
    public void testNotifySmsSentFailedToEmergencyStateTrackerOnDomainCs() throws Exception {
        setUpDomainSelectionEnabled(true);
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        mSmsDispatchersController.testNotifySmsSentFailedToEmergencyStateTracker("911", 1L, false);
        processAllMessages();

        verify(mTelephonyManager).isEmergencyNumber(eq("911"));
        verify(mEmergencyStateTracker)
                .endSms(eq("1"), eq(false), eq(NetworkRegistrationInfo.DOMAIN_CS), eq(true));
    }

    @Test
    @SmallTest
    public void testNotifySmsSentFailedToEmergencyStateTrackerOnDomainPs() throws Exception {
        setUpDomainSelectionEnabled(true);
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        mSmsDispatchersController.testNotifySmsSentFailedToEmergencyStateTracker("911", 1L, true);
        processAllMessages();

        verify(mTelephonyManager).isEmergencyNumber(eq("911"));
        verify(mEmergencyStateTracker)
                .endSms(eq("1"), eq(false), eq(NetworkRegistrationInfo.DOMAIN_PS), eq(true));
    }

    @Test
    @SmallTest
    public void testNotifySmsSentFailedToEmergencyStateTrackerWithNonEmergencyNumber()
            throws Exception {
        setUpDomainSelectionEnabled(true);
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        mSmsDispatchersController.testNotifySmsSentFailedToEmergencyStateTracker("1234", 1L, true);
        processAllMessages();

        verify(mTelephonyManager).isEmergencyNumber(eq("1234"));
        verify(mEmergencyStateTracker, never())
                .endSms(anyString(), anyBoolean(), anyInt(), anyBoolean());
    }

    @Test
    @SmallTest
    public void testNotifySmsReceivedViaImsToEmergencyStateTracker() throws Exception {
        setUpDomainSelectionEnabled(true);
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        mSmsDispatchersController.testNotifySmsReceivedViaImsToEmergencyStateTracker("911");
        processAllMessages();

        verify(mTelephonyManager).isEmergencyNumber(eq("911"));
        verify(mEmergencyStateTracker).onEmergencySmsReceived();
    }

    @Test
    @SmallTest
    public void testNotifySmsReceivedViaImsToEmergencyStateTrackerWithNonEmergencyNumber()
            throws Exception {
        setUpDomainSelectionEnabled(true);
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        mSmsDispatchersController.testNotifySmsReceivedViaImsToEmergencyStateTracker("1234");
        processAllMessages();

        verify(mTelephonyManager).isEmergencyNumber(eq("1234"));
        verify(mEmergencyStateTracker, never()).onEmergencySmsReceived();
    }

    @Test
    @SmallTest
    public void testNotifyDomainSelectionTerminatedWhenImsAvailableAndNormalSms() throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();
        when(mImsSmsDispatcher.isAvailable()).thenReturn(true);

        mSmsDispatchersController.sendText("1111", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(false);
        ArgumentCaptor<DomainSelectionConnection.DomainSelectionConnectionCallback> captor =
                ArgumentCaptor.forClass(
                        DomainSelectionConnection.DomainSelectionConnectionCallback.class);
        verify(mSmsDsc).requestDomainSelection(any(), captor.capture());
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        DomainSelectionConnection.DomainSelectionConnectionCallback callback = captor.getValue();
        assertNotNull(callback);
        callback.onSelectionTerminated(DisconnectCause.LOCAL);
        processAllMessages();

        verify(mSmsDsc, never()).finishSelection();
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());

        verify(mImsSmsDispatcher).sendText(eq("1111"), eq("2222"), eq("text"), eq(mSentIntent),
                any(), any(), eq("test-app"), eq(mCallingUserId),
                eq(false), eq(0), eq(false), eq(10), eq(false), eq(1L), eq(false), anyLong(),
                eq(Process.INVALID_UID));
    }

    @Test
    @SmallTest
    public void testNotifyDomainSelectionTerminatedWhenImsNotAvailableAndEmergencySms()
            throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);
        when(mImsSmsDispatcher.isAvailable()).thenReturn(false);
        when(mImsSmsDispatcher.isEmergencySmsSupport(anyString())).thenReturn(true);

        mSmsDispatchersController.sendText("911", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false,
                10, false, 1L, false, Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(true);
        ArgumentCaptor<DomainSelectionConnection.DomainSelectionConnectionCallback> captor =
                ArgumentCaptor.forClass(
                        DomainSelectionConnection.DomainSelectionConnectionCallback.class);
        verify(mEmergencySmsDsc).requestDomainSelection(any(), captor.capture());
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        DomainSelectionConnection.DomainSelectionConnectionCallback callback = captor.getValue();
        assertNotNull(callback);
        callback.onSelectionTerminated(DisconnectCause.LOCAL);
        processAllMessages();

        verify(mEmergencySmsDsc, never()).finishSelection();
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());

        verify(mImsSmsDispatcher).sendText(eq("911"), eq("2222"), eq("text"), eq(mSentIntent),
                any(), any(), eq("test-app"), eq(0), eq(false), eq(0), eq(false), eq(10),
                eq(false), eq(1L), eq(false), anyLong(), eq(Process.INVALID_UID));
    }

    @Test
    @SmallTest
    public void testSendTextContinuously() throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();

        mSmsDispatchersController.sendText("1111", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(false);
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        mSmsDispatchersController.sendText("1111", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        verify(mSmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder.getConnection());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(2, holder.getPendingRequests().size());

        mDscFuture.complete(NetworkRegistrationInfo.DOMAIN_PS);
        processAllMessages();

        verify(mSmsDsc).finishSelection();
        verify(mImsSmsDispatcher, times(2)).sendText(eq("1111"), eq("2222"),
                eq("text"), eq(mSentIntent), any(), any(), eq("test-app"), eq(mCallingUserId),
                eq(false), eq(0), eq(false), eq(10), eq(false), eq(1L),
                eq(false), anyLong(), eq(Process.INVALID_UID));
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());
    }

    @Test
    @SmallTest
    public void testSendTextWhenDomainSelectionFinishedAndNewTextSent() throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();

        mSmsDispatchersController.sendText("1111", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(false);
        verify(mSmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        // Expect that finishDomainSelection is called while a new pending request is posted.
        mDscFuture.complete(NetworkRegistrationInfo.DOMAIN_PS);

        SmsDomainSelectionConnection newSmsDsc = Mockito.mock(SmsDomainSelectionConnection.class);
        mSmsDispatchersController.setDomainSelectionResolverProxy(
                new SmsDispatchersController.DomainSelectionResolverProxy() {
                    @Override
                    @Nullable
                    public DomainSelectionConnection getDomainSelectionConnection(Phone phone,
                            @DomainSelectionService.SelectorType int selectorType,
                            boolean isEmergency) {
                        return newSmsDsc;
                    }

                    @Override
                    public boolean isDomainSelectionSupported() {
                        return true;
                    }
                });
        CompletableFuture newDscFuture = new CompletableFuture<>();
        when(newSmsDsc.requestDomainSelection(
                any(DomainSelectionService.SelectionAttributes.class),
                any(DomainSelectionConnection.DomainSelectionConnectionCallback.class)))
                .thenReturn(newDscFuture);

        // Expect that new domain selection connection is created and domain selection is performed.
        mSmsDispatchersController.sendText("1111", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        verify(mSmsDsc).finishSelection();

        verify(newSmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder.getConnection());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        newDscFuture.complete(NetworkRegistrationInfo.DOMAIN_PS);
        processAllMessages();

        verify(newSmsDsc).finishSelection();
        verify(mImsSmsDispatcher, times(2)).sendText(eq("1111"), eq("2222"), eq("text"),
                eq(mSentIntent), any(), any(), eq("test-app"), eq(mCallingUserId), eq(false), eq(0),
                eq(false), eq(10), eq(false), eq(1L), eq(false), anyLong(),
                eq(Process.INVALID_UID));
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());
    }

    @Test
    @SmallTest
    public void testSendTextForEmergencyWhenDomainSelectionFinishedAndNewTextSent()
            throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);

        mSmsDispatchersController.sendText("911", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(true);
        verify(mEmergencySmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isEmergency());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        // Expect that finishDomainSelection is called while a new pending request is posted.
        mDscFuture.complete(NetworkRegistrationInfo.DOMAIN_PS);

        EmergencySmsDomainSelectionConnection newEmergencySmsDsc =
                Mockito.mock(EmergencySmsDomainSelectionConnection.class);
        mSmsDispatchersController.setDomainSelectionResolverProxy(
                new SmsDispatchersController.DomainSelectionResolverProxy() {
                    @Override
                    @Nullable
                    public DomainSelectionConnection getDomainSelectionConnection(Phone phone,
                            @DomainSelectionService.SelectorType int selectorType,
                            boolean isEmergency) {
                        return newEmergencySmsDsc;
                    }

                    @Override
                    public boolean isDomainSelectionSupported() {
                        return true;
                    }
                });
        CompletableFuture newDscFuture = new CompletableFuture<>();
        when(newEmergencySmsDsc.requestDomainSelection(
                any(DomainSelectionService.SelectionAttributes.class),
                any(DomainSelectionConnection.DomainSelectionConnectionCallback.class)))
                .thenReturn(newDscFuture);

        // Expect that new domain selection connection is created and domain selection is performed.
        mSmsDispatchersController.sendText("911", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        verify(mEmergencySmsDsc).finishSelection();

        verify(newEmergencySmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder.getConnection());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        newDscFuture.complete(NetworkRegistrationInfo.DOMAIN_PS);
        processAllMessages();

        verify(newEmergencySmsDsc).finishSelection();
        verify(mImsSmsDispatcher, times(2)).sendText(eq("911"), eq("2222"), eq("text"),
                eq(mSentIntent), any(), any(), eq("test-app"), eq(0), eq(false), eq(0), eq(false),
                eq(10), eq(false), eq(1L), eq(false), anyLong(),
                eq(Process.INVALID_UID));
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());
    }

    @Test
    @SmallTest
    public void testSendTextFallbackWhenDomainSelectionConnectionNotCreated() throws Exception {
        mSmsDispatchersController.setDomainSelectionResolverProxy(
                new SmsDispatchersController.DomainSelectionResolverProxy() {
                    @Override
                    @Nullable
                    public DomainSelectionConnection getDomainSelectionConnection(Phone phone,
                            @DomainSelectionService.SelectorType int selectorType,
                            boolean isEmergency) {
                        return null;
                    }

                    @Override
                    public boolean isDomainSelectionSupported() {
                        return true;
                    }
                });
        setUpSmsDispatchers();
        when(mImsSmsDispatcher.isAvailable()).thenReturn(true);

        // Expect that creating a domain selection connection is failed and
        // fallback to the legacy implementation.
        mSmsDispatchersController.sendText("1111", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(false);
        assertNotNull(holder);
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());

        verify(mImsSmsDispatcher).sendText(eq("1111"), eq("2222"), eq("text"), eq(mSentIntent),
                any(), any(), eq("test-app"), eq(mCallingUserId), eq(false), eq(0),
                eq(false), eq(10), eq(false), eq(1L), eq(false), anyLong(),
                eq(Process.INVALID_UID));
    }

    @Test
    @SmallTest
    public void testSendTextFallbackForEmergencyWhenDomainSelectionConnectionNotCreated()
            throws Exception {
        mSmsDispatchersController.setDomainSelectionResolverProxy(
                new SmsDispatchersController.DomainSelectionResolverProxy() {
                    @Override
                    @Nullable
                    public DomainSelectionConnection getDomainSelectionConnection(Phone phone,
                            @DomainSelectionService.SelectorType int selectorType,
                            boolean isEmergency) {
                        return null;
                    }

                    @Override
                    public boolean isDomainSelectionSupported() {
                        return true;
                    }
                });
        setUpSmsDispatchers();
        setUpEmergencyStateTracker(DisconnectCause.NOT_DISCONNECTED);
        when(mImsSmsDispatcher.isAvailable()).thenReturn(true);

        // Expect that creating a domain selection connection is failed and
        // fallback to the legacy implementation.
        mSmsDispatchersController.sendText("911", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(true);
        assertNotNull(holder);
        assertNull(holder.getConnection());
        assertTrue(holder.isEmergency());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());

        verify(mImsSmsDispatcher).sendText(eq("911"), eq("2222"), eq("text"), eq(mSentIntent),
                any(), any(), eq("test-app"), eq(mCallingUserId), eq(false), eq(0), eq(false),
                eq(10), eq(false), eq(1L), eq(false), anyLong(), eq(Process.INVALID_UID));
    }

    private void switchImsSmsFormat(int phoneType) {
        mSimulatedCommands.setImsRegistrationState(new int[]{1, phoneType});
        mSimulatedCommands.notifyImsNetworkStateChanged();
        /* handle EVENT_IMS_STATE_DONE */
        processAllMessages();
        assertTrue(mSmsDispatchersController.isIms());
    }

    @Test
    public void testSetImsManager() {
        ImsManager imsManager = mock(ImsManager.class);
        assertTrue(mSmsDispatchersController.setImsManager(imsManager));
    }

    @Test
    public void testSendSmsToDatagramDispatcher() {
        when(mSatelliteController.shouldSendSmsToDatagramDispatcher(any(Phone.class)))
                .thenReturn(true);
        mSmsDispatchersController.sendText("1111", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();
        verify(mMockDatagramDispatcher).sendSms(any());

        clearInvocations(mMockDatagramDispatcher);
        ArrayList<String> parts = new ArrayList<>();
        ArrayList<PendingIntent> sentIntents = new ArrayList<>();
        ArrayList<PendingIntent> deliveryIntents = new ArrayList<>();
        mSmsDispatchersController.testSendMultipartText("1111", "2222", parts, sentIntents,
                deliveryIntents, null, "test-app", mCallingUserId, false, 0, false, 10, 1L,
                Process.INVALID_UID);
        processAllMessages();
        verify(mMockDatagramDispatcher).sendSms(any());
    }

    @Test
    public void testSendCarrierRoamingNbIotNtnText() {
        PendingRequest pendingRequest = createPendingRequest();
        switchImsSmsFormat(PhoneConstants.PHONE_TYPE_GSM);
        mSmsDispatchersController.sendCarrierRoamingNbIotNtnText(pendingRequest);
        processAllMessages();
        verify(mSimulatedCommandsVerifier, times(1)).sendImsGsmSms(anyString(), anyString(),
                anyInt(), anyInt(), any(Message.class));
    }

    private void setUpDomainSelectionEnabled(boolean enabled) {
        mSmsDispatchersController.setDomainSelectionResolverProxy(
                new SmsDispatchersController.DomainSelectionResolverProxy() {
                    @Override
                    @Nullable
                    public DomainSelectionConnection getDomainSelectionConnection(Phone phone,
                            @DomainSelectionService.SelectorType int selectorType,
                            boolean isEmergency) {
                        return null;
                    }

                    @Override
                    public boolean isDomainSelectionSupported() {
                        return true;
                    }
                });
    }

    private void setUpDomainSelectionConnection() {
        mEmergencySmsDsc = Mockito.mock(EmergencySmsDomainSelectionConnection.class);
        mSmsDsc = Mockito.mock(SmsDomainSelectionConnection.class);
        mSmsDispatchersController.setDomainSelectionResolverProxy(
                new SmsDispatchersController.DomainSelectionResolverProxy() {
                    @Override
                    @Nullable
                    public DomainSelectionConnection getDomainSelectionConnection(Phone phone,
                            @DomainSelectionService.SelectorType int selectorType,
                            boolean isEmergency) {
                        return isEmergency ? mEmergencySmsDsc : mSmsDsc;
                    }

                    @Override
                    public boolean isDomainSelectionSupported() {
                        return true;
                    }
                });

        mDscFuture = new CompletableFuture<>();
        when(mSmsDsc.requestDomainSelection(
                any(DomainSelectionService.SelectionAttributes.class),
                any(DomainSelectionConnection.DomainSelectionConnectionCallback.class)))
                .thenReturn(mDscFuture);
        when(mEmergencySmsDsc.requestDomainSelection(
                any(DomainSelectionService.SelectionAttributes.class),
                any(DomainSelectionConnection.DomainSelectionConnectionCallback.class)))
                .thenReturn(mDscFuture);
    }

    private void setUpSmsDispatchers() throws Exception {
        mImsSmsDispatcher = Mockito.mock(TestImsSmsDispatcher.class);
        mGsmSmsDispatcher = Mockito.mock(TestSmsDispatcher.class);
        mCdmaSmsDispatcher = Mockito.mock(TestSmsDispatcher.class);

        replaceInstance(SmsDispatchersController.class, "mImsSmsDispatcher",
                mSmsDispatchersController, mImsSmsDispatcher);
        replaceInstance(SmsDispatchersController.class, "mGsmDispatcher",
                mSmsDispatchersController, mGsmSmsDispatcher);
        replaceInstance(SmsDispatchersController.class, "mCdmaDispatcher",
                mSmsDispatchersController, mCdmaSmsDispatcher);

        when(mTelephonyManager.isEmergencyNumber(eq("911"))).thenReturn(true);

        mSentIntent = PendingIntent.getBroadcast(TestApplication.getAppContext(), 0,
                new Intent(ACTION_TEST_SMS_SENT), PendingIntent.FLAG_MUTABLE
                        | PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT);
    }

    private void setUpSpySmsDispatchers() throws Exception {
        ImsSmsDispatcher.FeatureConnectorFactory mConnectorFactory = mock(
                ImsSmsDispatcher.FeatureConnectorFactory.class);
        FeatureConnector mMockConnector = mock(FeatureConnector.class);
        when(mConnectorFactory.create(any(), anyInt(), anyString(), any(), any())).thenReturn(
                mMockConnector);
        mImsSmsDispatcher =
                spy(new TestImsSmsDispatcher(mPhone, mSmsDispatchersController, mConnectorFactory,
                        mFeatureFlags));

        mGsmSmsDispatcher = spy(
                new TestSmsDispatcher(mPhone, mSmsDispatchersController, mFeatureFlags));

        mCdmaSmsDispatcher = Mockito.mock(TestSmsDispatcher.class);
        when(mCdmaSmsDispatcher.getFormat()).thenReturn(SmsConstants.FORMAT_3GPP2);

        replaceInstance(SmsDispatchersController.class, "mImsSmsDispatcher",
                mSmsDispatchersController, mImsSmsDispatcher);
        replaceInstance(SmsDispatchersController.class, "mGsmDispatcher",
                mSmsDispatchersController, mGsmSmsDispatcher);
        replaceInstance(SmsDispatchersController.class, "mCdmaDispatcher",
                mSmsDispatchersController, mCdmaSmsDispatcher);
    }

    private void setUpEmergencyStateTracker(int result) throws Exception {
        mEmergencySmsFuture = new CompletableFuture<Integer>();
        mEmergencyStateTracker = Mockito.mock(EmergencyStateTracker.class);
        replaceInstance(SmsDispatchersController.class, "mEmergencyStateTracker",
                mSmsDispatchersController, mEmergencyStateTracker);
        when(mEmergencyStateTracker.startEmergencySms(any(Phone.class), anyString(), anyBoolean()))
                .thenReturn(mEmergencySmsFuture);
        doNothing().when(mEmergencyStateTracker)
                .endSms(anyString(), anyBoolean(), anyInt(), anyBoolean());
        mEmergencySmsFuture.complete(result);
        when(mTelephonyManager.isEmergencyNumber(eq("911"))).thenReturn(true);
    }

    private void sendDataWithDomainSelection(@NetworkRegistrationInfo.Domain int domain,
            boolean isCdmaMo) throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();

        byte[] data = new byte[]{0x01};
        mSmsDispatchersController.testSendData(
                "test-app", mCallingUserId, "1111", "2222", 8080, data, mSentIntent, null, false,
                Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(false);
        verify(mSmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        mDscFuture.complete(domain);
        processAllMessages();

        verify(mSmsDsc).finishSelection();
        if (domain == NetworkRegistrationInfo.DOMAIN_PS) {
            verify(mImsSmsDispatcher).sendData(eq("test-app"), eq(0), eq("1111"), eq("2222"),
                    eq(8080), eq(data), eq(mSentIntent), any(), eq(false), anyLong(),
                    eq(Process.INVALID_UID));
        } else if (isCdmaMo) {
            verify(mCdmaSmsDispatcher).sendData(eq("test-app"), eq(0), eq("1111"), eq("2222"),
                    eq(8080), eq(data), eq(mSentIntent), any(), eq(false), anyLong(),
                    eq(Process.INVALID_UID));
        } else {
            verify(mGsmSmsDispatcher).sendData(eq("test-app"), eq(0), eq("1111"), eq("2222"),
                    eq(8080), eq(data), eq(mSentIntent), any(), eq(false), anyLong(),
                    eq(Process.INVALID_UID));
        }
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());
    }

    private void sendTextWithDomainSelection(@NetworkRegistrationInfo.Domain int domain,
            boolean isCdmaMo) throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();

        mSmsDispatchersController.sendText("1111", "2222", "text", mSentIntent, null, null,
                "test-app", mCallingUserId, false, 0, false, 10, false, 1L, false,
                Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(false);
        verify(mSmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        mDscFuture.complete(domain);
        processAllMessages();

        verify(mSmsDsc).finishSelection();
        if (domain == NetworkRegistrationInfo.DOMAIN_PS) {
            verify(mImsSmsDispatcher).sendText(eq("1111"), eq("2222"), eq("text"), eq(mSentIntent),
                    any(), any(), eq("test-app"), eq(0), eq(false), eq(0), eq(false), eq(10),
                    eq(false), eq(1L), eq(false), anyLong(), eq(Process.INVALID_UID));
        } else if (isCdmaMo) {
            verify(mCdmaSmsDispatcher).sendText(eq("1111"), eq("2222"), eq("text"), eq(mSentIntent),
                    any(), any(), eq("test-app"), eq(0), eq(false), eq(0), eq(false), eq(10),
                    eq(false), eq(1L), eq(false), anyLong(), eq(Process.INVALID_UID));
        } else {
            verify(mGsmSmsDispatcher).sendText(eq("1111"), eq("2222"), eq("text"), eq(mSentIntent),
                    any(), any(), eq("test-app"), eq(0), eq(false), eq(0), eq(false), eq(10),
                    eq(false), eq(1L), eq(false), anyLong(), eq(Process.INVALID_UID));
        }
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());
    }

    private void sendMultipartTextWithDomainSelection(@NetworkRegistrationInfo.Domain int domain,
            boolean isCdmaMo) throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();

        ArrayList<String> parts = new ArrayList<>();
        ArrayList<PendingIntent> sentIntents = new ArrayList<>();
        ArrayList<PendingIntent> deliveryIntents = new ArrayList<>();
        mSmsDispatchersController.testSendMultipartText("1111", "2222", parts, sentIntents,
                deliveryIntents, null, "test-app", mCallingUserId, false, 0, false, 10, 1L,
                Process.INVALID_UID);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(false);
        verify(mSmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        mDscFuture.complete(domain);
        processAllMessages();

        verify(mSmsDsc).finishSelection();
        if (domain == NetworkRegistrationInfo.DOMAIN_PS) {
            verify(mImsSmsDispatcher).sendMultipartText(eq("1111"), eq("2222"), eq(parts),
                    eq(sentIntents), eq(deliveryIntents), any(), eq("test-app"), eq(mCallingUserId),
                    eq(false), eq(0), eq(false), eq(10), eq(1L), anyLong(),
                    eq(Process.INVALID_UID));
        } else if (isCdmaMo) {
            verify(mCdmaSmsDispatcher).sendMultipartText(eq("1111"), eq("2222"), eq(parts),
                    eq(sentIntents), eq(deliveryIntents), any(), eq("test-app"), eq(mCallingUserId),
                    eq(false), eq(0),
                    eq(false), eq(10), eq(1L), anyLong(), eq(Process.INVALID_UID));
        } else {
            verify(mGsmSmsDispatcher).sendMultipartText(eq("1111"), eq("2222"), eq(parts),
                    eq(sentIntents), eq(deliveryIntents), any(), eq("test-app"), eq(mCallingUserId),
                    eq(false), eq(0), eq(false), eq(10), eq(1L), anyLong(),
                    eq(Process.INVALID_UID));
        }
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());
    }

    private void sendRetrySmsWithDomainSelection(@NetworkRegistrationInfo.Domain int domain,
            int phoneType, String smsFormat) throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();
        when(mPhone.getPhoneType()).thenReturn(phoneType);
        when(mImsSmsDispatcher.getFormat()).thenReturn(SmsConstants.FORMAT_3GPP);
        when(mCdmaSmsDispatcher.getFormat()).thenReturn(SmsConstants.FORMAT_3GPP2);
        when(mGsmSmsDispatcher.getFormat()).thenReturn(SmsConstants.FORMAT_3GPP);
        replaceInstance(SMSDispatcher.SmsTracker.class, "mFormat", mTracker, smsFormat);

        mSmsDispatchersController.sendRetrySms(mTracker);
        processAllMessages();

        SmsDispatchersController.DomainSelectionConnectionHolder holder =
                mSmsDispatchersController.testGetDomainSelectionConnectionHolder(false);
        verify(mSmsDsc).requestDomainSelection(any(), any());
        assertNotNull(holder);
        assertNotNull(holder.getConnection());
        assertTrue(holder.isDomainSelectionRequested());
        assertEquals(1, holder.getPendingRequests().size());

        mDscFuture.complete(domain);
        processAllMessages();

        verify(mSmsDsc).finishSelection();
        if (domain == NetworkRegistrationInfo.DOMAIN_PS) {
            verify(mImsSmsDispatcher).sendSms(eq(mTracker));
        } else if (SmsConstants.FORMAT_3GPP2.equals(smsFormat)) {
            verify(mCdmaSmsDispatcher).sendSms(eq(mTracker));
        } else {
            verify(mGsmSmsDispatcher).sendSms(eq(mTracker));
        }
        assertNull(holder.getConnection());
        assertFalse(holder.isDomainSelectionRequested());
        assertEquals(0, holder.getPendingRequests().size());
    }

    private void sendRetrySmsWhenImsAlreadyUsed(int phoneType, String smsFormat) throws Exception {
        setUpDomainSelectionConnection();
        setUpSmsDispatchers();
        when(mPhone.getPhoneType()).thenReturn(phoneType);
        when(mImsSmsDispatcher.getFormat()).thenReturn(SmsConstants.FORMAT_3GPP);
        when(mCdmaSmsDispatcher.getFormat()).thenReturn(SmsConstants.FORMAT_3GPP2);
        when(mGsmSmsDispatcher.getFormat()).thenReturn(SmsConstants.FORMAT_3GPP);
        replaceInstance(SMSDispatcher.SmsTracker.class, "mFormat", mTracker, smsFormat);
        mTracker.mUsesImsServiceForIms = true;

        mSmsDispatchersController.sendRetrySms(mTracker);
        processAllMessages();

        verify(mSmsDsc, never()).requestDomainSelection(any(), any());

        if (SmsConstants.FORMAT_3GPP2.equals(smsFormat)) {
            verify(mCdmaSmsDispatcher).sendSms(eq(mTracker));
        } else {
            verify(mGsmSmsDispatcher).sendSms(eq(mTracker));
        }
    }

    private static <T> ArrayList<T> asArrayList(T object) {
        ArrayList<T> list = new ArrayList<>();
        list.add(object);
        return list;
    }

    private PendingRequest createPendingRequest() {
        return new PendingRequest(
                SmsDispatchersController.PendingRequest.TYPE_TEXT, null, "test-app",
                mCallingUserId, "1111", "2222", asArrayList(mSentIntent), asArrayList(null),
                false, null, 0, asArrayList("text"), null,
                false, 0, false, 10, 100L, false, false,
                Process.INVALID_UID);
    }
}
