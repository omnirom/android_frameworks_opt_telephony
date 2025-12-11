/*
 * Copyright 2017 The Android Open Source Project
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
package com.android.internal.telephony.uicc;

import static com.android.internal.telephony.TelephonyTestUtils.waitForMs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.ISub;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.cat.CatService;
import com.android.internal.telephony.subscription.SubscriptionInfoInternal;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Map;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class UiccProfileTest extends TelephonyTest {
    private UiccProfile mUiccProfile;

    public UiccProfileTest() {
        super();
    }

    private IccIoResult mIccIoResult;
    private PersistableBundle mBundle;
    private CarrierConfigManager.CarrierConfigChangeListener mCarrierConfigChangeListener;

    private static final int UICCPROFILE_CARRIER_PRIVILEGE_LOADED_EVENT = 3;

    // Mocked classes
    private CatService mCAT;
    private IccCardStatus mIccCardStatus;
    private Handler mMockedHandler;
    private UiccCard mUiccCard;
    private SubscriptionInfo mSubscriptionInfo;
    private ISub mMockedIsub;
    private static final int TEST_CARRIER_ID = 1;

    private IccCardApplicationStatus composeUiccApplicationStatus(
            IccCardApplicationStatus.AppType appType,
            IccCardApplicationStatus.AppState appState, String aid) {
        IccCardApplicationStatus mIccCardAppStatus = new IccCardApplicationStatus();
        mIccCardAppStatus.aid = aid;
        mIccCardAppStatus.app_type = appType;
        mIccCardAppStatus.app_state = appState;
        mIccCardAppStatus.pin1 = mIccCardAppStatus.pin2 =
                IccCardStatus.PinState.PINSTATE_ENABLED_VERIFIED;
        return mIccCardAppStatus;
    }

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        mCAT = mock(CatService.class);
        mIccCardStatus = mock(IccCardStatus.class);
        mMockedHandler = mock(Handler.class);
        mUiccCard = mock(UiccCard.class);
        mSubscriptionInfo = mock(SubscriptionInfo.class);
        mMockedIsub = mock(ISub.class);

        doReturn(mMockedIsub).when(mIBinder).queryLocalInterface(anyString());
        mServiceManagerMockedServices.put("isub", mIBinder);

        doReturn(1).when(mMockedIsub).getSubId(0);
        /* initially there are no application available, but the array should not be empty. */
        IccCardApplicationStatus umtsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_USIM,
                IccCardApplicationStatus.AppState.APPSTATE_UNKNOWN, "0xA2");
        mIccCardStatus.mApplications = new IccCardApplicationStatus[]{umtsApp};
        mIccCardStatus.mImsSubscriptionAppIndex =
                mIccCardStatus.mGsmUmtsSubscriptionAppIndex = -1;
        mIccIoResult = new IccIoResult(0x90, 0x00, IccUtils.hexStringToBytes("FF40"));
        mSimulatedCommands.setIccIoResultForApduLogicalChannel(mIccIoResult);
        mBundle = mContextFixture.getCarrierConfigBundle();
        when(mCarrierConfigManager.getConfigForSubId(anyInt(), any())).thenReturn(mBundle);

        // Capture CarrierConfigChangeListener to emulate the carrier config change notification
        ArgumentCaptor<CarrierConfigManager.CarrierConfigChangeListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(CarrierConfigManager.CarrierConfigChangeListener.class);
        mUiccProfile = new UiccProfile(mContext, mSimulatedCommands, mIccCardStatus,
                0 /* phoneId */, mUiccCard, new Object(), mFeatureFlags);
        verify(mCarrierConfigManager).registerCarrierConfigChangeListener(any(),
                listenerArgumentCaptor.capture());
        mCarrierConfigChangeListener = listenerArgumentCaptor.getAllValues().get(0);

        processAllMessages();
        logd("Create UiccProfile");

        replaceInstance(UiccProfile.class, "mCatService", mUiccProfile, mCAT);
    }

    @After
    public void tearDown() throws Exception {
        mUiccProfile = null;
        mIccIoResult = null;
        mBundle = null;
        super.tearDown();
    }

    @Test
    public void tesUiccProfileInfoSanity() {
        assertEquals(1, mUiccProfile.getNumApplications());
        assertNull(mUiccProfile.getUniversalPinState());
        assertNull(mUiccProfile.getOperatorBrandOverride());
        for (IccCardApplicationStatus.AppType mAppType :
                IccCardApplicationStatus.AppType.values()) {
            if (mAppType == IccCardApplicationStatus.AppType.APPTYPE_USIM) {
                assertTrue(mUiccProfile.isApplicationOnIcc(mAppType));
            } else {
                assertFalse(mUiccProfile.isApplicationOnIcc(mAppType));
            }
        }
    }

    @Test
    public void testParseAllowListMapFromString() {
        String allowList = "";
        Map<String, String> parsedMap = UiccProfile.parseToCertificateToPackageMap(allowList);
        assertTrue(parsedMap.isEmpty());

        allowList = "nokey;value;separation";
        parsedMap = UiccProfile.parseToCertificateToPackageMap(allowList);
        assertTrue(parsedMap.isEmpty());

        allowList = "KEY1:value1";
        parsedMap = UiccProfile.parseToCertificateToPackageMap(allowList);
        assertEquals("value1", parsedMap.get("KEY1"));

        allowList = "KEY1:value1;   KEY2:value2  ;KEY3:value3";
        parsedMap = UiccProfile.parseToCertificateToPackageMap(allowList);
        assertEquals("value1", parsedMap.get("KEY1"));
        assertEquals("value2", parsedMap.get("KEY2"));
        assertEquals("value3", parsedMap.get("KEY3"));
    }

    @Test
    public void testUpdateUiccProfileApplication() {
        /* update app status and index */
        IccCardApplicationStatus imsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_ISIM,
                IccCardApplicationStatus.AppState.APPSTATE_UNKNOWN, "0xA1");
        IccCardApplicationStatus umtsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_USIM,
                IccCardApplicationStatus.AppState.APPSTATE_UNKNOWN, "0xA2");
        mIccCardStatus.mApplications = new IccCardApplicationStatus[]{imsApp, umtsApp};
        mIccCardStatus.mImsSubscriptionAppIndex = 0;
        mIccCardStatus.mGsmUmtsSubscriptionAppIndex = 1;
        logd("Update UICC Profile Applications");
        mUiccProfile.update(mContext, mSimulatedCommands, mIccCardStatus);
        processAllMessages();
        assertEquals(2, mUiccProfile.getNumApplications());
        assertTrue(mUiccProfile.isApplicationOnIcc(IccCardApplicationStatus.AppType.APPTYPE_ISIM));
        assertTrue(mUiccProfile.isApplicationOnIcc(IccCardApplicationStatus.AppType.APPTYPE_USIM));
    }

    @Test
    public void testUpdateUiccProfile() {
        int mChannelId = 1;
        mIccCardStatus.mCardState = IccCardStatus.CardState.CARDSTATE_PRESENT;
        mSimulatedCommands.setOpenChannelId(mChannelId);
        mUiccProfile.update(mContext, mSimulatedCommands, mIccCardStatus);
        processAllMessages();

        assertTrue(mUiccProfile.areCarrierPrivilegeRulesLoaded());
        verify(mSimulatedCommandsVerifier, times(2)).iccOpenLogicalChannel(isA(String.class),
                anyInt(), isA(Message.class));
        verify(mSimulatedCommandsVerifier, times(2)).iccTransmitApduLogicalChannel(
                anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(),
                anyBoolean(), isA(Message.class)
        );
    }

    @Test
    public void testUpdateUiccProfilePinState() {
        mIccCardStatus.mUniversalPinState = IccCardStatus.PinState.PINSTATE_ENABLED_VERIFIED;
        mUiccProfile.update(mContext, mSimulatedCommands, mIccCardStatus);
        assertEquals(IccCardStatus.PinState.PINSTATE_ENABLED_VERIFIED,
                mUiccProfile.getUniversalPinState());
    }

    @Test
    public void testInitialCardState() {
        // after updateExternalState() is called, the state will not be UNKNOWN
        assertEquals(mUiccProfile.getState(), State.NOT_READY);
    }

    @Test
    public void testUpdateUiccProfileApplicationNotReady() {
        /* update app status and index */
        IccCardApplicationStatus imsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_ISIM,
                IccCardApplicationStatus.AppState.APPSTATE_READY, "0xA1");
        IccCardApplicationStatus umtsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_USIM,
                IccCardApplicationStatus.AppState.APPSTATE_UNKNOWN, "0xA2");
        mIccCardStatus.mApplications = new IccCardApplicationStatus[]{imsApp, umtsApp};
        mIccCardStatus.mImsSubscriptionAppIndex = 0;
        mIccCardStatus.mGsmUmtsSubscriptionAppIndex = 1;
        logd("Update UICC Profile Applications");
        mUiccProfile.update(mContext, mSimulatedCommands, mIccCardStatus);
        processAllMessages();
        assertEquals(2, mUiccProfile.getNumApplications());
        mUiccProfile.mHandler.sendMessage(
                mUiccProfile.mHandler.obtainMessage(UiccProfile.EVENT_APP_READY));
        waitForMs(100);
        processAllMessages();
        assertEquals(mUiccProfile.getState(), State.NOT_READY);
    }

    @Test
    public void testUpdateUiccProfileApplicationAllReady() {
        /* update app status and index */
        IccCardApplicationStatus imsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_ISIM,
                IccCardApplicationStatus.AppState.APPSTATE_READY, "0xA1");
        IccCardApplicationStatus umtsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_USIM,
                IccCardApplicationStatus.AppState.APPSTATE_READY, "0xA2");
        mIccCardStatus.mApplications = new IccCardApplicationStatus[]{imsApp, umtsApp};
        mIccCardStatus.mImsSubscriptionAppIndex = 0;
        mIccCardStatus.mGsmUmtsSubscriptionAppIndex = 1;
        logd("Update UICC Profile Applications");
        mUiccProfile.update(mContext, mSimulatedCommands, mIccCardStatus);
        processAllMessages();
        assertEquals(2, mUiccProfile.getNumApplications());
        mUiccProfile.mHandler.sendMessage(
                mUiccProfile.mHandler.obtainMessage(UiccProfile.EVENT_APP_READY));
        waitForMs(100);
        processAllMessages();
        // state is loaded as all records are loaded right away as SimulatedCommands returns
        // response for them right away. Ideally applications and records should be mocked.
        assertEquals(State.LOADED, mUiccProfile.getState());
    }

    @Test
    public void testUpdateUiccProfileApplicationAllSupportedAppsReady() {
        /* update app status and index */
        IccCardApplicationStatus umtsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_USIM,
                IccCardApplicationStatus.AppState.APPSTATE_READY, "0xA2");
        IccCardApplicationStatus imsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_ISIM,
                IccCardApplicationStatus.AppState.APPSTATE_READY, "0xA1");
        IccCardApplicationStatus unknownApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_UNKNOWN,
                IccCardApplicationStatus.AppState.APPSTATE_UNKNOWN, "0xA2");
        mIccCardStatus.mApplications = new IccCardApplicationStatus[]{imsApp, umtsApp, unknownApp};
        mIccCardStatus.mImsSubscriptionAppIndex = 0;
        mIccCardStatus.mGsmUmtsSubscriptionAppIndex = 1;
        logd("Update UICC Profile Applications");
        mUiccProfile.update(mContext, mSimulatedCommands, mIccCardStatus);
        processAllMessages();
        assertEquals(3, mUiccProfile.getNumApplications());
        mUiccProfile.mHandler.sendMessage(
                mUiccProfile.mHandler.obtainMessage(UiccProfile.EVENT_APP_READY));
        waitForMs(100);
        processAllMessages();
        // state is loaded as all records are loaded right away as SimulatedCommands returns
        // response for them right away. Ideally applications and records should be mocked.
        assertEquals(State.LOADED, mUiccProfile.getState());
    }

    @Test
    public void testUpdateUiccProfileApplicationWithDuplicateApps() {
        /* update app status and index */
        IccCardApplicationStatus umtsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_USIM,
                IccCardApplicationStatus.AppState.APPSTATE_READY, "0xA2");
        IccCardApplicationStatus imsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_ISIM,
                IccCardApplicationStatus.AppState.APPSTATE_READY, "0xA1");
        IccCardApplicationStatus unknownApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_UNKNOWN,
                IccCardApplicationStatus.AppState.APPSTATE_UNKNOWN, "0xA2");
        IccCardApplicationStatus umtsAppDup = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_USIM,
                AppState.APPSTATE_DETECTED, "0xA2");
        mIccCardStatus.mApplications = new IccCardApplicationStatus[]{imsApp, umtsApp, unknownApp,
                umtsAppDup};
        mIccCardStatus.mImsSubscriptionAppIndex = 0;
        mIccCardStatus.mGsmUmtsSubscriptionAppIndex = 1;
        logd("Update UICC Profile Applications");
        mUiccProfile.update(mContext, mSimulatedCommands, mIccCardStatus);
        processAllMessages();

        assertEquals(4, mUiccProfile.getNumApplications());

        mUiccProfile.mHandler.sendMessage(
                mUiccProfile.mHandler.obtainMessage(UiccProfile.EVENT_APP_READY));
        waitForMs(100);
        processAllMessages();
        // state is loaded as all records are loaded right away as SimulatedCommands returns
        // response for them right away. Ideally applications and records should be mocked.
        assertEquals(State.LOADED, mUiccProfile.getState());
    }

    @Test
    public void testUpdateUiccProfileApplicationWithDuplicateAppsInDifferentOrder() {
        /* update app status and index */
        IccCardApplicationStatus umtsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_USIM,
                IccCardApplicationStatus.AppState.APPSTATE_READY, "0xA2");
        IccCardApplicationStatus imsApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_ISIM,
                IccCardApplicationStatus.AppState.APPSTATE_READY, "0xA1");
        IccCardApplicationStatus unknownApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_UNKNOWN,
                IccCardApplicationStatus.AppState.APPSTATE_UNKNOWN, "0xA2");
        IccCardApplicationStatus umtsAppDup = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_USIM,
                AppState.APPSTATE_DETECTED, "0xA2");
        mIccCardStatus.mApplications = new IccCardApplicationStatus[]{umtsAppDup, imsApp, umtsApp,
                unknownApp};
        mIccCardStatus.mImsSubscriptionAppIndex = 0;
        mIccCardStatus.mGsmUmtsSubscriptionAppIndex = 2;
        logd("Update UICC Profile Applications");
        mUiccProfile.update(mContext, mSimulatedCommands, mIccCardStatus);
        processAllMessages();

        assertEquals(4, mUiccProfile.getNumApplications());

        mUiccProfile.mHandler.sendMessage(
                mUiccProfile.mHandler.obtainMessage(UiccProfile.EVENT_APP_READY));
        waitForMs(100);
        processAllMessages();
        // state is loaded as all records are loaded right away as SimulatedCommands returns
        // response for them right away. Ideally applications and records should be mocked.
        assertEquals(State.LOADED, mUiccProfile.getState());
    }

    @Test
    public void testUpdateUiccProfileApplicationNoApplication() {
        mIccCardStatus.mApplications = new IccCardApplicationStatus[]{};
        mIccCardStatus.mImsSubscriptionAppIndex = -1;
        mIccCardStatus.mGsmUmtsSubscriptionAppIndex = -1;
        logd("Update UICC Profile Applications");
        mUiccProfile.update(mContext, mSimulatedCommands, mIccCardStatus);
        processAllMessages();

        assertEquals(0, mUiccProfile.getNumApplications());

        mUiccProfile.mHandler.sendMessage(
                mUiccProfile.mHandler.obtainMessage(UiccProfile.EVENT_APP_READY));
        waitForMs(100);
        processAllMessages();
        // state is loaded since there is no applications.
        assertEquals(State.NOT_READY, mUiccProfile.getState());
    }

    @Test
    public void testUpdateUiccProfileApplicationNoSupportApplication() {
        IccCardApplicationStatus unknownApp = composeUiccApplicationStatus(
                IccCardApplicationStatus.AppType.APPTYPE_UNKNOWN,
                IccCardApplicationStatus.AppState.APPSTATE_UNKNOWN, "");
        mIccCardStatus.mApplications = new IccCardApplicationStatus[]{unknownApp};
        mIccCardStatus.mImsSubscriptionAppIndex = -1;
        mIccCardStatus.mGsmUmtsSubscriptionAppIndex = -1;
        logd("Update UICC Profile Applications");
        mUiccProfile.update(mContext, mSimulatedCommands, mIccCardStatus);
        processAllMessages();

        assertEquals(1, mUiccProfile.getNumApplications());

        mUiccProfile.mHandler.sendMessage(
                mUiccProfile.mHandler.obtainMessage(UiccProfile.EVENT_APP_READY));
        waitForMs(100);
        processAllMessages();
        // state is loaded since there is no applications.
        assertEquals(State.NOT_READY, mUiccProfile.getState());
    }

    @Test
    public void testUpdateExternalState() {
        // IO_ERROR
        doReturn(IccCardStatus.CardState.CARDSTATE_ERROR).when(mUiccCard).getCardState();
        mUiccProfile.updateExternalState();
        assertEquals(State.CARD_IO_ERROR, mUiccProfile.getState());

        // RESTRICTED
        doReturn(IccCardStatus.CardState.CARDSTATE_RESTRICTED).when(mUiccCard).getCardState();
        mUiccProfile.updateExternalState();
        assertEquals(State.CARD_RESTRICTED, mUiccProfile.getState());

        // CARD PRESENT; no mUiccApplication - state should be NOT_READY
        doReturn(IccCardStatus.CardState.CARDSTATE_PRESENT).when(mUiccCard).getCardState();
        mUiccProfile.updateExternalState();
        assertEquals(State.NOT_READY, mUiccProfile.getState());

        // set mUiccApplication
        testUpdateUiccProfileApplicationAllReady();
        mUiccProfile.updateExternalState();
        assertEquals(State.LOADED, mUiccProfile.getState());
    }

    @Test
    public void testCarrierConfigHandling() {
        testUpdateUiccProfileApplication();

        // Fake carrier name
        String fakeCarrierName = "fakeCarrierName";
        PersistableBundle carrierConfigBundle = mContextFixture.getCarrierConfigBundle();
        carrierConfigBundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true);
        carrierConfigBundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING,
                fakeCarrierName);

        // send carrier config change
        mCarrierConfigChangeListener.onCarrierConfigChanged(mPhone.getPhoneId(), mPhone.getSubId(),
                TEST_CARRIER_ID, TEST_CARRIER_ID);
        processAllMessages();

        // verify that setSimOperatorNameForPhone() is called with fakeCarrierName
        ArgumentCaptor<String> stringArgumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(mTelephonyManager, atLeast(1)).setSimOperatorNameForPhone(anyInt(),
                stringArgumentCaptor.capture());
        boolean carrierFound = false;
        for (String carrierName : stringArgumentCaptor.getAllValues()) {
            if (fakeCarrierName.equals(carrierName)) {
                carrierFound = true;
                break;
            }
        }
        assertTrue(carrierFound);
    }

    @Test
    public void testCarrierConfigHandlingForFailCase() {
        testUpdateUiccProfileApplication();

        // Fake carrier name
        String fakeCarrierName = "fakeCarrierName";
        PersistableBundle carrierConfigBundle = mContextFixture.getCarrierConfigBundle();
        carrierConfigBundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true);
        carrierConfigBundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING,
                fakeCarrierName);

        // send carrier config change
        mCarrierConfigChangeListener.onCarrierConfigChanged(mPhone.getPhoneId(), mPhone.getSubId(),
                TelephonyManager.UNKNOWN_CARRIER_ID, TelephonyManager.UNKNOWN_CARRIER_ID);
        processAllMessages();

        // verify that setSimOperatorNameForPhone() is called with fakeCarrierName
        ArgumentCaptor<String> stringArgumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(mTelephonyManager, atLeast(1)).setSimOperatorNameForPhone(anyInt(),
                stringArgumentCaptor.capture());
        boolean carrierFound = false;
        for (String carrierName : stringArgumentCaptor.getAllValues()) {
            if (fakeCarrierName.equals(carrierName)) {
                carrierFound = true;
                break;
            }
        }
        // As we are sending inValid carrierId it fails to set the carrierName
        assertFalse(carrierFound);
    }

    @Test
    public void testSetOperatorBrandOverride() {
        testUpdateUiccProfileApplication();
        String fakeIccId = "1234567";
        String fakeBrand = "operator";

        mUiccProfile.getApplicationIndex(0).getIccRecords().mIccId = fakeIccId;

        doReturn(new SubscriptionInfoInternal.Builder().setSimSlotIndex(0).setId(1)
                .setIccId("98765").build()).when(mSubscriptionManagerService)
                .getSubscriptionInfoInternal(anyInt());
        mUiccProfile.setOperatorBrandOverride(fakeBrand);
        String brandInSharedPreference = mContext.getSharedPreferences("file name", 0)
                .getString("operator_branding_" + fakeIccId, null);
        assertNotEquals(fakeBrand, brandInSharedPreference);

        doReturn(new SubscriptionInfoInternal.Builder().setSimSlotIndex(0).setId(1)
                .setIccId(fakeIccId).build()).when(mSubscriptionManagerService)
                .getSubscriptionInfoInternal(anyInt());
        mUiccProfile.setOperatorBrandOverride(fakeBrand);
        brandInSharedPreference = mContext.getSharedPreferences("file name", 0)
                .getString("operator_branding_" + fakeIccId, null);
        assertEquals(fakeBrand, brandInSharedPreference);
    }

    @Test
    public void testSetOperatorBrandOverrideIccNotMatch() {
        testUpdateUiccProfileApplication();
        String fakeIccId1 = "1234567";
        String fakeIccId2 = "7654321";
        String fakeBrand = "operator";

        mUiccProfile.getApplicationIndex(0).getIccRecords().mIccId = fakeIccId1;
        doReturn(fakeIccId2).when(mSubscriptionInfo).getIccId();

        mUiccProfile.setOperatorBrandOverride(fakeBrand);
        String brandInSharedPreference = mContext.getSharedPreferences("file name", 0)
                .getString("operator_branding_" + fakeIccId1, null);
        assertNull(brandInSharedPreference);
        brandInSharedPreference = mContext.getSharedPreferences("file name", 0)
                .getString("operator_branding_" + fakeIccId2, null);
        assertNull(brandInSharedPreference);
    }

    @Test
    public void testIsEmptyProfile() {
        testUpdateUiccProfileApplication();
        assertFalse(mUiccProfile.isEmptyProfile());

        // Manually resetting app shouldn't indicate we are on empty profile.
        mUiccProfile.resetAppWithAid("", true);
        assertFalse(mUiccProfile.isEmptyProfile());

        // If we update there's no application, then we are on empty profile.
        testUpdateUiccProfileApplicationNoApplication();
        assertTrue(mUiccProfile.isEmptyProfile());
    }
}
