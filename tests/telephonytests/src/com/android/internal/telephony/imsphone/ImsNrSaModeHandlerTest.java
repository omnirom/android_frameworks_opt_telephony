/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.telephony.imsphone;

import static android.telephony.CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA;
import static android.telephony.CarrierConfigManager.Ims.KEY_NR_SA_DISABLE_POLICY_INT;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_NONE;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_WFC_ESTABLISHED;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY;
import static android.telephony.ims.feature.MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_NONE;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.telephony.CarrierConfigManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArraySet;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public final class ImsNrSaModeHandlerTest extends TelephonyTest{
    @Captor ArgumentCaptor<CarrierConfigManager.CarrierConfigChangeListener>
            mCarrierConfigChangeListenerCaptor;
    @Captor ArgumentCaptor<Handler> mPreciseCallStateHandlerCaptor;

    private ImsNrSaModeHandler mTestImsNrSaModeHandler;
    private CarrierConfigManager.CarrierConfigChangeListener mCarrierConfigChangeListener;
    private Handler mPreciseCallStateHandler;

    private Phone mDefaultPhone;

    @Mock private ImsPhoneCall mForegroundCall;
    @Mock private ImsPhoneCall mBackgroundCall;
    private Call.State mActiveState = ImsPhoneCall.State.ACTIVE;
    private Call.State mIdleState = ImsPhoneCall.State.IDLE;

    private int mAnyInt = 0;
    private final Set<String> mFeatureTags = new ArraySet<String>();

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);

        mTestImsNrSaModeHandler = new ImsNrSaModeHandler(mImsPhone, mTestableLooper.getLooper());

        verify(mCarrierConfigManager).registerCarrierConfigChangeListener(
                any(), mCarrierConfigChangeListenerCaptor.capture());

        mCarrierConfigChangeListener = mCarrierConfigChangeListenerCaptor.getValue();

        doReturn(mAnyInt).when(mImsPhone).getSubId();
        doReturn(mContextFixture.getCarrierConfigBundle()).when(mCarrierConfigManager)
                .getConfigForSubId(anyInt(), any());

        mDefaultPhone = new GsmCdmaPhone(
                mContext, mSimulatedCommands, mNotifier, true, 0,
                PhoneConstants.PHONE_TYPE_GSM, mTelephonyComponentFactory,
                (c, p) -> mImsManager, mFeatureFlags);

        doReturn(mDefaultPhone).when(mImsPhone).getDefaultPhone();

        doReturn(mForegroundCall).when(mImsPhone).getForegroundCall();
        doReturn(mBackgroundCall).when(mImsPhone).getBackgroundCall();

        doReturn(mActiveState).when(mForegroundCall).getState();
        doReturn(mActiveState).when(mBackgroundCall).getState();
    }

    @After
    public void tearDown() throws Exception {
        mTestImsNrSaModeHandler = null;
        super.tearDown();
    }

    @Test
    public void testTearDown() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_WFC_ESTABLISHED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);

        verify(mImsPhone).registerForPreciseCallStateChanged(
                mPreciseCallStateHandlerCaptor.capture(), anyInt(), any());
        mPreciseCallStateHandler = mPreciseCallStateHandlerCaptor.getValue();

        mSimulatedCommands.setN1ModeEnabled(false, null);
        mTestImsNrSaModeHandler.setNrSaDisabledForWfc(true);

        mTestImsNrSaModeHandler.tearDown();

        verify(mCarrierConfigManager).unregisterCarrierConfigChangeListener(any());
        verify(mImsPhone).unregisterForPreciseCallStateChanged(mPreciseCallStateHandler);
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testOnImsRegisteredWithNrSaCapabilityAndVoiceCapabilityAndSaDisablePolicyNone() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_NONE);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);
        mTestImsNrSaModeHandler.updateImsCapability(CAPABILITY_TYPE_VOICE);
        mTestImsNrSaModeHandler.setWifiRegStatus(false);

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN, mFeatureTags);

        assertFalse(mTestImsNrSaModeHandler.isWifiRegistered());
    }

    @Test
    public void testOnImsRegisteredWithSaDisablePolicyWfcEstablished() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_WFC_ESTABLISHED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);
        mTestImsNrSaModeHandler.updateImsCapability(CAPABILITY_TYPE_VOICE);

        verify(mImsPhone).registerForPreciseCallStateChanged(any(), anyInt(), any());

        mSimulatedCommands.setN1ModeEnabled(false, null);
        mTestImsNrSaModeHandler.setNrSaDisabledForWfc(true);
        mTestImsNrSaModeHandler.setWifiRegStatus(true);
        mTestImsNrSaModeHandler.setImsCallStatus(true);

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_NONE, mFeatureTags);

        assertTrue(mSimulatedCommands.isN1ModeEnabled());
        assertFalse(mTestImsNrSaModeHandler.isWifiRegistered());

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN, mFeatureTags);

        assertFalse(mSimulatedCommands.isN1ModeEnabled());
        assertTrue(mTestImsNrSaModeHandler.isWifiRegistered());
    }

    @Test
    public void testOnImsRegisteredWithSaDisablePolicyWfcEstablishedWithVonrDisabled() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT,
                NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);
        mTestImsNrSaModeHandler.updateImsCapability(CAPABILITY_TYPE_VOICE);

        verify(mImsPhone).registerForPreciseCallStateChanged(any(), anyInt(), any());

        mSimulatedCommands.setN1ModeEnabled(true, null);
        mTestImsNrSaModeHandler.setWifiRegStatus(false);
        mTestImsNrSaModeHandler.setImsCallStatus(true);
        mSimulatedCommands.setVonrEnabled(true);

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN, mFeatureTags);
        processAllMessages();

        assertTrue(mSimulatedCommands.isN1ModeEnabled());
        assertTrue(mTestImsNrSaModeHandler.isWifiRegistered());

        mSimulatedCommands.setN1ModeEnabled(true, null);
        mTestImsNrSaModeHandler.setWifiRegStatus(false);
        mTestImsNrSaModeHandler.setImsCallStatus(true);
        mSimulatedCommands.setVonrEnabled(false);

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN, mFeatureTags);
        processAllMessages();

        assertFalse(mSimulatedCommands.isN1ModeEnabled());
        assertTrue(mTestImsNrSaModeHandler.isWifiRegistered());

        mSimulatedCommands.setN1ModeEnabled(true, null);
        mTestImsNrSaModeHandler.setWifiRegStatus(false);
        mTestImsNrSaModeHandler.setImsCallStatus(true);
        mSimulatedCommands.setVonrEnabled(true);

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_NONE, mFeatureTags);
        processAllMessages();

        assertTrue(mSimulatedCommands.isN1ModeEnabled());
        assertFalse(mTestImsNrSaModeHandler.isWifiRegistered());
    }

    @Test
    public void testOnImsRegisteredWithSaDisablePolicyVowifiRegistered() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);
        mTestImsNrSaModeHandler.updateImsCapability(CAPABILITY_TYPE_VOICE);

        verify(mImsPhone).unregisterForPreciseCallStateChanged(mTestImsNrSaModeHandler);

        mSimulatedCommands.setN1ModeEnabled(true, null);
        mTestImsNrSaModeHandler.setWifiRegStatus(false);

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN, mFeatureTags);

        assertFalse(mSimulatedCommands.isN1ModeEnabled());
        assertTrue(mTestImsNrSaModeHandler.isWifiRegistered());

        mSimulatedCommands.setN1ModeEnabled(false, null);
        mTestImsNrSaModeHandler.setWifiRegStatus(true);

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_NONE, mFeatureTags);

        assertTrue(mSimulatedCommands.isN1ModeEnabled());
        assertFalse(mTestImsNrSaModeHandler.isWifiRegistered());
    }

    @Test
    public void testOnImsRegisteredWithoutNrSaCapabilityWithSaDisablePolicyVowifiRegistered() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);
        mTestImsNrSaModeHandler.updateImsCapability(CAPABILITY_TYPE_VOICE);

        verify(mImsPhone, times(0)).unregisterForPreciseCallStateChanged(mTestImsNrSaModeHandler);

        mSimulatedCommands.setN1ModeEnabled(true, null);
        mTestImsNrSaModeHandler.setWifiRegStatus(false);

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN, mFeatureTags);

        assertTrue(mSimulatedCommands.isN1ModeEnabled());
        assertFalse(mTestImsNrSaModeHandler.isWifiRegistered());
    }

    @Test
    public void testOnImsRegisteredWithoutVoiceCapabilityWithSaDisablePolicyVowifiRegistered() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);

        verify(mImsPhone).unregisterForPreciseCallStateChanged(mTestImsNrSaModeHandler);

        mSimulatedCommands.setN1ModeEnabled(true, null);
        mTestImsNrSaModeHandler.setWifiRegStatus(false);

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN, mFeatureTags);

        assertTrue(mSimulatedCommands.isN1ModeEnabled());
        assertTrue(mTestImsNrSaModeHandler.isWifiRegistered());
    }

    @Test
    public void testOnImsUnregisteredWithoutNrSaCapability() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);

        mTestImsNrSaModeHandler.setWifiRegStatus(true);

        mTestImsNrSaModeHandler.onImsUnregistered(REGISTRATION_TECH_IWLAN);

        assertTrue(mTestImsNrSaModeHandler.isWifiRegistered());
    }


    @Test
    public void testOnImsUnregisteredWithSaDisablePolicyNone() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_NONE);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);

        mTestImsNrSaModeHandler.setWifiRegStatus(true);

        mTestImsNrSaModeHandler.onImsUnregistered(REGISTRATION_TECH_IWLAN);

        assertTrue(mTestImsNrSaModeHandler.isWifiRegistered());
    }

    @Test
    public void testOnImsUnregisteredWithoutRadioTechIwlan() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);

        mTestImsNrSaModeHandler.setWifiRegStatus(true);

        mTestImsNrSaModeHandler.onImsUnregistered(NR_SA_DISABLE_POLICY_NONE);

        assertTrue(mTestImsNrSaModeHandler.isWifiRegistered());
    }

    @Test
    public void testOnImsUnregisteredWithoutWifiResistring() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);

        mTestImsNrSaModeHandler.setWifiRegStatus(false);

        mTestImsNrSaModeHandler.onImsUnregistered(REGISTRATION_TECH_IWLAN);

        assertFalse(mTestImsNrSaModeHandler.isWifiRegistered());
    }

    @Test
    public void testOnImsUnregisteredWithNrSaCapabilityAndVoiceCapabilityAndRadioTechIwlan() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);
        mTestImsNrSaModeHandler.updateImsCapability(CAPABILITY_TYPE_VOICE);

        mSimulatedCommands.setN1ModeEnabled(false, null);
        mTestImsNrSaModeHandler.setNrSaDisabledForWfc(true);
        mTestImsNrSaModeHandler.setWifiRegStatus(true);

        mTestImsNrSaModeHandler.onImsUnregistered(REGISTRATION_TECH_IWLAN);

        assertTrue(mSimulatedCommands.isN1ModeEnabled());
        assertFalse(mTestImsNrSaModeHandler.isWifiRegistered());
    }

    @Test
    public void testOnPreciseCallStateChangedWithSaDisablePolicyWfcEstablished() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_WFC_ESTABLISHED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);
        mTestImsNrSaModeHandler.updateImsCapability(CAPABILITY_TYPE_VOICE);

        verify(mImsPhone).registerForPreciseCallStateChanged(
                mPreciseCallStateHandlerCaptor.capture(), anyInt(), any());
        mPreciseCallStateHandler = mPreciseCallStateHandlerCaptor.getValue();

        mTestImsNrSaModeHandler.setWifiRegStatus(true);
        mSimulatedCommands.setN1ModeEnabled(true, null);

        mPreciseCallStateHandler.handleMessage(mPreciseCallStateHandler.obtainMessage(101));

        assertTrue(mTestImsNrSaModeHandler.isImsCallOngoing());
        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        doReturn(mIdleState).when(mForegroundCall).getState();
        doReturn(mIdleState).when(mBackgroundCall).getState();
        mPreciseCallStateHandler.handleMessage(mPreciseCallStateHandler.obtainMessage(101));

        assertFalse(mTestImsNrSaModeHandler.isImsCallOngoing());
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testOnPreciseCallStateChangedWithSaDisablePolicyWfcEstablishedWithVonrDisabled() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT,
                NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);
        mTestImsNrSaModeHandler.updateImsCapability(CAPABILITY_TYPE_VOICE);

        verify(mImsPhone).registerForPreciseCallStateChanged(
                mPreciseCallStateHandlerCaptor.capture(), anyInt(), any());
        mPreciseCallStateHandler = mPreciseCallStateHandlerCaptor.getValue();

        mTestImsNrSaModeHandler.setWifiRegStatus(true);
        mSimulatedCommands.setN1ModeEnabled(true, null);
        mSimulatedCommands.setVonrEnabled(false);

        mPreciseCallStateHandler.handleMessage(mPreciseCallStateHandler.obtainMessage(101));
        processAllMessages();

        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        doReturn(mIdleState).when(mForegroundCall).getState();
        doReturn(mIdleState).when(mBackgroundCall).getState();
        mPreciseCallStateHandler.handleMessage(mPreciseCallStateHandler.obtainMessage(101));

        assertTrue(mSimulatedCommands.isN1ModeEnabled());

        doReturn(mActiveState).when(mForegroundCall).getState();
        doReturn(mActiveState).when(mBackgroundCall).getState();
        mPreciseCallStateHandler.handleMessage(mPreciseCallStateHandler.obtainMessage(101));
        mTestImsNrSaModeHandler.setImsCallStatus(false);
        processAllMessages();

        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testOnCarrierConfigChangedRegisterOrUnregisterListenerForPreciseCallStateChange() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_WFC_ESTABLISHED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);

        verify(mImsPhone).registerForPreciseCallStateChanged(
                mPreciseCallStateHandlerCaptor.capture(), anyInt(), any());
        mPreciseCallStateHandler = mPreciseCallStateHandlerCaptor.getValue();

        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED);

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);

        verify(mImsPhone).unregisterForPreciseCallStateChanged(mPreciseCallStateHandler);
    }

    @Test
    public void testUpdateImsCapabilityWithSaDisablePolicyWfcEstablished() {
        mContextFixture.getCarrierConfigBundle().putInt(
                KEY_NR_SA_DISABLE_POLICY_INT, NR_SA_DISABLE_POLICY_WFC_ESTABLISHED);
        mContextFixture.getCarrierConfigBundle().putIntArray(
                KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{CARRIER_NR_AVAILABILITY_SA});

        mCarrierConfigChangeListener.onCarrierConfigChanged(mAnyInt, mAnyInt, mAnyInt, mAnyInt);

        verify(mImsPhone).registerForPreciseCallStateChanged(
                mPreciseCallStateHandlerCaptor.capture(), anyInt(), any());
        mPreciseCallStateHandler = mPreciseCallStateHandlerCaptor.getValue();

        mTestImsNrSaModeHandler.setWifiRegStatus(true);
        mSimulatedCommands.setN1ModeEnabled(true, null);
        mPreciseCallStateHandler.handleMessage(mPreciseCallStateHandler.obtainMessage(101));

        assertTrue(mTestImsNrSaModeHandler.isImsCallOngoing());
        assertTrue(mSimulatedCommands.isN1ModeEnabled());

        mTestImsNrSaModeHandler.updateImsCapability(CAPABILITY_TYPE_VOICE);
        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        mTestImsNrSaModeHandler.updateImsCapability(0);
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }
}
