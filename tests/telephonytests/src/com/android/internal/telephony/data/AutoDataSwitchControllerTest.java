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

package com.android.internal.telephony.data;

import static android.telephony.SubscriptionManager.DEFAULT_PHONE_INDEX;

import static com.android.internal.telephony.data.AutoDataSwitchController.EVALUATION_REASON_DATA_SETTINGS_CHANGED;
import static com.android.internal.telephony.data.AutoDataSwitchController.EVALUATION_REASON_REGISTRATION_STATE_CHANGED;
import static com.android.internal.telephony.data.AutoDataSwitchController.EVALUATION_REASON_SIGNAL_STRENGTH_CHANGED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.os.AsyncResult;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.subscription.SubscriptionInfoInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AutoDataSwitchControllerTest extends TelephonyTest {
    private static final int EVENT_SERVICE_STATE_CHANGED = 1;
    private static final int EVENT_DISPLAY_INFO_CHANGED = 2;
    private static final int EVENT_EVALUATE_AUTO_SWITCH = 3;
    private static final int EVENT_SIGNAL_STRENGTH_CHANGED = 4;
    private static final int EVENT_STABILITY_CHECK_PASSED = 5;

    private static final int PHONE_1 = 0;
    private static final int SUB_1 = 1;
    private static final int PHONE_2 = 1;
    private static final int SUB_2 = 2;
    private static final int SUB_3 = 3;
    private static final int PHONE_3 = 2;
    private static final int MAX_RETRY = 5;
    private static final int SCORE_TOLERANCE = 100;
    private static final int GOOD_RAT_SIGNAL_SCORE = 200;
    private static final int BAD_RAT_SIGNAL_SCORE = 50;
    private static final String TEST_UUID_STRING1 = "e9929bd3-c1b5-48bc-a753-ff38108a2231";
    private static final String TEST_UUID_STRING2 = "cb14195d-a3b6-46a1-b98d-6b9740a0bc4f";

    private boolean mIsNonTerrestrialNetwork = false;
    // Mocked
    private AutoDataSwitchController.AutoDataSwitchControllerCallback mMockedPhoneSwitcherCallback;
    private AlarmManager mMockedAlarmManager;
    private CarrierConfigManager.CarrierConfigChangeListener mCarrierConfigChangeListener;

    // Real
    private TelephonyDisplayInfo mGoodTelephonyDisplayInfo;
    private TelephonyDisplayInfo mBadTelephonyDisplayInfo;
    private int mDefaultDataSub;
    private DataEvaluation mDataEvaluation;
    private AutoDataSwitchController mAutoDataSwitchControllerUT;
    private Map<Integer, AlarmManager.OnAlarmListener> mEventsToAlarmListener;
    private Map<Integer, Object> mScheduledEventsToExtras;
    private PersistableBundle mPersistableBundle;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        mGoodTelephonyDisplayInfo = new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_NR,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED, false /*roaming*/,
                false/*isNtn*/, false/*isSatelliteConstrainedDataStatus*/);
        mBadTelephonyDisplayInfo = new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE, false /*roaming*/,
                false/*isNtn*/, false/*isSatelliteConstrainedDataStatus*/);
        mMockedPhoneSwitcherCallback =
                mock(AutoDataSwitchController.AutoDataSwitchControllerCallback.class);
        mMockedAlarmManager = mock(AlarmManager.class);

        doReturn(PHONE_1).when(mPhone).getPhoneId();
        doReturn(SUB_1).when(mPhone).getSubId();

        doReturn(PHONE_2).when(mPhone2).getPhoneId();
        doReturn(SUB_2).when(mPhone2).getSubId();

        doReturn(SUB_1).when(mSubscriptionManagerService).getSubId(PHONE_1);
        doReturn(SUB_2).when(mSubscriptionManagerService).getSubId(PHONE_2);

        mPhones = new Phone[]{mPhone, mPhone2};
        for (Phone phone : mPhones) {
            ServiceState ss = new ServiceState();

            ss.addNetworkRegistrationInfo(new NetworkRegistrationInfo.Builder()
                    .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                    .setRegistrationState(
                            NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING)
                    .setDomain(NetworkRegistrationInfo.DOMAIN_PS)
                    .setIsNonTerrestrialNetwork(mIsNonTerrestrialNetwork)
                    .build());

            doReturn(ss).when(phone).getServiceState();
            doReturn(mSST).when(phone).getServiceStateTracker();
            doReturn(mDisplayInfoController).when(phone).getDisplayInfoController();
            doReturn(mSignalStrengthController).when(phone).getSignalStrengthController();
            doReturn(mSignalStrength).when(phone).getSignalStrength();
            doReturn(mDataNetworkController).when(phone).getDataNetworkController();
            doReturn(mDataConfigManager).when(mDataNetworkController).getDataConfigManager();
            doReturn(mDataSettingsManager).when(phone).getDataSettingsManager();
            doAnswer(invocation -> phone.getSubId() == mDefaultDataSub)
                    .when(phone).isUserDataEnabled();
        }
        mDataEvaluation = new DataEvaluation(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY);
        doReturn(mDataEvaluation).when(mDataNetworkController).evaluateNetworkRequest(
                any(TelephonyNetworkRequest.class),
                eq(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY));
        doReturn(new int[]{SUB_1, SUB_2}).when(mSubscriptionManagerService)
                .getActiveSubIdList(true);
        doAnswer(invocation -> {
            int subId = (int) invocation.getArguments()[0];
            return subId == SUB_1 ? PHONE_1 : PHONE_2;
        }).when(mSubscriptionManagerService).getPhoneId(anyInt());
        doAnswer(invocation -> {
            int subId = (int) invocation.getArguments()[0];

            if (!SubscriptionManager.isUsableSubIdValue(subId)) return null;

            int slotIndex = subId == SUB_1 ? PHONE_1 : PHONE_2;
            return new SubscriptionInfoInternal.Builder()
                    .setSimSlotIndex(slotIndex).setId(subId).build();
        }).when(mSubscriptionManagerService).getSubscriptionInfoInternal(anyInt());
        replaceInstance(PhoneFactory.class, "sPhones", null, mPhones);

        // Change data config
        doReturn(true).when(mDataConfigManager).isPingTestBeforeAutoDataSwitchRequired();
        doReturn(10000L).when(mDataConfigManager)
                .getAutoDataSwitchAvailabilityStabilityTimeThreshold();
        doReturn(120000L).when(mDataConfigManager)
                .getAutoDataSwitchPerformanceStabilityTimeThreshold();
        doReturn(150000L).when(mDataConfigManager)
                .getAutoDataSwitchAvailabilitySwitchbackStabilityTimeThreshold();
        doReturn(MAX_RETRY).when(mDataConfigManager).getAutoDataSwitchValidationMaxRetry();
        doReturn(SCORE_TOLERANCE).when(mDataConfigManager).getAutoDataSwitchScoreTolerance();
        doAnswer(invocation -> {
            TelephonyDisplayInfo displayInfo = (TelephonyDisplayInfo) invocation.getArguments()[0];
            SignalStrength signalStrength = (SignalStrength) invocation.getArguments()[1];
            if (displayInfo == mGoodTelephonyDisplayInfo
                    || signalStrength.getLevel() > SignalStrength.SIGNAL_STRENGTH_MODERATE) {
                return GOOD_RAT_SIGNAL_SCORE;
            }
            return BAD_RAT_SIGNAL_SCORE;
        }).when(mDataConfigManager).getAutoDataSwitchScore(any(TelephonyDisplayInfo.class),
                any(SignalStrength.class));

        setDefaultDataSubId(SUB_1);
        doReturn(PHONE_1).when(mPhoneSwitcher).getPreferredDataPhoneId();

        mAutoDataSwitchControllerUT = new AutoDataSwitchController(mContext, Looper.myLooper(),
                mPhoneSwitcher, mFeatureFlags, mMockedPhoneSwitcherCallback);

        if (mFeatureFlags.monitorCarrierConfigChangeForAutoDataSwitch()) {
            ArgumentCaptor<CarrierConfigManager.CarrierConfigChangeListener> captor =
                    ArgumentCaptor.forClass(CarrierConfigManager.CarrierConfigChangeListener.class);
            verify(mCarrierConfigManager).registerCarrierConfigChangeListener(any(),
                    captor.capture());
            mCarrierConfigChangeListener = captor.getValue();
        }

        replaceInstance(AutoDataSwitchController.class, "mAlarmManager",
                mAutoDataSwitchControllerUT, mMockedAlarmManager);
        mEventsToAlarmListener = getPrivateField(mAutoDataSwitchControllerUT,
                "mEventsToAlarmListener", Map.class);
        mScheduledEventsToExtras = getPrivateField(mAutoDataSwitchControllerUT,
                "mScheduledEventsToExtras", Map.class);

        clearInvocations(mDisplayInfoController, mSignalStrengthController, mSST,
                mCarrierConfigManager);

        // Default setup for opportunistic auto data switch policy.
        // This ensures existing tests behave as if opportunistic switching is disabled by policy,
        // unless overridden by a specific test or setupOpportunisticSwitchMode.
        if (mFeatureFlags.monitorCarrierConfigChangeForAutoDataSwitch()) {
            mPersistableBundle = new PersistableBundle();
            mPersistableBundle.putInt(CarrierConfigManager.KEY_OPP_AUTO_DATA_SWITCH_POLICY_INT,
                    CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_DISABLED);
            doReturn(mPersistableBundle).when(mCarrierConfigManager).getConfig(any());
        } else {
            doReturn(CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_DISABLED)
                    .when(mDataConfigManager).getCarrierOverriddenAutoDataSwitchPolicyForOppt();
        }
    }

    @After
    public void tearDown() throws Exception {
        mAutoDataSwitchControllerUT = null;
        mGoodTelephonyDisplayInfo = null;
        mBadTelephonyDisplayInfo = null;
        super.tearDown();
    }

    @Test
    public void testCarrierConfigChanged_opportunisticPolicyEnabled_triggersSwitch() {
        if (!mFeatureFlags.monitorCarrierConfigChangeForAutoDataSwitch()) {
            return;
        }
        // 1. Initial state: Policy is DISABLED. Primary is OOS, but no switch happens.
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_DISABLED);
        setupStatePrimaryIsOos();

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_REGISTRATION_STATE_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(anyInt(), anyBoolean());
        clearInvocations(mMockedPhoneSwitcherCallback);

        // 2. Simulate carrier config change: Policy becomes FOR_AVAILABILITY
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOR_AVAILABILITY);

        // 3. Trigger the change listener
        mCarrierConfigChangeListener.onCarrierConfigChanged(PHONE_1, SUB_1, 1, 1);
        processAllFutureMessages();

        // 4. Verify a switch is now triggered
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(PHONE_2, true);
    }

    @Test
    public void testCarrierConfigChanged_opportunisticPolicyDisabled_cancelsSwitch() {
        if (!mFeatureFlags.monitorCarrierConfigChangeForAutoDataSwitch()) {
            return;
        }
        // 1. Initial state: Policy is FOR_AVAILABILITY. Primary is OOS, switch is pending.
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOR_AVAILABILITY);
        setupStatePrimaryIsOos();

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_REGISTRATION_STATE_CHANGED);
        // Don't process future messages yet, so the switch is pending stability check.
        assertThat(mScheduledEventsToExtras.containsKey(EVENT_STABILITY_CHECK_PASSED)).isTrue();

        // 2. Simulate carrier config change: Policy becomes DISABLED
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_DISABLED);

        // 3. Trigger the change listener
        mCarrierConfigChangeListener.onCarrierConfigChanged(PHONE_1, SUB_1, 1, 1);
        processAllMessages();
        processAllFutureMessages();

        // 4. Verify the pending switch is cancelled
        verify(mMockedPhoneSwitcherCallback).onRequireCancelAnyPendingAutoSwitchValidation();
        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(anyInt(), anyBoolean());
    }

    @Test
    public void testCancelSwitch_onPrimary() {
        // 0. When all conditions met
        prepareIdealUsesNonDdsCondition();
        processAllFutureMessages();

        // Verify attempting to switch
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(PHONE_2, true/*needValidation*/);

        // 1.1 Service state becomes not ideal - secondary lost its advantage score,
        // but primary is OOS, so continue to switch.
        clearInvocations(mMockedPhoneSwitcherCallback);
        displayInfoChanged(PHONE_2, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_POOR);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback, never())
                .onRequireCancelAnyPendingAutoSwitchValidation();

        // 1.2 Service state becomes not ideal - secondary lost its advantage score,
        // since primary is in service, no need to switch.
        clearInvocations(mMockedPhoneSwitcherCallback);
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_2, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_POOR);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireCancelAnyPendingAutoSwitchValidation();

        // 2.1 User data disabled on primary SIM
        prepareIdealUsesNonDdsCondition();
        processAllFutureMessages();
        clearInvocations(mMockedPhoneSwitcherCallback);
        doReturn(false).when(mPhone).isUserDataEnabled();
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireCancelAnyPendingAutoSwitchValidation();

        // 2.2 Auto switch feature is disabled
        prepareIdealUsesNonDdsCondition();
        processAllFutureMessages();
        clearInvocations(mMockedPhoneSwitcherCallback);
        mDataEvaluation.addDataDisallowedReason(DataEvaluation.DataDisallowedReason
                .NO_SUITABLE_DATA_PROFILE);
        doReturn(mDataEvaluation)
                .when(mDataNetworkController).evaluateNetworkRequest(
                        any(TelephonyNetworkRequest.class),
                        eq(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY));
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireCancelAnyPendingAutoSwitchValidation();

        // 3.1 No default network
        prepareIdealUsesNonDdsCondition();
        processAllFutureMessages();
        clearInvocations(mMockedPhoneSwitcherCallback);
        mAutoDataSwitchControllerUT.updateDefaultNetworkCapabilities(new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI));
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireCancelAnyPendingAutoSwitchValidation();
    }

    @Test
    public void testRoaming_prefer_home_over_roam() {
        // DDS -> nDDS: Prefer Home over Roaming
        prepareIdealUsesNonDdsCondition();
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(PHONE_2, true/*needValidation*/);

        // nDDS -> DDS: Prefer Home over Roaming
        doReturn(PHONE_2).when(mPhoneSwitcher).getPreferredDataPhoneId();
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                true/*needValidation*/);
    }

    @Test
    public void testRoaming_prefer_roam_over_satellite() {
        // DDS -> nDDS: Prefer Roaming over non-terrestrial
        prepareIdealUsesNonDdsCondition();
        mIsNonTerrestrialNetwork = true;
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        mIsNonTerrestrialNetwork = false;
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(PHONE_2, true/*needValidation*/);

        // nDDS -> DDS: Prefer Roaming over non-terrestrial
        doReturn(PHONE_2).when(mPhoneSwitcher).getPreferredDataPhoneId();
        mIsNonTerrestrialNetwork = false;
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        mIsNonTerrestrialNetwork = true;
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                true/*needValidation*/);
        mIsNonTerrestrialNetwork = false;
    }

    @Test
    public void testRoaming_satellite_bypass_settings() {
        prepareIdealUsesNonDdsCondition();

        doReturn(true).when(mDataConfigManager).isIgnoringDataRoamingSettingForSatellite();
        doReturn(false).when(mPhone).getDataRoamingEnabled();

        mIsNonTerrestrialNetwork = true;
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        mIsNonTerrestrialNetwork = false;
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(PHONE_2, true/*needValidation*/);
    }


    @Test
    public void testRoaming_roaming_but_roam_disabled() {
        // Disable RAT + signalStrength base switching.
        doReturn(-1).when(mDataConfigManager).getAutoDataSwitchScoreTolerance();
        mAutoDataSwitchControllerUT = new AutoDataSwitchController(mContext, Looper.myLooper(),
                mPhoneSwitcher, mFeatureFlags, mMockedPhoneSwitcherCallback);

        // On primary phone
        // 1.1 Both roaming, user allow roaming on both phone, no need to switch.
        prepareIdealUsesNonDdsCondition();
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        processAllFutureMessages();
        clearInvocations(mMockedPhoneSwitcherCallback);

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(anyInt(),
                anyBoolean()/*needValidation*/);

        // 1.2 Both roaming, but roaming is only allowed on the backup phone.
        doReturn(false).when(mPhone).getDataRoamingEnabled();
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(PHONE_2, true/*needValidation*/);

        // On backup phone
        doReturn(PHONE_2).when(mPhoneSwitcher).getPreferredDataPhoneId();
        // 2.1 Both roaming, user allow roaming on both phone, prefer default.
        doReturn(true).when(mPhone).getDataRoamingEnabled();
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                true/*needValidation*/);

        // 2.1 Both roaming, but roaming is only allowed on the default phone.
        doReturn(false).when(mPhone2).getDataRoamingEnabled();
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                false/*needValidation*/);
    }

    @Test
    public void testRoaming_same_roaming_condition_uses_rat_signalStrength() {
        // On primary phone
        // 1. Both roaming, user allow roaming on both phone, do NOT use RAT score to decide switch.
        prepareIdealUsesNonDdsCondition();
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(PHONE_2,
                true/*needValidation*/);

        // On backup phone
        doReturn(PHONE_2).when(mPhoneSwitcher).getPreferredDataPhoneId();
        // 2. Both roaming, do NOT uses RAT score to decide switch, so switch back to primary.
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                true/*needValidation*/);
    }

    @Test
    public void testCancelSwitch_onPrimary_rat_signalStrength() {
        // 4.1.1 Display info and signal strength on secondary phone became bad,
        // but primary is still OOS, so still switch to the secondary.
        prepareIdealUsesNonDdsCondition();
        processAllFutureMessages();
        clearInvocations(mMockedPhoneSwitcherCallback);
        displayInfoChanged(PHONE_2, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_MODERATE);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback, never())
                .onRequireCancelAnyPendingAutoSwitchValidation();

        // 4.1.2 Display info and signal strength on secondary phone became bad,
        // but primary become service, then don't switch.
        logd("4.1.2 Display info and signal strength on secondary became bad, don't switch.");
        prepareIdealUsesNonDdsCondition();
        processAllFutureMessages();
        clearInvocations(mMockedPhoneSwitcherCallback, mMockedAlarmManager);
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_2, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_MODERATE);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback, atLeastOnce())
                .onRequireCancelAnyPendingAutoSwitchValidation();
        verify(mMockedAlarmManager, atLeastOnce()).cancel(mEventsToAlarmListener.get(
                EVENT_STABILITY_CHECK_PASSED));

        // 4.2 Display info on default phone became good just as the secondary
        logd("4.2 Display info on default phone became good just as the secondary.");
        prepareIdealUsesNonDdsCondition();
        processAllFutureMessages();
        clearInvocations(mMockedPhoneSwitcherCallback, mMockedAlarmManager);
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_1, mGoodTelephonyDisplayInfo);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback, atLeastOnce())
                .onRequireCancelAnyPendingAutoSwitchValidation();
        verify(mMockedAlarmManager, atLeastOnce()).cancel(mEventsToAlarmListener.get(
                EVENT_STABILITY_CHECK_PASSED));

        // 4.3 Signal strength on default phone became just as good as the secondary
        logd("4.3 Signal strength on default phone became just as good as the secondary.");
        prepareIdealUsesNonDdsCondition();
        processAllFutureMessages();
        clearInvocations(mMockedPhoneSwitcherCallback, mMockedAlarmManager);
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        signalStrengthChanged(PHONE_1, SignalStrength.SIGNAL_STRENGTH_GREAT);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback, atLeastOnce())
                .onRequireCancelAnyPendingAutoSwitchValidation();
        verify(mMockedAlarmManager, atLeastOnce()).cancel(mEventsToAlarmListener.get(
                EVENT_STABILITY_CHECK_PASSED));
    }

    @Test
    public void testOnNonDdsSwitchBackToPrimary() {
        // Disable Rat/SignalStrength based switch to test primary OOS based switch
        doReturn(-1).when(mDataConfigManager).getAutoDataSwitchScoreTolerance();
        mAutoDataSwitchControllerUT = new AutoDataSwitchController(mContext, Looper.myLooper(),
                mPhoneSwitcher, mFeatureFlags, mMockedPhoneSwitcherCallback);
        doReturn(PHONE_2).when(mPhoneSwitcher).getPreferredDataPhoneId();

        prepareIdealUsesNonDdsCondition();
        // 1.1 service state changes - primary becomes available again, require validation
        serviceStateChanged(PHONE_1,
                NetworkRegistrationInfo.REGISTRATION_STATE_HOME/*need validate*/);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                true/*needValidation*/);

        clearInvocations(mMockedPhoneSwitcherCallback);
        prepareIdealUsesNonDdsCondition();
        // 1.2 service state changes - secondary becomes unavailable, NO need validation
        serviceStateChanged(PHONE_1,
                NetworkRegistrationInfo.REGISTRATION_STATE_HOME/*need validate*/);
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_DENIED/*no need*/);
        processAllFutureMessages();
        // The later validation requirement overrides the previous
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                false/*needValidation*/);

        clearInvocations(mMockedPhoneSwitcherCallback);
        prepareIdealUsesNonDdsCondition();
        // 2.1 User data disabled on primary SIM, no need validation
        doReturn(false).when(mPhone).isUserDataEnabled();
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireImmediatelySwitchToPhone(DEFAULT_PHONE_INDEX,
                EVALUATION_REASON_DATA_SETTINGS_CHANGED);

        clearInvocations(mMockedPhoneSwitcherCallback);
        prepareIdealUsesNonDdsCondition();
        // 2.2 Auto switch feature is disabled, no need validation
        clearInvocations(mCellularNetworkValidator);
        mDataEvaluation.addDataDisallowedReason(DataEvaluation.DataDisallowedReason.DATA_DISABLED);
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireImmediatelySwitchToPhone(DEFAULT_PHONE_INDEX,
                EVALUATION_REASON_DATA_SETTINGS_CHANGED);

        clearInvocations(mMockedPhoneSwitcherCallback);
        prepareIdealUsesNonDdsCondition();
        // 3.1 Default network is active on non-cellular transport
        mAutoDataSwitchControllerUT.updateDefaultNetworkCapabilities(new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI));
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                false/*needValidation*/);

        clearInvocations(mMockedPhoneSwitcherCallback);
        prepareIdealUsesNonDdsCondition();
    }

    @Test
    public void testOnNonDdsSwitchBackToPrimary_rat_signalStrength() {
        prepareIdealUsesNonDdsCondition();
        processAllFutureMessages();
        doReturn(PHONE_2).when(mPhoneSwitcher).getPreferredDataPhoneId();

        // 4.1 Display info and signal strength on secondary phone became bad just as the default
        // Expect switch back since both phone has the same score.
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_2, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_POOR);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                true/*needValidation*/);

        clearInvocations(mMockedPhoneSwitcherCallback);
        prepareIdealUsesNonDdsCondition();
        // 4.2 Display info and signal strength on secondary phone became worse than the default.
        // Expect to switch.
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        signalStrengthChanged(PHONE_1, SignalStrength.SIGNAL_STRENGTH_GREAT);
        displayInfoChanged(PHONE_2, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_POOR);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                true/*needValidation*/);
    }

    @Test
    public void testCancelSwitch_onSecondary() {
        doReturn(PHONE_2).when(mPhoneSwitcher).getPreferredDataPhoneId();
        prepareIdealUsesNonDdsCondition();

        // attempts the switch back due to secondary not usable
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_DENIED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                false/*needValidation*/);

        // cancel the switch back attempt due to secondary back to HOME
        clearInvocations(mMockedPhoneSwitcherCallback);
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireCancelAnyPendingAutoSwitchValidation();
    }

    @Test
    public void testStabilityCheckOverride_basic() {
        // Disable RAT + signalStrength base switching.
        doReturn(-1).when(mDataConfigManager).getAutoDataSwitchScoreTolerance();
        mAutoDataSwitchControllerUT = new AutoDataSwitchController(mContext, Looper.myLooper(),
                mPhoneSwitcher, mFeatureFlags, mMockedPhoneSwitcherCallback);

        // Starting stability check for switching to non-DDS
        prepareIdealUsesNonDdsCondition();
        processAllFutureMessages();

        clearInvocations(mMockedPhoneSwitcherCallback);
        // Switch success, but the previous stability check is still pending
        doReturn(PHONE_2).when(mPhoneSwitcher).getPreferredDataPhoneId();

        // Display info and signal strength on secondary phone became worse than the default.
        // Expect to switch back, and it should override the previous stability check
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        // process all messages include the delayed message
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                true/*needValidation*/);
        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(PHONE_2,
                true/*needValidation*/);
    }

    @Test
    public void testStabilityCheckOverride_uses_rat_signalStrength() {
        // Switching due to availability first.
        prepareIdealUsesNonDdsCondition();

        // Verify stability check pending with short timer.
        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(anyInt(), anyBoolean());

        // Switching due to performance now, should override to use long timer.
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);

        // Verify stability check pending with long timer.
        assertThat(mAutoDataSwitchControllerUT.hasMessages(EVENT_STABILITY_CHECK_PASSED)).isFalse();
        verify(mMockedAlarmManager).setExact(anyInt(), anyLong(), anyString(),
                eq(mEventsToAlarmListener.get(
                        EVENT_STABILITY_CHECK_PASSED)), any());
    }

    @Test
    public void testValidationFailedRetry() {
        prepareIdealUsesNonDdsCondition();

        clearInvocations(mMockedPhoneSwitcherCallback);
        for (int i = 0; i < MAX_RETRY; i++) {
            mAutoDataSwitchControllerUT.evaluateRetryOnValidationFailed();
            processAllFutureMessages();
        }
        verify(mMockedPhoneSwitcherCallback, times(MAX_RETRY))
                .onRequireValidation(PHONE_2, true /*need validation*/);
    }

    @Test
    public void testExemptPingTest() {
        // Change resource overlay
        doReturn(false).when(mDataConfigManager)
                .isPingTestBeforeAutoDataSwitchRequired();
        doReturn(-1 /*Disable signal based switch for easy mock*/).when(mDataConfigManager)
                .getAutoDataSwitchScoreTolerance();
        mAutoDataSwitchControllerUT = new AutoDataSwitchController(mContext, Looper.myLooper(),
                mPhoneSwitcher, mFeatureFlags, mMockedPhoneSwitcherCallback);

        //1. DDS -> nDDS, verify callback doesn't require validation
        prepareIdealUsesNonDdsCondition();
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(PHONE_2, false/*needValidation*/);

        //2. nDDS -> DDS, verify callback doesn't require validation
        doReturn(PHONE_2).when(mPhoneSwitcher).getPreferredDataPhoneId();
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX,
                false/*needValidation*/);
    }

    @Test
    public void testSetNotification() {
        NotificationManager notificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        SubscriptionInfo mockedInfo = mock(SubscriptionInfo.class);
        doReturn(false).when(mockedInfo).isOpportunistic();
        doReturn(mockedInfo).when(mSubscriptionManagerService).getSubscriptionInfo(anyInt());

        // First switch is not due to auto, so no notification.
        mAutoDataSwitchControllerUT.displayAutoDataSwitchNotification(PHONE_2, false);
        verify(mSubscriptionManagerService, never()).getSubscriptionInfo(SUB_2);

        // Switch is due to auto, show notification.
        mAutoDataSwitchControllerUT.displayAutoDataSwitchNotification(PHONE_2, true);
        verify(notificationManager).notify(any(), anyInt(), any());
        verify(mSubscriptionManagerService).getSubscriptionInfo(SUB_2);

        // Switch is due to auto, but already shown notification, hide the notification.
        mAutoDataSwitchControllerUT.displayAutoDataSwitchNotification(PHONE_2, true);
        verify(notificationManager).cancel(any(), anyInt());
    }

    @Test
    public void testMultiSimConfigChanged() {
        // Test Dual -> Single
        mAutoDataSwitchControllerUT.onMultiSimConfigChanged(1);

        verify(mDisplayInfoController).unregisterForTelephonyDisplayInfoChanged(any());
        verify(mSignalStrengthController).unregisterForSignalStrengthChanged(any());
        verify(mSST).unregisterForServiceStateChanged(any());

        clearInvocations(mDisplayInfoController, mSignalStrengthController, mSST);
        // Test Single -> Dual
        mAutoDataSwitchControllerUT.onMultiSimConfigChanged(2);

        verify(mDisplayInfoController).registerForTelephonyDisplayInfoChanged(any(),
                eq(EVENT_DISPLAY_INFO_CHANGED), eq(PHONE_2));
        verify(mSignalStrengthController).registerForSignalStrengthChanged(any(),
                eq(EVENT_SIGNAL_STRENGTH_CHANGED), eq(PHONE_2));
        verify(mSST).registerForServiceStateChanged(any(),
                eq(EVENT_SERVICE_STATE_CHANGED), eq(PHONE_2));
    }

    @Test
    public void testSubscriptionChangedUnregister() {
        // Test single SIM loaded
        int modemCount = 2;
        doReturn(new int[]{SUB_2}).when(mSubscriptionManagerService)
                .getActiveSubIdList(true);
        mAutoDataSwitchControllerUT.notifySubscriptionsMappingChanged();
        processAllMessages();

        // Verify unregister from both slots since only 1 visible SIM is insufficient for switching
        verify(mDisplayInfoController, times(modemCount))
                .unregisterForTelephonyDisplayInfoChanged(any());
        verify(mSignalStrengthController, times(modemCount))
                .unregisterForSignalStrengthChanged(any());
        verify(mSST, times(modemCount)).unregisterForServiceStateChanged(any());

        // Test single -> Duel
        clearInvocations(mDisplayInfoController, mSignalStrengthController, mSST);
        doReturn(new int[]{SUB_1, SUB_2}).when(mSubscriptionManagerService)
                .getActiveSubIdList(true);
        mAutoDataSwitchControllerUT.notifySubscriptionsMappingChanged();
        processAllMessages();

        // Verify register on both slots
        for (int phoneId = 0; phoneId < modemCount; phoneId++) {
            verify(mDisplayInfoController).registerForTelephonyDisplayInfoChanged(any(),
                    eq(EVENT_DISPLAY_INFO_CHANGED), eq(phoneId));
            verify(mSignalStrengthController).registerForSignalStrengthChanged(any(),
                    eq(EVENT_SIGNAL_STRENGTH_CHANGED), eq(phoneId));
            verify(mSST).registerForServiceStateChanged(any(),
                    eq(EVENT_SERVICE_STATE_CHANGED), eq(phoneId));
        }
    }

    @Test
    public void testDataSettingsChangedUpdateListener() {
        setDefaultDataSubId(SUB_1); // Phone 1 is default
        int modemCount = mPhones.length; // Should be 2

        // Pre-condition: Assume listeners are registered initially (cleared invocations in setUp)

        // --- Scenario 1: Disable Default Phone User Data ---
        logd("Scenario 1: Disable Default Phone User Data");
        doReturn(false).when(mPhone).isUserDataEnabled();

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllMessages();

        // Verify unregister calls for *both* phones
        verify(mDisplayInfoController, times(modemCount))
                .unregisterForTelephonyDisplayInfoChanged(any());
        verify(mSignalStrengthController, times(modemCount)).unregisterForSignalStrengthChanged(
                any());
        verify(mSST, times(modemCount)).unregisterForServiceStateChanged(any());
        // Verify register calls were NOT made
        verify(mDisplayInfoController, never()).registerForTelephonyDisplayInfoChanged(
                any(), anyInt(), any());
        verify(mSignalStrengthController, never()).registerForSignalStrengthChanged(
                any(), anyInt(), any());
        verify(mSST, never()).registerForServiceStateChanged(any(), anyInt(), any());
        clearInvocations(mDisplayInfoController, mSignalStrengthController, mSST);

        // --- Scenario 2: Re-enable Default Phone User Data ---
        logd("Scenario 2: Re-enable Default Phone User Data");
        doReturn(true).when(mPhone).isUserDataEnabled();

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllMessages();

        // Verify register calls for *both* phones
        verify(mDisplayInfoController, times(modemCount)).registerForTelephonyDisplayInfoChanged(
                any(), eq(EVENT_DISPLAY_INFO_CHANGED), any());
        verify(mSignalStrengthController, times(modemCount)).registerForSignalStrengthChanged(
                any(), eq(EVENT_SIGNAL_STRENGTH_CHANGED), any());
        verify(mSST, times(modemCount)).registerForServiceStateChanged(
                any(), eq(EVENT_SERVICE_STATE_CHANGED), any());
        // Verify unregister calls were NOT made
        verify(mDisplayInfoController, never()).unregisterForTelephonyDisplayInfoChanged(any());
        verify(mSignalStrengthController, never()).unregisterForSignalStrengthChanged(any());
        verify(mSST, never()).unregisterForServiceStateChanged(any());
        clearInvocations(mDisplayInfoController, mSignalStrengthController, mSST); // Reset

        // --- Scenario 3: Disable *Only* Candidate Phone Data Setting ---
        logd("Scenario 3: Disable *Only* Candidate Phone Data Setting");
        doReturn(true).when(mPhone).isUserDataEnabled(); // Ensure default is enabled
        doReturn(false).when(mDataSettingsManager).isDataEnabled(); // Disable candidate

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllMessages();

        // Verify unregister calls for *both* phones (as no candidates left)
        verify(mDisplayInfoController, times(modemCount))
                .unregisterForTelephonyDisplayInfoChanged(any());
        verify(mSignalStrengthController, times(modemCount))
                .unregisterForSignalStrengthChanged(any());
        verify(mSST, times(modemCount)).unregisterForServiceStateChanged(any());
        // Verify register calls were NOT made
        verify(mDisplayInfoController, never())
                .registerForTelephonyDisplayInfoChanged(any(), anyInt(), any());
        verify(mSignalStrengthController, never())
                .registerForSignalStrengthChanged(any(), anyInt(), any());
        verify(mSST, never()).registerForServiceStateChanged(any(), anyInt(), any());
        clearInvocations(mDisplayInfoController, mSignalStrengthController, mSST); // Reset

        // --- Scenario 4: Re-enable Candidate Phone Data Setting ---
        logd("Scenario 4: Re-enable Candidate Phone Data Setting");
        doReturn(true).when(mDataSettingsManager).isDataEnabled();

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(EVALUATION_REASON_DATA_SETTINGS_CHANGED);
        processAllMessages();

        // Verify register calls for *both* phones
        verify(mDisplayInfoController, times(modemCount)).registerForTelephonyDisplayInfoChanged(
                any(), eq(EVENT_DISPLAY_INFO_CHANGED), any());
        verify(mSignalStrengthController, times(modemCount)).registerForSignalStrengthChanged(
                any(), eq(EVENT_SIGNAL_STRENGTH_CHANGED), any());
        verify(mSST, times(modemCount)).registerForServiceStateChanged(
                any(), eq(EVENT_SERVICE_STATE_CHANGED), any());
        // Verify unregister calls were NOT made
        verify(mDisplayInfoController, never()).unregisterForTelephonyDisplayInfoChanged(any());
        verify(mSignalStrengthController, never()).unregisterForSignalStrengthChanged(any());
        verify(mSST, never()).unregisterForServiceStateChanged(any());
    }

    @Test
    public void testDefaultNetworkChangedUpdateListener() {
        setDefaultDataSubId(SUB_1); // Phone 1 is default
        int modemCount = mPhones.length; // Should be 2

        // Pre-condition: Assume listeners are registered initially (cleared invocations in setUp)

        // --- Scenario 1: Default network becomes non-cellular (WIFI) ---
        logd("Scenario 1: Default network becomes WIFI");
        NetworkCapabilities wifiCapabilities = new NetworkCapabilities();
        wifiCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        mAutoDataSwitchControllerUT.updateDefaultNetworkCapabilities(wifiCapabilities);
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(AutoDataSwitchController
                .EVALUATION_REASON_DEFAULT_NETWORK_CHANGED);
        processAllMessages();

        // Verify unregister calls for *both* phones
        verify(mDisplayInfoController, times(modemCount))
                .unregisterForTelephonyDisplayInfoChanged(any());
        verify(mSignalStrengthController, times(modemCount))
                .unregisterForSignalStrengthChanged(any());
        verify(mSST, times(modemCount)).unregisterForServiceStateChanged(any());
        // Verify register calls were NOT made
        verify(mDisplayInfoController, never()).registerForTelephonyDisplayInfoChanged(
                any(), anyInt(), any());
        verify(mSignalStrengthController, never()).registerForSignalStrengthChanged(
                any(), anyInt(), any());
        verify(mSST, never()).registerForServiceStateChanged(any(), anyInt(), any());
        clearInvocations(mDisplayInfoController, mSignalStrengthController, mSST); // Reset

        // --- Scenario 2: Default network becomes cellular ---
        logd("Scenario 2: Default network becomes CELLULAR");
        NetworkCapabilities cellularCapabilities = new NetworkCapabilities();
        cellularCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        mAutoDataSwitchControllerUT.updateDefaultNetworkCapabilities(cellularCapabilities);
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(AutoDataSwitchController
                .EVALUATION_REASON_DEFAULT_NETWORK_CHANGED);
        processAllMessages();

        // Verify register calls for *both* phones
        verify(mDisplayInfoController, times(modemCount)).registerForTelephonyDisplayInfoChanged(
                any(), eq(EVENT_DISPLAY_INFO_CHANGED), any());
        verify(mSignalStrengthController, times(modemCount)).registerForSignalStrengthChanged(any(),
                eq(EVENT_SIGNAL_STRENGTH_CHANGED), any());
        verify(mSST, times(modemCount)).registerForServiceStateChanged(any(),
                eq(EVENT_SERVICE_STATE_CHANGED), any());
        // Verify unregister calls were NOT made
        verify(mDisplayInfoController, never()).unregisterForTelephonyDisplayInfoChanged(any());
        verify(mSignalStrengthController, never()).unregisterForSignalStrengthChanged(any());
        verify(mSST, never()).unregisterForServiceStateChanged(any());
        clearInvocations(mDisplayInfoController, mSignalStrengthController, mSST); // Reset

        // --- Scenario 3: Default network lost (null) ---
        logd("Scenario 3: Default network lost (null)");
        // First switch to non-cellular to ensure listeners are off
        mAutoDataSwitchControllerUT.updateDefaultNetworkCapabilities(wifiCapabilities);
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(AutoDataSwitchController
                .EVALUATION_REASON_DEFAULT_NETWORK_CHANGED);
        processAllMessages();

        verify(mDisplayInfoController, times(modemCount))
                .unregisterForTelephonyDisplayInfoChanged(any());
        verify(mSignalStrengthController, times(modemCount))
                .unregisterForSignalStrengthChanged(any());
        verify(mSST, times(modemCount))
                .unregisterForServiceStateChanged(any());
        clearInvocations(mDisplayInfoController, mSignalStrengthController, mSST);

        // Now lose the network
        mAutoDataSwitchControllerUT.updateDefaultNetworkCapabilities(null);
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(AutoDataSwitchController
                .EVALUATION_REASON_DEFAULT_NETWORK_CHANGED);
        processAllMessages();

        // Verify register calls for *both* phones (null network means cellular is possible)
        verify(mDisplayInfoController, times(modemCount)).registerForTelephonyDisplayInfoChanged(
                any(), eq(EVENT_DISPLAY_INFO_CHANGED), any());
        verify(mSignalStrengthController, times(modemCount)).registerForSignalStrengthChanged(any(),
                eq(EVENT_SIGNAL_STRENGTH_CHANGED), any());
        verify(mSST, times(modemCount)).registerForServiceStateChanged(any(),
                eq(EVENT_SERVICE_STATE_CHANGED), any());

        verify(mDisplayInfoController, never())
                .unregisterForTelephonyDisplayInfoChanged(any());
        verify(mSignalStrengthController, never()).unregisterForSignalStrengthChanged(any());
        verify(mSST, never()).unregisterForServiceStateChanged(any());
    }

    @Test
    public void testRatSignalStrengthSkipEvaluation() {
        // Score NOT significantly better to justify the evaluation
        clearInvocations(mMockedPhoneSwitcherCallback);
        displayInfoChanged(PHONE_2, mBadTelephonyDisplayInfo);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback, never())
                .onRequireCancelAnyPendingAutoSwitchValidation();
        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(anyInt(), anyBoolean());
    }

    /**
     * Scenario 2: On Default, Candidate score IS better, BUT Candidate is NOT HOME.
     */
    @Test
    public void testBetterCandidate_onDefault_nonHome_scoreHigh_noEval() {
        doReturn(PHONE_1).when(mPhoneSwitcher).getPreferredDataPhoneId();

        // Setup state: Low score for default, high score for backup
        displayInfoChanged(PHONE_1, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_1, SignalStrength.SIGNAL_STRENGTH_POOR);
        displayInfoChanged(PHONE_2, mGoodTelephonyDisplayInfo); // High score inputs
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_GREAT);
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);
        processAllMessages();

        // Trigger internal call via another display info change
        displayInfoChanged(PHONE_2, mGoodTelephonyDisplayInfo);
        processAllMessages();

        // Verify no evaluation scheduled (skipped because not HOME)
        assertThat(mAutoDataSwitchControllerUT.hasMessages(EVENT_EVALUATE_AUTO_SWITCH)).isFalse();
        mAutoDataSwitchControllerUT.removeMessages(EVENT_EVALUATE_AUTO_SWITCH);
    }

    @Test
    public void testSwitchToOpportunistic_availability_primaryOOS() {
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOR_AVAILABILITY);
        setDefaultDataSubId(SUB_1); // Primary is default
        doReturn(PHONE_1).when(mPhoneSwitcher).getPreferredDataPhoneId(); // Currently on primary

        // Primary (PHONE_1) is OOS
        serviceStateChanged(PHONE_1,
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING);
        displayInfoChanged(PHONE_1, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_1, SignalStrength.SIGNAL_STRENGTH_POOR);

        // Opportunistic (PHONE_2) is HOME and good
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_2, mGoodTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_GREAT);

        // Ensure data settings are enabled
        doReturn(true).when(mPhone).isUserDataEnabled(); // Primary
        DataSettingsManager dsmPhone2 = mPhone2.getDataSettingsManager();
        doReturn(true).when(dsmPhone2).isDataEnabled(); // Opportunistic
        // Ensure opportunistic data is allowed by resetting
        mDataEvaluation = new DataEvaluation(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY);

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_REGISTRATION_STATE_CHANGED);
        processAllFutureMessages(); // Process initial evaluation and stability timer

        // Expect switch to opportunistic (PHONE_2)
        // needValidation is true because mRequirePingTestBeforeSwitch is true by default
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(PHONE_2, true);
    }

    @Test
    public void testSwitchToPrimary_availability_opportunisticOOS() {
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOR_AVAILABILITY);
        setDefaultDataSubId(SUB_1); // Primary is default
        // Assume currently on opportunistic (PHONE_2)
        doReturn(PHONE_2).when(mPhoneSwitcher).getPreferredDataPhoneId();
        doReturn(SUB_2).when(mPhoneSwitcher).getAutoSelectedDataSubId();


        // Primary (PHONE_1) is HOME
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_1, mGoodTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_1, SignalStrength.SIGNAL_STRENGTH_GREAT);

        // Opportunistic (PHONE_2) becomes OOS
        serviceStateChanged(PHONE_2,
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING);
        displayInfoChanged(PHONE_2, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_POOR);


        // Ensure data settings are enabled
        doReturn(true).when(mPhone).isUserDataEnabled(); // Primary
        DataSettingsManager dsmPhone2 = mPhone2.getDataSettingsManager();
        doReturn(true).when(dsmPhone2).isDataEnabled(); // Opportunistic
        // Ensure opportunistic data is allowed by resetting
        mDataEvaluation = new DataEvaluation(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY);

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_REGISTRATION_STATE_CHANGED);
        processAllFutureMessages();

        // Expect switch back to primary (DEFAULT_PHONE_INDEX which maps to PHONE_1)
        // needValidation is false because opportunistic is OOS
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX, false);
    }

    @Test
    public void testSwitchToOpportunistic_performance_primaryPoorOpportunisticGood() {
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOR_PERFORMANCE);
        setDefaultDataSubId(SUB_1); // Primary is default
        doReturn(PHONE_1).when(mPhoneSwitcher).getPreferredDataPhoneId(); // Currently on primary

        // Primary (PHONE_1) is HOME but poor signal
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_1, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_1, SignalStrength.SIGNAL_STRENGTH_POOR);

        // Opportunistic (PHONE_2) is HOME and excellent signal
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_2, mGoodTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_GREAT);

        // Ensure data settings are enabled
        doReturn(true).when(mPhone).isUserDataEnabled(); // Primary
        // Corrected stubbing for mPhone2's DataSettingsManager
        DataSettingsManager dsmPhone2 = mPhone2.getDataSettingsManager();
        doReturn(true).when(dsmPhone2).isDataEnabled(); // Opportunistic
        // Ensure opportunistic data is allowed by resetting
        mDataEvaluation = new DataEvaluation(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY);

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_SIGNAL_STRENGTH_CHANGED);
        processAllFutureMessages(); // Process initial evaluation and stability timer

        // Expect switch to opportunistic (PHONE_2)
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(PHONE_2, true);
    }

    @Test
    public void testSwitchToPrimary_performance_opportunisticPoorPrimaryGood() {
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOR_PERFORMANCE);
        setDefaultDataSubId(SUB_1); // Primary is default
        // Assume currently on opportunistic (PHONE_2)
        doReturn(PHONE_2).when(mPhoneSwitcher).getPreferredDataPhoneId();
        doReturn(SUB_2).when(mPhoneSwitcher).getAutoSelectedDataSubId();

        // Primary (PHONE_1) is HOME and excellent signal
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_1, mGoodTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_1, SignalStrength.SIGNAL_STRENGTH_GREAT);

        // Opportunistic (PHONE_2) is HOME but poor signal
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_2, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_POOR);

        // Ensure data settings are enabled
        doReturn(true).when(mPhone).isUserDataEnabled(); // Primary
        DataSettingsManager dsmPhone2 = mPhone2.getDataSettingsManager();
        doReturn(true).when(dsmPhone2).isDataEnabled(); // Opportunistic
        // Ensure opportunistic data is allowed by resetting
        mDataEvaluation = new DataEvaluation(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY);

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_SIGNAL_STRENGTH_CHANGED);
        processAllFutureMessages();

        // Expect switch back to primary (DEFAULT_PHONE_INDEX which maps to PHONE_1)
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(DEFAULT_PHONE_INDEX, true);
    }

    @Test
    public void testOpportunistic_noSwitchIfPolicyDisabled() {
        // Policy is disabled by default in setUp, but explicitly set here for clarity
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_DISABLED);
        setDefaultDataSubId(SUB_1);
        doReturn(PHONE_1).when(mPhoneSwitcher).getPreferredDataPhoneId();

        // Primary (PHONE_1) is OOS
        serviceStateChanged(PHONE_1,
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING);
        // Opportunistic (PHONE_2) is HOME and good
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_2, mGoodTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_GREAT);

        doReturn(true).when(mPhone).isUserDataEnabled();
        DataSettingsManager dsmPhone2 = mPhone2.getDataSettingsManager();
        doReturn(true).when(dsmPhone2).isDataEnabled(); // Opportunistic
        // Ensure opportunistic data is allowed by resetting
        mDataEvaluation = new DataEvaluation(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY);

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_REGISTRATION_STATE_CHANGED);
        processAllFutureMessages();

        // No switch should be attempted
        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(anyInt(), anyBoolean());
        verify(mMockedPhoneSwitcherCallback, never())
                .onRequireImmediatelySwitchToPhone(anyInt(), anyInt());
        verify(mMockedPhoneSwitcherCallback, never())
                .onRequireCancelAnyPendingAutoSwitchValidation();
    }

    @Test
    public void testOpportunistic_followSystem_availability() {
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOLLOW_SYSTEM);
        setDefaultDataSubId(SUB_1);
        doReturn(PHONE_1).when(mPhoneSwitcher).getPreferredDataPhoneId();

        serviceStateChanged(PHONE_1,
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING);
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_2, mGoodTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_GREAT);
        doReturn(true).when(mPhone).isUserDataEnabled();
        DataSettingsManager dsmPhone2 = mPhone2.getDataSettingsManager();
        doReturn(true).when(dsmPhone2).isDataEnabled(); // Opportunistic
        // Ensure opportunistic data is allowed by resetting
        mDataEvaluation = new DataEvaluation(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY);

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_REGISTRATION_STATE_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(PHONE_2, true);
    }

    @Test
    public void testOpportunistic_followSystem_performance() {
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOLLOW_SYSTEM);
        setDefaultDataSubId(SUB_1);
        doReturn(PHONE_1).when(mPhoneSwitcher).getPreferredDataPhoneId();

        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_1, mBadTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_1, SignalStrength.SIGNAL_STRENGTH_POOR);

        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_2, mGoodTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_GREAT);
        doReturn(true).when(mPhone).isUserDataEnabled();
        DataSettingsManager dsmPhone2 = mPhone2.getDataSettingsManager();
        doReturn(true).when(dsmPhone2).isDataEnabled(); // Opportunistic
        // Ensure opportunistic data is allowed by resetting
        mDataEvaluation = new DataEvaluation(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY);

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_SIGNAL_STRENGTH_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback).onRequireValidation(PHONE_2, true);
    }

    @Test
    public void testNoSwitch_opportunistic_primaryAndOpptSameGoodScore() {
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOR_PERFORMANCE);
        setDefaultDataSubId(SUB_1);
        doReturn(PHONE_1).when(mPhoneSwitcher).getPreferredDataPhoneId();

        // Both primary and opportunistic are HOME with good (same) scores
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_1, mGoodTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_1, SignalStrength.SIGNAL_STRENGTH_GREAT);

        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_2, mGoodTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_GREAT);

        doReturn(true).when(mPhone).isUserDataEnabled();
        DataSettingsManager dsmPhone2 = mPhone2.getDataSettingsManager();
        doReturn(true).when(dsmPhone2).isDataEnabled(); // Opportunistic
        // Ensure opportunistic data is allowed by resetting
        mDataEvaluation = new DataEvaluation(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY);

        // Clear mock interactions that may have occurred during test setup.
        clearInvocations(mMockedPhoneSwitcherCallback);

        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_SIGNAL_STRENGTH_CHANGED);
        processAllFutureMessages();

        // No switch should be attempted as scores are not significantly different
        // and we are already on the default (primary).
        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(anyInt(), anyBoolean());
        verify(mMockedPhoneSwitcherCallback, never())
                .onRequireImmediatelySwitchToPhone(anyInt(), anyInt());
        // Expect that an attempt to cancel any pending switch is made.
        verify(mMockedPhoneSwitcherCallback).onRequireCancelAnyPendingAutoSwitchValidation();
        verifyNoMoreInteractions(mMockedPhoneSwitcherCallback);
    }

    @Test
    public void testConstructor_nullCarrierConfigManager_shouldNotCrash() {
        doReturn(true).when(mFeatureFlags).monitorCarrierConfigChangeForAutoDataSwitch();
        doReturn(null).when(mContext).getSystemService(Context.CARRIER_CONFIG_SERVICE);
        clearInvocations(mCarrierConfigManager);

        try {
            new AutoDataSwitchController(mContext, Looper.myLooper(),
                    mPhoneSwitcher, mFeatureFlags, mMockedPhoneSwitcherCallback);
            verify(mCarrierConfigManager, never()).registerCarrierConfigChangeListener(any(),
                    any());
        } finally {
            doReturn(mCarrierConfigManager).when(mContext).getSystemService(
                    Context.CARRIER_CONFIG_SERVICE);
        }
    }

    @Test
    public void testShouldExcludeOpportunisticForSwitch_noSub() {
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOR_AVAILABILITY);
        doReturn(List.of()).when(mSubscriptionManagerService)
                .getActiveSubscriptionInfoList(any(), any(), anyBoolean());

        setupStatePrimaryIsOos();
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_REGISTRATION_STATE_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(anyInt(), anyBoolean());
    }

    @Test
    public void testShouldExcludeOpportunisticForSwitch_oneSub() {
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOR_AVAILABILITY);

        SubscriptionInfo subInfo1Primary = mock(SubscriptionInfo.class);
        doReturn(SUB_1).when(subInfo1Primary).getSubscriptionId();
        doReturn(false).when(subInfo1Primary).isOpportunistic();
        doReturn(true).when(subInfo1Primary).isActive();
        doReturn(ParcelUuid.fromString(TEST_UUID_STRING1)).when(subInfo1Primary).getGroupUuid();
        doReturn(List.of(subInfo1Primary)).when(
                mSubscriptionManagerService).getActiveSubscriptionInfoList(any(), any(),
                anyBoolean());

        setupStatePrimaryIsOos();
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_REGISTRATION_STATE_CHANGED);
        processAllFutureMessages();

        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(anyInt(), anyBoolean());
    }

    @Test
    public void testShouldExcludeOpportunisticForSwitch_onePrimaryOneOppt_differentGroup() {
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOR_AVAILABILITY);

        SubscriptionInfo subInfo1Primary = mock(SubscriptionInfo.class);
        doReturn(SUB_1).when(subInfo1Primary).getSubscriptionId();
        doReturn(false).when(subInfo1Primary).isOpportunistic();
        doReturn(true).when(subInfo1Primary).isActive();
        doReturn(ParcelUuid.fromString(TEST_UUID_STRING1)).when(subInfo1Primary).getGroupUuid();

        SubscriptionInfo subInfo2Opportunistic = mock(SubscriptionInfo.class);
        doReturn(SUB_2).when(subInfo2Opportunistic).getSubscriptionId();
        doReturn(true).when(subInfo2Opportunistic).isOpportunistic();
        doReturn(true).when(subInfo2Opportunistic).isActive();
        doReturn(ParcelUuid.fromString(TEST_UUID_STRING2)).when(
                subInfo2Opportunistic).getGroupUuid();

        doReturn(List.of(subInfo1Primary, subInfo2Opportunistic)).when(
                mSubscriptionManagerService).getActiveSubscriptionInfoList(any(), any(),
                anyBoolean());

        setupStatePrimaryIsOos();
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_REGISTRATION_STATE_CHANGED);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback, never()).onRequireValidation(anyInt(), anyBoolean());
    }

    @Test
    public void testShouldExcludeOpportunisticForSwitch_threeActiveSubs_primaryOpptInSameGroup() {
        // Future case, three active subs is not supported in current DSDS device yet
        setupOpportunisticSwitchMode(
                CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOR_AVAILABILITY);

        SubscriptionInfo subInfo1Primary = mock(SubscriptionInfo.class);
        doReturn(SUB_1).when(subInfo1Primary).getSubscriptionId();
        doReturn(false).when(subInfo1Primary).isOpportunistic();
        doReturn(true).when(subInfo1Primary).isActive();
        doReturn(ParcelUuid.fromString(TEST_UUID_STRING1)).when(subInfo1Primary).getGroupUuid();

        SubscriptionInfo subInfo2Opportunistic = mock(SubscriptionInfo.class);
        doReturn(SUB_2).when(subInfo2Opportunistic).getSubscriptionId();
        doReturn(true).when(subInfo2Opportunistic).isOpportunistic();
        doReturn(true).when(subInfo2Opportunistic).isActive();
        doReturn(ParcelUuid.fromString(TEST_UUID_STRING1)).when(
                subInfo2Opportunistic).getGroupUuid();

        SubscriptionInfo subInfo3Opportunistic = mock(SubscriptionInfo.class);
        doReturn(SUB_3).when(subInfo3Opportunistic).getSubscriptionId();
        doReturn(true).when(subInfo3Opportunistic).isOpportunistic();
        doReturn(true).when(subInfo3Opportunistic).isActive();
        doReturn(ParcelUuid.fromString(TEST_UUID_STRING2)).when(
                subInfo3Opportunistic).getGroupUuid();

        doReturn(List.of(subInfo1Primary, subInfo2Opportunistic, subInfo3Opportunistic)).when(
                mSubscriptionManagerService).getActiveSubscriptionInfoList(any(), any(),
                anyBoolean());

        setupStatePrimaryIsOos();
        mAutoDataSwitchControllerUT.evaluateAutoDataSwitch(
                EVALUATION_REASON_REGISTRATION_STATE_CHANGED);
        processAllFutureMessages();
        verify(mMockedPhoneSwitcherCallback).onRequireValidation(anyInt(), anyBoolean());
    }

    /**
     * Trigger conditions
     * 1. service state changes
     * 2. telephony display info changes
     * 3. signal strength changes
     * 4. data setting changes
     *      - user toggle data
     *      - user toggle auto switch feature
     * 5. default network changes
     *      - current network lost
     *      - network become active on non-cellular network
     */
    private void prepareIdealUsesNonDdsCondition() {
        // 1. service state changes
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        serviceStateChanged(PHONE_1, NetworkRegistrationInfo
                .REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING);

        // 2. telephony display info changes
        displayInfoChanged(PHONE_2, mGoodTelephonyDisplayInfo);
        displayInfoChanged(PHONE_1, mBadTelephonyDisplayInfo);

        // 3. signal strength changes
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_GREAT);
        signalStrengthChanged(PHONE_1, SignalStrength.SIGNAL_STRENGTH_POOR);

        // 4.1 User data enabled on primary SIM
        doReturn(true).when(mPhone).isUserDataEnabled();
        doReturn(true).when(mPhone).getDataRoamingEnabled();

        // 4.2 Auto switch feature is enabled
        doReturn(true).when(mPhone2).getDataRoamingEnabled();
        doReturn(true).when(mDataSettingsManager).isDataEnabled();
        mDataEvaluation.addDataAllowedReason(DataEvaluation.DataAllowedReason.NORMAL);

        // 5. No default network
        mAutoDataSwitchControllerUT.updateDefaultNetworkCapabilities(null /*networkCapabilities*/);
    }

    private void signalStrengthChanged(int phoneId, int level) {
        SignalStrength ss = mock(SignalStrength.class);
        doReturn(level).when(ss).getLevel();
        doReturn(ss).when(mPhones[phoneId]).getSignalStrength();

        Message msg = mAutoDataSwitchControllerUT.obtainMessage(EVENT_SIGNAL_STRENGTH_CHANGED);
        msg.obj = new AsyncResult(phoneId, null, null);
        mAutoDataSwitchControllerUT.sendMessage(msg);
        processAllMessages();
    }
    private void displayInfoChanged(int phoneId, TelephonyDisplayInfo telephonyDisplayInfo) {
        doReturn(telephonyDisplayInfo).when(mDisplayInfoController).getTelephonyDisplayInfo();

        Message msg = mAutoDataSwitchControllerUT.obtainMessage(EVENT_DISPLAY_INFO_CHANGED);
        msg.obj = new AsyncResult(phoneId, null, null);
        mAutoDataSwitchControllerUT.sendMessage(msg);
        processAllMessages();
    }
    private void serviceStateChanged(int phoneId,
            @NetworkRegistrationInfo.RegistrationState int dataRegState) {

        ServiceState ss = new ServiceState();

        ss.addNetworkRegistrationInfo(new NetworkRegistrationInfo.Builder()
                .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                .setRegistrationState(dataRegState)
                .setDomain(NetworkRegistrationInfo.DOMAIN_PS)
                .setIsNonTerrestrialNetwork(mIsNonTerrestrialNetwork)
                .build());

        ss.setDataRoamingFromRegistration(dataRegState
                == NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);

        doReturn(ss).when(mPhones[phoneId]).getServiceState();

        Message msg = mAutoDataSwitchControllerUT.obtainMessage(EVENT_SERVICE_STATE_CHANGED);
        msg.obj = new AsyncResult(phoneId, null, null);
        mAutoDataSwitchControllerUT.sendMessage(msg);
        processAllMessages();
    }
    private void setDefaultDataSubId(int defaultDataSub) {
        mDefaultDataSub = defaultDataSub;
        doReturn(mDefaultDataSub).when(mSubscriptionManagerService).getDefaultDataSubId();
    }

    private void setupOpportunisticSwitchMode(int opportunisticPolicyOnPrimarySub) {
        // Enable feature flag for opportunistic network switching logic
        doReturn(true).when(mFeatureFlags).macroBasedOpportunisticNetworks();

        // Simulate one primary visible subscription (SUB_1 on PHONE_1)
        doReturn(new int[]{SUB_1}).when(mSubscriptionManagerService)
                .getActiveSubIdList(true /*visibleOnly*/);

        // Simulate primary (SUB_1) and opportunistic (SUB_2) subscriptions being active overall
        doReturn(new int[]{SUB_1, SUB_2}).when(mSubscriptionManagerService)
                .getActiveSubIdList(false /*visibleOnly*/);

        // Mock SubscriptionInfo for SUB_1 (Primary)
        SubscriptionInfo subInfo1Primary = mock(SubscriptionInfo.class);
        doReturn(PHONE_1).when(subInfo1Primary).getSimSlotIndex();
        doReturn(SUB_1).when(subInfo1Primary).getSubscriptionId();
        doReturn(false).when(subInfo1Primary).isOpportunistic();
        doReturn(true).when(subInfo1Primary).isActive();
        doReturn("PrimarySub").when(subInfo1Primary).getDisplayName();
        doReturn(ParcelUuid.fromString(TEST_UUID_STRING1)).when(subInfo1Primary).getGroupUuid();

        // Mock SubscriptionInfo for SUB_2 (Opportunistic)
        SubscriptionInfo subInfo2Opportunistic = mock(SubscriptionInfo.class);
        doReturn(PHONE_2).when(subInfo2Opportunistic).getSimSlotIndex();
        doReturn(SUB_2).when(subInfo2Opportunistic).getSubscriptionId();
        doReturn(true).when(subInfo2Opportunistic).isOpportunistic();
        doReturn(true).when(subInfo2Opportunistic).isActive();
        doReturn("OpportunisticSub").when(subInfo2Opportunistic).getDisplayName();
        doReturn(ParcelUuid.fromString(TEST_UUID_STRING1)).when(
                subInfo2Opportunistic).getGroupUuid();

        doReturn(List.of(subInfo1Primary, subInfo2Opportunistic)).when(
                mSubscriptionManagerService).getActiveSubscriptionInfoList(any(), any(),
                anyBoolean());

        // Mock carrier config for the primary phone (PHONE_1, which is mPhone)
        // to set the opportunistic switch policy.
        if (mFeatureFlags.monitorCarrierConfigChangeForAutoDataSwitch()) {
            mPersistableBundle.putInt(CarrierConfigManager.KEY_OPP_AUTO_DATA_SWITCH_POLICY_INT,
                    opportunisticPolicyOnPrimarySub);
            doReturn(mPersistableBundle).when(mCarrierConfigManager).getConfigForSubId(anyInt(),
                    any());
        } else {
            doReturn(opportunisticPolicyOnPrimarySub)
                    .when(mDataConfigManager).getCarrierOverriddenAutoDataSwitchPolicyForOppt();
        }
    }

    private void setupStatePrimaryIsOos() {
        setDefaultDataSubId(SUB_1);
        doReturn(PHONE_1).when(mPhoneSwitcher).getPreferredDataPhoneId();

        serviceStateChanged(PHONE_1,
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING);
        serviceStateChanged(PHONE_2, NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        displayInfoChanged(PHONE_2, mGoodTelephonyDisplayInfo);
        signalStrengthChanged(PHONE_2, SignalStrength.SIGNAL_STRENGTH_GREAT);
        doReturn(true).when(mPhone).isUserDataEnabled();
        DataSettingsManager dsmPhone2 = mPhone2.getDataSettingsManager();
        doReturn(true).when(dsmPhone2).isDataEnabled();
        mDataEvaluation = new DataEvaluation(DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY);
    }

    @Override
    public void processAllFutureMessages() {
        if (mScheduledEventsToExtras.containsKey(EVENT_STABILITY_CHECK_PASSED)) {
            mEventsToAlarmListener.get(EVENT_STABILITY_CHECK_PASSED).onAlarm();
        }
        if (mScheduledEventsToExtras.containsKey(EVENT_EVALUATE_AUTO_SWITCH)) {
            mEventsToAlarmListener.get(EVENT_EVALUATE_AUTO_SWITCH).onAlarm();
        }
        super.processAllFutureMessages();
    }
}
