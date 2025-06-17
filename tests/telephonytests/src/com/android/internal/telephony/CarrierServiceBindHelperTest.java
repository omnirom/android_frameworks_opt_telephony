/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Message;
import android.service.carrier.CarrierService;
import android.service.carrier.ICarrierService;
import android.telephony.TelephonyManager.CarrierPrivilegesCallback;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.telephony.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class CarrierServiceBindHelperTest extends TelephonyTest {
    private static final int PHONE_ID_0 = 0;
    private static final int PHONE_ID_1 = 1;

    CarrierServiceBindHelper mCarrierServiceBindHelper;
    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        doReturn(1).when(mTelephonyManager).getActiveModemCount();
    }

    @After
    public void tearDown() throws Exception {
        mCarrierServiceBindHelper = null;
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testMultiSimConfigChanged() throws Exception {
        clearInvocations(mPhoneConfigurationManager);
        mCarrierServiceBindHelper = new CarrierServiceBindHelper(mContext);
        assertEquals(1, mCarrierServiceBindHelper.mBindings.size());
        assertEquals(1, mCarrierServiceBindHelper.mLastSimState.size());
        assertNotNull(mCarrierServiceBindHelper.mBindings.get(0));
        assertNotNull(mCarrierServiceBindHelper.mLastSimState.get(0));

        // Verify registration of EVENT_MULTI_SIM_CONFIG_CHANGED.
        doReturn(2).when(mTelephonyManager).getActiveModemCount();
        PhoneConfigurationManager.notifyMultiSimConfigChange(2);
        processAllMessages();

        assertEquals(2, mCarrierServiceBindHelper.mBindings.size());
        assertEquals(2, mCarrierServiceBindHelper.mLastSimState.size());
        assertNotNull(mCarrierServiceBindHelper.mBindings.get(0));
        assertNotNull(mCarrierServiceBindHelper.mBindings.get(1));
        assertNotNull(mCarrierServiceBindHelper.mLastSimState.get(0));
        assertNotNull(mCarrierServiceBindHelper.mLastSimState.get(1));

        // Switch back to single SIM.
        doReturn(1).when(mTelephonyManager).getActiveModemCount();
        PhoneConfigurationManager.notifyMultiSimConfigChange(1);
        processAllMessages();

        assertEquals(1, mCarrierServiceBindHelper.mBindings.size());
        assertEquals(1, mCarrierServiceBindHelper.mLastSimState.size());
        assertNotNull(mCarrierServiceBindHelper.mBindings.get(0));
        assertNotNull(mCarrierServiceBindHelper.mLastSimState.get(0));
    }

    @Test
    public void testUnbindWhenNotBound() throws Exception {
        mCarrierServiceBindHelper = new CarrierServiceBindHelper(mContext);

        // Try unbinding without binding and make sure we don't throw an Exception
        mCarrierServiceBindHelper.mHandler.handleMessage(
                Message.obtain(mCarrierServiceBindHelper.mHandler,
                        CarrierServiceBindHelper.EVENT_PERFORM_IMMEDIATE_UNBIND,
                        new Integer(0)));
    }

    // Verify a CarrierPrivilegesCallback is registered and return the callback object. May return
    // null if no callback is captured.
    private CarrierPrivilegesCallback expectRegisterCarrierPrivilegesCallback(int phoneId) {
        ArgumentCaptor<CarrierPrivilegesCallback> callbackCaptor =
                ArgumentCaptor.forClass(CarrierPrivilegesCallback.class);
        verify(mTelephonyManager)
                .registerCarrierPrivilegesCallback(eq(phoneId), any(), callbackCaptor.capture());
        return callbackCaptor.getAllValues().get(0);
    }

    @Test
    public void testCarrierPrivilegesCallbackRegistration() {
        // Device starts with DSDS mode
        doReturn(2).when(mTelephonyManager).getActiveModemCount();
        mCarrierServiceBindHelper = new CarrierServiceBindHelper(mContext);
        processAllMessages();

        // Verify that CarrierPrivilegesCallbacks are registered on both phones.
        // Capture the callbacks for further verification
        CarrierPrivilegesCallback phone0Callback =
                expectRegisterCarrierPrivilegesCallback(PHONE_ID_0);
        assertNotNull(phone0Callback);
        CarrierPrivilegesCallback phone1Callback =
                expectRegisterCarrierPrivilegesCallback(PHONE_ID_1);
        assertNotNull(phone1Callback);

        // Switch back to single SIM.
        doReturn(1).when(mTelephonyManager).getActiveModemCount();
        PhoneConfigurationManager.notifyMultiSimConfigChange(1);
        processAllMessages();

        // Verify the callback for phone1 had been unregistered while phone0 didn't.
        verify(mTelephonyManager).unregisterCarrierPrivilegesCallback(eq(phone1Callback));
        verify(mTelephonyManager, never()).unregisterCarrierPrivilegesCallback(eq(phone0Callback));
    }

    @Test
    public void testCarrierAppConnectionLost_resetsCarrierNetworkChange() {
        if (!Flags.disableCarrierNetworkChangeOnCarrierAppLost()) {
            return;
        }
        // Static test data
        String carrierServicePackageName = "android.test.package.carrier";
        ComponentName carrierServiceComponentName =
                new ComponentName("android.test.package", "carrier");
        ArgumentCaptor<ServiceConnection> serviceConnectionCaptor =
                ArgumentCaptor.forClass(ServiceConnection.class);
        ResolveInfo resolveInfo = new ResolveInfo();
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.packageName = carrierServicePackageName;
        serviceInfo.name = "carrier";
        serviceInfo.metaData = new Bundle();
        serviceInfo.metaData.putBoolean("android.service.carrier.LONG_LIVED_BINDING", true);
        resolveInfo.serviceInfo = serviceInfo;

        // Set up expectations for construction/initialization.
        doReturn(carrierServicePackageName)
                .when(mTelephonyManager)
                .getCarrierServicePackageNameForLogicalSlot(PHONE_ID_0);
        doReturn(1).when(mTelephonyManager).getActiveModemCount();
        doReturn(resolveInfo)
                .when(mPackageManager)
                .resolveService(any(), eq(PackageManager.GET_META_DATA));
        ICarrierService carrierServiceInterface = Mockito.mock(ICarrierService.class);
        mContextFixture.addService(
                CarrierService.CARRIER_SERVICE_INTERFACE,
                carrierServiceComponentName,
                carrierServicePackageName,
                carrierServiceInterface,
                serviceInfo);

        mCarrierServiceBindHelper = new CarrierServiceBindHelper(mContext);
        processAllMessages();

        CarrierPrivilegesCallback phoneCallback =
                expectRegisterCarrierPrivilegesCallback(PHONE_ID_0);
        assertNotNull(phoneCallback);
        phoneCallback.onCarrierServiceChanged(null, 0);
        processAllMessages();

        // Grab the ServiceConnection for CarrierService
        verify(mContext)
                .bindService(any(Intent.class), anyInt(), any(), serviceConnectionCaptor.capture());
        ServiceConnection serviceConnection = serviceConnectionCaptor.getAllValues().get(0);
        assertNotNull(serviceConnection);

        // Test CarrierService disconnection
        serviceConnection.onServiceDisconnected(carrierServiceComponentName);
        verify(mTelephonyRegistryManager).notifyCarrierNetworkChange(PHONE_ID_0, false);
    }

    @Test
    public void testCarrierAppBindingLost_resetsCarrierNetworkChange() {
        if (!Flags.disableCarrierNetworkChangeOnCarrierAppLost()) {
            return;
        }
        // Static test data
        String carrierServicePackageName = "android.test.package.carrier";
        ComponentName carrierServiceComponentName =
                new ComponentName("android.test.package", "carrier");
        ArgumentCaptor<ServiceConnection> serviceConnectionCaptor =
                ArgumentCaptor.forClass(ServiceConnection.class);
        ResolveInfo resolveInfo = new ResolveInfo();
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.packageName = carrierServicePackageName;
        serviceInfo.name = "carrier";
        serviceInfo.metaData = new Bundle();
        serviceInfo.metaData.putBoolean("android.service.carrier.LONG_LIVED_BINDING", true);
        resolveInfo.serviceInfo = serviceInfo;

        // Set up expectations for construction/initialization.
        doReturn(carrierServicePackageName)
                .when(mTelephonyManager)
                .getCarrierServicePackageNameForLogicalSlot(PHONE_ID_0);
        doReturn(1).when(mTelephonyManager).getActiveModemCount();
        doReturn(resolveInfo)
                .when(mPackageManager)
                .resolveService(any(), eq(PackageManager.GET_META_DATA));
        ICarrierService carrierServiceInterface = Mockito.mock(ICarrierService.class);
        mContextFixture.addService(
                CarrierService.CARRIER_SERVICE_INTERFACE,
                carrierServiceComponentName,
                carrierServicePackageName,
                carrierServiceInterface,
                serviceInfo);

        mCarrierServiceBindHelper = new CarrierServiceBindHelper(mContext);
        processAllMessages();

        CarrierPrivilegesCallback phoneCallback =
                expectRegisterCarrierPrivilegesCallback(PHONE_ID_0);
        assertNotNull(phoneCallback);
        phoneCallback.onCarrierServiceChanged(null, 0);
        processAllMessages();

        // Grab the ServiceConnection for CarrierService
        verify(mContext)
                .bindService(any(Intent.class), anyInt(), any(), serviceConnectionCaptor.capture());
        ServiceConnection serviceConnection = serviceConnectionCaptor.getAllValues().get(0);
        assertNotNull(serviceConnection);

        // Test CarrierService disconnection
        serviceConnection.onBindingDied(carrierServiceComponentName);
        verify(mTelephonyRegistryManager).notifyCarrierNetworkChange(PHONE_ID_0, false);
    }
    // TODO (b/232461097): Add UT cases to cover more scenarios (user unlock, SIM state change...)
}
