/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.AccessNetworkConstants;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.SparseArray;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.data.DataSettingsManager.DataSettingsManagerCallback;
import com.android.internal.telephony.subscription.SubscriptionInfoInternal;
import com.android.internal.telephony.subscription.SubscriptionManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DataSettingsManagerTest extends TelephonyTest {
    private static final String DATA_ROAMING_IS_USER_SETTING = "data_roaming_is_user_setting_key0";

    // Mocked
    DataSettingsManagerCallback mMockedDataSettingsManagerCallback;

    DataSettingsManager mDataSettingsManagerUT;
    PersistableBundle mBundle;
    List<DataServiceManager> mMockDataServiceManagers;

    @Before
    public void setUp() throws Exception {
        logd("DataSettingsManagerTest +Setup!");
        super.setUp(getClass().getSimpleName());
        mMockedDataSettingsManagerCallback = Mockito.mock(DataSettingsManagerCallback.class);
        mBundle = mContextFixture.getCarrierConfigBundle();
        mMockDataServiceManagers = List.of(
                mMockedWwanDataServiceManager,
                mMockedWlanDataServiceManager
        );
        SparseArray<DataServiceManager> mMockDataServiceManagerSparseArray = new SparseArray<>();
        mMockDataServiceManagerSparseArray.put(AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                mMockedWwanDataServiceManager);
        mMockDataServiceManagerSparseArray.put(AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                mMockedWlanDataServiceManager);
        doReturn(true).when(mDataConfigManager).isConfigCarrierSpecific();
        doReturn(true).when(mFeatureFlags).dataServiceUserDataToggleNotify();

        doReturn(new SubscriptionInfoInternal.Builder().setId(1).build())
                .when(mSubscriptionManagerService).getSubscriptionInfoInternal(anyInt());

        mDataSettingsManagerUT = new DataSettingsManager(mPhone, mDataNetworkController,
                mMockDataServiceManagerSparseArray,
                mFeatureFlags, Looper.myLooper(), mMockedDataSettingsManagerCallback);
        logd("DataSettingsManagerTest -Setup!");
    }

    @After
    public void tearDown() throws Exception {
        logd("tearDown");
        super.tearDown();
    }

    @Test
    public void testMobileDataPolicyParsing() {
        //Valid new data policy
        Set<Integer> policies = mDataSettingsManagerUT.getMobileDataPolicyEnabled("1, 2");
        assertThat(policies.size()).isEqualTo(2);
        Set<Integer> policies2 = mDataSettingsManagerUT.getMobileDataPolicyEnabled(",2");
        assertThat(policies2.size()).isEqualTo(1);
        Set<Integer> policies3 = mDataSettingsManagerUT.getMobileDataPolicyEnabled("");
        assertThat(policies3.size()).isEqualTo(0);

        // Invalid
        Set<Integer> invalid = mDataSettingsManagerUT.getMobileDataPolicyEnabled(
                "nonExistent, 1, 2");
        assertThat(invalid.size()).isEqualTo(2);

        Set<Integer> invalid2 = mDataSettingsManagerUT.getMobileDataPolicyEnabled(
                "nonExistent ,,");
        assertThat(invalid2.size()).isEqualTo(0);
    }

    @Test
    public void testGetPolicies() {
        mDataSettingsManagerUT.setMobileDataPolicy(1, true);
        mDataSettingsManagerUT.setMobileDataPolicy(2, true);
        processAllMessages();

        ArgumentCaptor<String> stringArgumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(mSubscriptionManagerService, times(2))
                .setEnabledMobileDataPolicies(anyInt(), stringArgumentCaptor.capture());
        assertEquals("1,2", stringArgumentCaptor.getValue());
    }

    @Test
    public void testDefaultDataRoamingEnabled() {
        doReturn(true).when(mDataConfigManager).isDataRoamingEnabledByDefault();
        mMockDataServiceManagers.forEach(Mockito::clearInvocations);
        mDataSettingsManagerUT.setDefaultDataRoamingEnabled();
        assertTrue(mDataSettingsManagerUT.isDataRoamingEnabled());
        mMockDataServiceManagers.forEach(mockDataServiceManager -> {
            // should notify the default data roaming
            verify(mockDataServiceManager,
                    times(1)).notifyUserDataRoamingEnabled(eq(true),
                    isNull());
            clearInvocations(mockDataServiceManager);
        });

        mDataSettingsManagerUT.setDataRoamingEnabled(false);
        processAllMessages();
        assertFalse(mDataSettingsManagerUT.isDataRoamingEnabled());
        mMockDataServiceManagers.forEach(mockDataServiceManager -> {
            verify(mockDataServiceManager,
                    times(1)).notifyUserDataRoamingEnabled(eq(false),
                    isNull());
            clearInvocations(mockDataServiceManager);
        });

        mDataSettingsManagerUT.setDefaultDataRoamingEnabled();
        assertFalse(mDataSettingsManagerUT.isDataRoamingEnabled());
        // Should not notify data service manager when there have no changes
        mMockDataServiceManagers.forEach(mockDataServiceManager -> verify(mockDataServiceManager,
                never()).notifyUserDataRoamingEnabled(anyBoolean(),
                isNull()));
    }

    @Test
    public void testDefaultDataRoamingEnabledFromUpgrade() {
        doReturn(true).when(mDataConfigManager).isDataRoamingEnabledByDefault();
        mContext.getSharedPreferences("", 0).edit()
                .putBoolean(DATA_ROAMING_IS_USER_SETTING, true).commit();
        mDataSettingsManagerUT.setDefaultDataRoamingEnabled();
        assertFalse(mDataSettingsManagerUT.isDataRoamingEnabled());
    }

    @Test
    public void testUpdateDataEnabledAndNotifyOverride() throws Exception {
        // Mock another DDS phone.
        int ddsPhoneId = 1;
        int ddsSubId = 2;
        doReturn(ddsSubId).when(mSubscriptionManagerService).getDefaultDataSubId();
        Phone phone2 = Mockito.mock(Phone.class);
        doReturn(ddsPhoneId).when(phone2).getPhoneId();
        doReturn(ddsSubId).when(phone2).getSubId();
        doReturn(ddsPhoneId).when(mSubscriptionManagerService).getPhoneId(ddsSubId);
        DataSettingsManager dataSettingsManager2 = Mockito.mock(DataSettingsManager.class);
        doReturn(dataSettingsManager2).when(phone2).getDataSettingsManager();
        mPhones = new Phone[] {mPhone, phone2};
        replaceInstance(PhoneFactory.class, "sPhones", null, mPhones);
        ArgumentCaptor<DataSettingsManagerCallback> callbackArgumentCaptor = ArgumentCaptor
                .forClass(DataSettingsManagerCallback.class);

        mDataSettingsManagerUT.sendEmptyMessage(11 /* EVENT_INITIALIZE */);
        processAllMessages();

        // Verify listening to user enabled status of other phones.
        verify(dataSettingsManager2).registerCallback(callbackArgumentCaptor.capture());
        DataSettingsManagerCallback callback = callbackArgumentCaptor.getValue();

        // Mock the phone as nonDDS.
        mDataSettingsManagerUT.setDataEnabled(TelephonyManager.DATA_ENABLED_REASON_USER, false, "");
        processAllMessages();
        clearInvocations(mPhone);

        // Verify the override policy doesn't take effect because the DDS is user disabled.
        mDataSettingsManagerUT.setMobileDataPolicy(
                TelephonyManager.MOBILE_DATA_POLICY_AUTO_DATA_SWITCH, true);
        processAllMessages();
        verify(mPhone, never()).notifyDataEnabled(anyBoolean(), anyInt());

        // Verify the override takes effect upon DDS user enabled.
        doReturn(true).when(phone2).isUserDataEnabled();
        callback.onUserDataEnabledChanged(true, "callingPackage");
        verify(mPhone).notifyDataEnabled(true, TelephonyManager.DATA_ENABLED_REASON_OVERRIDE);
    }

    @Test
    public void testUpdateDataEnabledAndNotifyOverrideDdsChange() throws Exception {
        // Mock 2nd phone the DDS phone.
        int phone2Id = 1;
        int phone2SubId = 2;
        doReturn(phone2SubId).when(mSubscriptionManagerService).getDefaultDataSubId();
        Phone phone2 = Mockito.mock(Phone.class);
        doReturn(phone2Id).when(phone2).getPhoneId();
        doReturn(phone2SubId).when(phone2).getSubId();
        doReturn(phone2Id).when(mSubscriptionManagerService).getPhoneId(phone2SubId);
        DataSettingsManager dataSettingsManager2 = Mockito.mock(DataSettingsManager.class);
        doReturn(dataSettingsManager2).when(phone2).getDataSettingsManager();
        doReturn(true).when(phone2).isUserDataEnabled();

        mPhones = new Phone[] {mPhone, phone2};
        replaceInstance(PhoneFactory.class, "sPhones", null, mPhones);
        ArgumentCaptor<SubscriptionManagerService.SubscriptionManagerServiceCallback>
                callbackArgumentCaptor = ArgumentCaptor
                .forClass(SubscriptionManagerService.SubscriptionManagerServiceCallback.class);

        mDataSettingsManagerUT.sendEmptyMessage(11 /* EVENT_INITIALIZE */);
        mDataSettingsManagerUT.setDataEnabled(TelephonyManager.DATA_ENABLED_REASON_USER, false, "");
        processAllMessages();

        // Verify listening to DDS change callback
        verify(mSubscriptionManagerService, times(2))
                .registerCallback(callbackArgumentCaptor.capture());
        SubscriptionManagerService.SubscriptionManagerServiceCallback callback =
                callbackArgumentCaptor.getValue();

        // Mock the phone as nonDDS auto switch override enabled.
        clearInvocations(mPhones);
        mDataSettingsManagerUT.setMobileDataPolicy(
                TelephonyManager.MOBILE_DATA_POLICY_AUTO_DATA_SWITCH, true);
        processAllMessages();
        verify(mPhone).notifyDataEnabled(true, TelephonyManager.DATA_ENABLED_REASON_OVERRIDE);

        // The phone became DDS, data should be disabled
        doReturn(mPhone.getSubId()).when(mSubscriptionManagerService).getDefaultDataSubId();
        callback.onDefaultDataSubscriptionChanged(mPhone.getSubId());
        verify(mPhone).notifyDataEnabled(false, TelephonyManager.DATA_ENABLED_REASON_OVERRIDE);
    }

    @Test
    public void testUpdateDataEnabledAndNotifyDataService() throws Exception {
        // Mock 2nd phone the DDS phone.
        int phone2Id = 1;
        int phone2SubId = 2;
        doReturn(phone2SubId).when(mSubscriptionManagerService).getDefaultDataSubId();
        Phone phone2 = Mockito.mock(Phone.class);
        doReturn(phone2Id).when(phone2).getPhoneId();
        doReturn(phone2SubId).when(phone2).getSubId();
        doReturn(phone2Id).when(mSubscriptionManagerService).getPhoneId(phone2SubId);
        DataSettingsManager dataSettingsManager2 = Mockito.mock(DataSettingsManager.class);
        doReturn(dataSettingsManager2).when(phone2).getDataSettingsManager();
        doReturn(true).when(phone2).isUserDataEnabled();

        mPhones = new Phone[]{mPhone, phone2};
        replaceInstance(PhoneFactory.class, "sPhones", null, mPhones);

        processAllMessages();
        mMockDataServiceManagers.forEach(Mockito::clearInvocations);

        mDataSettingsManagerUT.sendEmptyMessage(11 /* EVENT_INITIALIZE */);
        processAllMessages();
        // Verify notified user data enabling state to data service manager when initialize
        mMockDataServiceManagers.forEach(mockDataServiceManager -> {
            verify(mockDataServiceManager, times(1)).notifyUserDataEnabled(eq(true),
                    isNull());
            clearInvocations(mockDataServiceManager);
        });

        mDataSettingsManagerUT.setDataEnabled(TelephonyManager.DATA_ENABLED_REASON_USER, false, "");
        processAllMessages();
        // Verify notified user data enabling state to data service manager when data disabled
        mMockDataServiceManagers.forEach(mockDataServiceManager -> {
            verify(mockDataServiceManager, times(1)).notifyUserDataEnabled(eq(false),
                    isNull());
            clearInvocations(mockDataServiceManager);
        });

        mDataSettingsManagerUT.setDataEnabled(TelephonyManager.DATA_ENABLED_REASON_USER, true, "");
        processAllMessages();
        // Verify notified user data enabling state to data service manager when data enabled
        mMockDataServiceManagers.forEach(mockDataServiceManager -> {
            verify(mockDataServiceManager, times(1)).notifyUserDataEnabled(eq(true),
                    isNull());
            clearInvocations(mockDataServiceManager);
        });
    }

    @Test
    public void testInitializeNotifyDataServiceUserDataEnabled() throws Exception {
        mMockDataServiceManagers.forEach(mockDataServiceManager -> {
            // After initialize, should notify data service the current data & roaming enabled state
            verify(mockDataServiceManager, times(1)).notifyUserDataEnabled(eq(true),
                    isNull());
            verify(mockDataServiceManager, times(1)).notifyUserDataRoamingEnabled(anyBoolean(),
                    isNull());
        });
    }

    @Test
    public void testNotifyDataServiceUserDataEnabledWhenSimStateChanged() {
        processAllMessages();
        mMockDataServiceManagers.forEach(Mockito::clearInvocations);


        var dataNetworkControllerCallbackArgumentCaptor = ArgumentCaptor.forClass(
                DataNetworkController.DataNetworkControllerCallback.class);
        verify(mDataNetworkController).registerDataNetworkControllerCallback(
                dataNetworkControllerCallbackArgumentCaptor.capture());
        mMockDataServiceManagers.forEach(Mockito::clearInvocations);

        // Sim absent should not trigger notify
        dataNetworkControllerCallbackArgumentCaptor.getValue().onSimStateChanged(
                TelephonyManager.SIM_STATE_ABSENT);
        processAllMessages();

        mMockDataServiceManagers.forEach(mockDataServiceManager -> {
            verify(mockDataServiceManager, never()).notifyUserDataRoamingEnabled(anyBoolean(),
                    any());
            verify(mockDataServiceManager, never()).notifyUserDataEnabled(anyBoolean(), any());
        });
        mMockDataServiceManagers.forEach(Mockito::clearInvocations);

        // Sim loaded should trigger notify
        dataNetworkControllerCallbackArgumentCaptor.getValue().onSimStateChanged(
                TelephonyManager.SIM_STATE_LOADED);
        processAllMessages();

        mMockDataServiceManagers.forEach(mockDataServiceManager -> {
            verify(mockDataServiceManager, times(1)).notifyUserDataRoamingEnabled(eq(false),
                    isNull());
            verify(mockDataServiceManager, times(1)).notifyUserDataEnabled(eq(true),
                    isNull());
        });
    }

    @Test
    public void testNotifyCorrespondingDataServiceUserDataEnabledWhenDataServiceBound() {
        processAllMessages();
        mMockDataServiceManagers.forEach(Mockito::clearInvocations);

        var dataNetworkControllerCallbackArgumentCaptor = ArgumentCaptor.forClass(
                DataNetworkController.DataNetworkControllerCallback.class);
        verify(mDataNetworkController).registerDataNetworkControllerCallback(
                dataNetworkControllerCallbackArgumentCaptor.capture());
        mMockDataServiceManagers.forEach(Mockito::clearInvocations);

        dataNetworkControllerCallbackArgumentCaptor.getValue().onDataServiceBound(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        processAllMessages();

        // Notify the bound data service manager
        verify(mMockedWwanDataServiceManager, times(1)).notifyUserDataRoamingEnabled(eq(false),
                isNull());
        verify(mMockedWwanDataServiceManager, times(1)).notifyUserDataEnabled(eq(true),
                isNull());
        // Should not notify another data service manager
        verify(mMockedWlanDataServiceManager, never()).notifyUserDataRoamingEnabled(anyBoolean(),
                any());
        verify(mMockedWlanDataServiceManager, never()).notifyUserDataEnabled(anyBoolean(), any());
    }

    @Test
    public void testNotifyDataEnabledFromNewValidSubId() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mDataSettingsManagerUT.registerCallback(
                new DataSettingsManagerCallback(mDataSettingsManagerUT::post) {
                    @Override
                    public void onDataEnabledChanged(boolean enabled,
                            @TelephonyManager.DataEnabledChangedReason int reason,
                            @NonNull String callingPackage) {
                        latch.countDown();
                    }
                });

        Message.obtain(mDataSettingsManagerUT, 4 /* EVENT_SUBSCRIPTIONS_CHANGED */, -1)
                .sendToTarget();
        Message.obtain(mDataSettingsManagerUT, 4 /* EVENT_SUBSCRIPTIONS_CHANGED */, 2)
                .sendToTarget();
        processAllMessages();

        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
    }
}
