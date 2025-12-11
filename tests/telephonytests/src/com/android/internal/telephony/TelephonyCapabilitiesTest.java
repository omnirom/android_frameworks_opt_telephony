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

package com.android.internal.telephony;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.os.Build;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.internal.telephony.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class TelephonyCapabilitiesTest extends TelephonyTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
                .thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testSupportsTelephonyFeatures_apiLevelOld() throws Exception {
        // A device that was originally released with API level 34, and which has not been
        // upgraded with a newer vendor partition introducing the new C/D/M feature flags.
        int vendorApiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        replaceInstance(TelephonyCapabilities.class, "VENDOR_API_LEVEL", null, vendorApiLevel);
        int boardApiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        replaceInstance(TelephonyCapabilities.class, "BOARD_API_LEVEL", null, boardApiLevel);

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA))
                .thenReturn(false);

        // The C/D/M flags are ignored, still reports all calling/messaging/data support.
        assertTrue(TelephonyCapabilities.supportsTelephonyCalling(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyMessaging(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyData(mFeatureFlags, mContext));
    }

    @Test
    public void testSupportsTelephonyFeatures_apiLevelNew() throws Exception {
        // A device that was originally released with API level 35
        int vendorApiLevel = Build.VERSION_CODES.VANILLA_ICE_CREAM;
        replaceInstance(TelephonyCapabilities.class, "VENDOR_API_LEVEL", null, vendorApiLevel);
        int boardApiLevel = Build.VERSION_CODES.VANILLA_ICE_CREAM;
        replaceInstance(TelephonyCapabilities.class, "BOARD_API_LEVEL", null, boardApiLevel);

        // Calling + Messaging + Data
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA))
                .thenReturn(true);
        assertTrue(TelephonyCapabilities.supportsTelephonyCalling(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyMessaging(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyData(mFeatureFlags, mContext));

        // Messaging + Data
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA))
                .thenReturn(true);
        assertFalse(TelephonyCapabilities.supportsTelephonyCalling(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyMessaging(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyData(mFeatureFlags, mContext));

        // Data-only
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA))
                .thenReturn(true);
        assertFalse(TelephonyCapabilities.supportsTelephonyCalling(mFeatureFlags, mContext));
        assertFalse(TelephonyCapabilities.supportsTelephonyMessaging(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyData(mFeatureFlags, mContext));

        // Calling + Messaging (unlikely, but tests the no-data case)
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA))
                .thenReturn(false);
        assertTrue(TelephonyCapabilities.supportsTelephonyCalling(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyMessaging(mFeatureFlags, mContext));
        assertFalse(TelephonyCapabilities.supportsTelephonyData(mFeatureFlags, mContext));
    }

    @Test
    @EnableFlags(Flags.FLAG_MINIMAL_TELEPHONY_CDM_CHECK_BOARD_API_LEVEL)
    public void testSupportsTelephonyFeatures_apiLevelUpdated_boardApiLevel() throws Exception {
        // EnableFlags doesn't work with mFeatureFlags
        doReturn(true).when(mFeatureFlags).minimalTelephonyCdmCheckBoardApiLevel();

        // A device that was originally released with API level 34, and which was later upgraded
        // with a newer vendor partition introducing the new C/D/M feature flags.
        int vendorApiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        replaceInstance(TelephonyCapabilities.class, "VENDOR_API_LEVEL", null, vendorApiLevel);
        int boardApiLevel = Build.VERSION_CODES.VANILLA_ICE_CREAM;
        replaceInstance(TelephonyCapabilities.class, "BOARD_API_LEVEL", null, boardApiLevel);

        // Calling + Messaging + Data
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA))
                .thenReturn(true);
        assertTrue(TelephonyCapabilities.supportsTelephonyCalling(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyMessaging(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyData(mFeatureFlags, mContext));

        // Messaging + Data
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA))
                .thenReturn(true);
        assertFalse(TelephonyCapabilities.supportsTelephonyCalling(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyMessaging(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyData(mFeatureFlags, mContext));

        // Data-only
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA))
                .thenReturn(true);
        assertFalse(TelephonyCapabilities.supportsTelephonyCalling(mFeatureFlags, mContext));
        assertFalse(TelephonyCapabilities.supportsTelephonyMessaging(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyData(mFeatureFlags, mContext));

        // Calling + Messaging (unlikely, but tests the no-data case)
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA))
                .thenReturn(false);
        assertTrue(TelephonyCapabilities.supportsTelephonyCalling(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyMessaging(mFeatureFlags, mContext));
        assertFalse(TelephonyCapabilities.supportsTelephonyData(mFeatureFlags, mContext));
    }

    @Test
    @DisableFlags(Flags.FLAG_MINIMAL_TELEPHONY_CDM_CHECK_BOARD_API_LEVEL)
    public void testSupportsTelephonyFeatures_apiLevelUpdated_vendorApiLevel() throws Exception {
        // DisableFlags doesn't work with mFeatureFlags
        doReturn(false).when(mFeatureFlags).minimalTelephonyCdmCheckBoardApiLevel();

        // A device that was originally released with API level 34, and which was later upgraded
        // with a newer vendor partition introducing the new C/D/M feature flags.
        int vendorApiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        replaceInstance(TelephonyCapabilities.class, "VENDOR_API_LEVEL", null, vendorApiLevel);
        int boardApiLevel = Build.VERSION_CODES.VANILLA_ICE_CREAM;
        replaceInstance(TelephonyCapabilities.class, "BOARD_API_LEVEL", null, boardApiLevel);

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA))
                .thenReturn(false);

        // The C/D/M flags are ignored, still reports all calling/messaging/data support.
        assertTrue(TelephonyCapabilities.supportsTelephonyCalling(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyMessaging(mFeatureFlags, mContext));
        assertTrue(TelephonyCapabilities.supportsTelephonyData(mFeatureFlags, mContext));
    }
}
