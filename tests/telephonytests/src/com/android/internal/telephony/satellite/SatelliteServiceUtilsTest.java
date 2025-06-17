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

package com.android.internal.telephony.satellite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.os.PersistableBundle;
import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class SatelliteServiceUtilsTest extends TelephonyTest {
    private static final String TAG = "SatelliteServiceUtilsTest";
    private static final int SUB_ID = 0;
    private static final int SUB_ID1 = 1;
    @Mock private ServiceState mServiceState2;

    @Mock SatelliteControllerTest.TestSatelliteController mMockSatelliteController;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);
        logd(TAG + " Setup!");

        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[]{mPhone, mPhone2});
        when(mPhone.getServiceState()).thenReturn(mServiceState);
        when(mPhone.getSubId()).thenReturn(SUB_ID);
        when(mPhone.getPhoneId()).thenReturn(0);
        when(mPhone2.getServiceState()).thenReturn(mServiceState2);
        when(mPhone2.getSubId()).thenReturn(SUB_ID1);
        when(mPhone2.getPhoneId()).thenReturn(1);
        replaceInstance(SatelliteController.class, "sInstance", null,
                mMockSatelliteController);
    }

    @After
    public void tearDown() throws Exception {
        logd(TAG + " tearDown");
        super.tearDown();
    }

    @Test
    public void testParseSupportedSatelliteServicesFromPersistableBundle() {
        PersistableBundle supportedServicesBundle = new PersistableBundle();
        String plmn1 = "10101";
        String plmn2 = "10102";
        String plmn3 = "10103";
        String plmn4 = "";
        String plmn5 = "123456789";
        int[] supportedServicesForPlmn1 = {1, 2, 3};
        int[] supportedServicesForPlmn2 = {3, 4, 100};
        int[] expectedServicesForPlmn1 = {1, 2, 3};
        int[] expectedServicesForPlmn2 = {3, 4};

        // Parse an empty bundle
        Map<String, Set<Integer>> supportedServiceMap =
                SatelliteServiceUtils.parseSupportedSatelliteServices(supportedServicesBundle);
        assertTrue(supportedServiceMap.isEmpty());

        // Add some more fields
        supportedServicesBundle.putIntArray(plmn1, supportedServicesForPlmn1);
        supportedServicesBundle.putIntArray(plmn2, supportedServicesForPlmn2);
        supportedServicesBundle.putIntArray(plmn3, new int[0]);
        supportedServicesBundle.putIntArray(plmn4, supportedServicesForPlmn1);
        supportedServicesBundle.putIntArray(plmn5, supportedServicesForPlmn2);

        supportedServiceMap =
                SatelliteServiceUtils.parseSupportedSatelliteServices(supportedServicesBundle);
        assertEquals(3, supportedServiceMap.size());

        assertTrue(supportedServiceMap.containsKey(plmn1));
        Set<Integer> supportedServices = supportedServiceMap.get(plmn1);
        assertTrue(Arrays.equals(expectedServicesForPlmn1,
                supportedServices.stream()
                        .mapToInt(Integer::intValue)
                        .toArray()));

        assertTrue(supportedServiceMap.containsKey(plmn2));
        supportedServices = supportedServiceMap.get(plmn2);
        assertTrue(Arrays.equals(expectedServicesForPlmn2,
                supportedServices.stream()
                        .mapToInt(Integer::intValue)
                        .toArray()));

        assertTrue(supportedServiceMap.containsKey(plmn3));
        supportedServices = supportedServiceMap.get(plmn3);
        assertTrue(supportedServices.isEmpty());

        assertFalse(supportedServiceMap.containsKey(plmn4));
        assertFalse(supportedServiceMap.containsKey(plmn5));
    }

    @Test
    public void testMergeStrLists() {
        List<String> l1 = Arrays.asList("1", "2", "2");
        List<String> l2 = Arrays.asList("1", "3", "3");
        List<String> expectedMergedList = Arrays.asList("1", "2", "3");
        List<String> mergedList = SatelliteServiceUtils.mergeStrLists(l1, l2);
        assertEquals(expectedMergedList, mergedList);

        List<String> l3 = Arrays.asList("2", "3", "4");
        expectedMergedList = Arrays.asList("1", "2", "3", "4");
        mergedList = SatelliteServiceUtils.mergeStrLists(l1, l2, l3);
        assertEquals(expectedMergedList, mergedList);
    }

    @Test
    public void testIsCellularAvailable() {
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState2.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState.getNetworkRegistrationInfo(anyInt(), anyInt())).thenReturn(null);
        when(mServiceState2.getNetworkRegistrationInfo(anyInt(), anyInt())).thenReturn(null);
        assertFalse(SatelliteServiceUtils.isCellularAvailable());

        when(mServiceState.getState()).thenReturn(ServiceState.STATE_EMERGENCY_ONLY);
        assertTrue(SatelliteServiceUtils.isCellularAvailable());

        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState2.getState()).thenReturn(ServiceState.STATE_EMERGENCY_ONLY);
        assertTrue(SatelliteServiceUtils.isCellularAvailable());

        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState2.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState2.isEmergencyOnly()).thenReturn(true);
        assertTrue(SatelliteServiceUtils.isCellularAvailable());

        NetworkRegistrationInfo dataNri = new NetworkRegistrationInfo.Builder()
                .setRegistrationState(NetworkRegistrationInfo.REGISTRATION_STATE_HOME)
                .build();
        when(mServiceState.getNetworkRegistrationInfo(anyInt(), anyInt())).thenReturn(dataNri);
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState2.isEmergencyOnly()).thenReturn(false);
        assertTrue(SatelliteServiceUtils.isCellularAvailable());

        dataNri = new NetworkRegistrationInfo.Builder()
                .setRegistrationState(NetworkRegistrationInfo.REGISTRATION_STATE_EMERGENCY)
                .build();
        when(mServiceState.getNetworkRegistrationInfo(anyInt(), anyInt())).thenReturn(dataNri);
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mServiceState2.isEmergencyOnly()).thenReturn(false);
        assertFalse(SatelliteServiceUtils.isCellularAvailable());
    }

    @Test
    public void testIsSatellitePlmn() {
        int subId = 1;

        when(mMockSatelliteController.getAllPlmnSet())
                .thenReturn(new HashSet<>(new ArrayList<>()));
        assertFalse(SatelliteServiceUtils.isSatellitePlmn(subId, mServiceState));

        // registered PLMN is null
        NetworkRegistrationInfo nri = new NetworkRegistrationInfo.Builder()
                .setRegisteredPlmn(null)
                .build();
        when(mServiceState.getNetworkRegistrationInfoListForTransportType(
                eq(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)))
                .thenReturn(List.of(nri));
        assertFalse(SatelliteServiceUtils.isSatellitePlmn(subId, mServiceState));

        // cell identity is null
        when(mMockSatelliteController.getAllPlmnSet()).thenReturn(
                new HashSet<>(List.of("120260")));
        nri = new NetworkRegistrationInfo.Builder()
                .setRegisteredPlmn("123456")
                .setCellIdentity(null)
                .build();
        when(mServiceState.getNetworkRegistrationInfoListForTransportType(
                eq(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)))
                .thenReturn(List.of(nri));
        assertFalse(SatelliteServiceUtils.isSatellitePlmn(subId, mServiceState));

        // mcc and mnc are null
        when(mCellIdentity.getMccString()).thenReturn(null);
        when(mCellIdentity.getMncString()).thenReturn(null);
        nri = new NetworkRegistrationInfo.Builder()
                .setRegisteredPlmn("123456")
                .setCellIdentity(mCellIdentity)
                .build();
        when(mServiceState.getNetworkRegistrationInfoListForTransportType(
                eq(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)))
                .thenReturn(List.of(nri));
        assertFalse(SatelliteServiceUtils.isSatellitePlmn(subId, mServiceState));

        // mccmnc equal to satellite PLMN
        when(mCellIdentity.getMccString()).thenReturn("120");
        when(mCellIdentity.getMncString()).thenReturn("260");
        nri = new NetworkRegistrationInfo.Builder()
                .setRegisteredPlmn("123456")
                .setCellIdentity(mCellIdentity)
                .build();
        when(mServiceState.getNetworkRegistrationInfoListForTransportType(
                eq(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)))
                .thenReturn(List.of(nri));
        assertTrue(SatelliteServiceUtils.isSatellitePlmn(subId, mServiceState));

        // registered PLMN equal to satellite PLMN
        when(mCellIdentity.getMccString()).thenReturn("123");
        when(mCellIdentity.getMncString()).thenReturn("456");
        nri = new NetworkRegistrationInfo.Builder()
                .setRegisteredPlmn("120260")
                .setCellIdentity(mCellIdentity)
                .build();
        when(mServiceState.getNetworkRegistrationInfoListForTransportType(
                eq(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)))
                .thenReturn(List.of(nri));
        assertTrue(SatelliteServiceUtils.isSatellitePlmn(subId, mServiceState));
    }
}
