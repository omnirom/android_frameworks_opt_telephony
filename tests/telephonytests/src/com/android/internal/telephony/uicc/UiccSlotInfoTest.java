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
package com.android.internal.telephony.uicc;
// Note: this test is copied from com.google.android.gts.telephony

import static android.telephony.UiccSlotInfo.CARD_STATE_INFO_PRESENT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.telephony.TelephonyManager;
import android.telephony.UiccPortInfo;
import android.telephony.UiccSlotInfo;
import android.testing.AndroidTestingRunner;
import android.text.TextUtils;

import androidx.test.filters.SmallTest;

import com.android.internal.telephony.TelephonyTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

/**
 * Test UiccSlotInfoTest class system APIs.
 */
@RunWith(AndroidTestingRunner.class)
public class UiccSlotInfoTest extends TelephonyTest {
    private static final String CARD_ID = "CA12D1D"; // fake card IccId

    @Test
    @SmallTest
    public void testUiccSlotInfoConstructor_v1_Deprecated() {
        UiccSlotInfo uiccSlotInfo = new UiccSlotInfo(
                true, /* isActive */
                false, /* isEuicc */
                CARD_ID, /* iccId */
                CARD_STATE_INFO_PRESENT,
                1,  /* logicalSlotIdx */
                true /* isExtendApduSupported */
        );

        verifyParcelable(uiccSlotInfo);
        verifyCommonFields(uiccSlotInfo);

        assertEquals(uiccSlotInfo.getIsEuicc(), false);
        assertTrue(Arrays.equals(new int[] {TelephonyManager.SIM_TYPE_UNKNOWN},
                    uiccSlotInfo.getSupportedSimTypes()));
        assertEquals(uiccSlotInfo.getSimType(), TelephonyManager.SIM_TYPE_UNKNOWN);
        assertEquals(uiccSlotInfo.isRemovable(), false); // This is approximately a bug, but it's
                                                         // legacy behavior. isRemovable is just
                                                         // hard-coded to false when using this
                                                         // particular constructor.
    }

    @Test
    @SmallTest
    public void testUiccSlotInfoConstructor_v2() {
        UiccPortInfo uiccPortInfo = new UiccPortInfo(
                CARD_ID,      /* iccId */
                0,       /* portIdx */
                1,  /* logicalSlotIdx */
                true   /* isActive */
        );

        UiccSlotInfo uiccSlotInfo = new UiccSlotInfo(
                false, /* isEuicc */
                CARD_ID, /* iccId */
                CARD_STATE_INFO_PRESENT, /* cardStateInfo */
                true, /* isExtendApduSupported */
                true, /* isRemovable */
                Collections.singletonList(uiccPortInfo)
        );

        verifyParcelable(uiccSlotInfo);
        verifyCommonFields(uiccSlotInfo);

        assertEquals(uiccSlotInfo.getIsEuicc(), false);
        assertTrue(Arrays.equals(new int[] {TelephonyManager.SIM_TYPE_UNKNOWN},
                    uiccSlotInfo.getSupportedSimTypes()));
        assertEquals(uiccSlotInfo.getSimType(), TelephonyManager.SIM_TYPE_UNKNOWN);
        assertEquals(uiccSlotInfo.isRemovable(), true);
        assertEquals(uiccSlotInfo.getPorts().iterator().next(), uiccPortInfo);
    }

    @Test
    @SmallTest
    public void testUiccSlotInfoConstructor_v3() {
        UiccPortInfo uiccPortInfo = new UiccPortInfo(
                "1CC1D",      /* iccId */
                0,       /* portIdx */
                1,  /* logicalSlotIdx */
                true   /* isActive */
        );
        UiccSlotInfo uiccSlotInfo = new UiccSlotInfo(
                true, /* isEuicc */
                CARD_ID, /* CARD_ID */
                CARD_STATE_INFO_PRESENT, /* cardStateInfo */
                true, /* isExtendApduSupported */
                false, /* isRemovable */
                Collections.singletonList(uiccPortInfo),
                TelephonyManager.SIM_TYPE_EMBEDDED,
                new int[] {TelephonyManager.SIM_TYPE_EMBEDDED}
        );

        verifyParcelable(uiccSlotInfo);
        verifyCommonFields(uiccSlotInfo);

        assertEquals(uiccSlotInfo.getIsEuicc(), true);
        assertTrue(Arrays.equals(new int[] {TelephonyManager.SIM_TYPE_EMBEDDED},
                    uiccSlotInfo.getSupportedSimTypes()));
        assertEquals(uiccSlotInfo.getSimType(), TelephonyManager.SIM_TYPE_EMBEDDED);
        assertEquals(uiccSlotInfo.isRemovable(), false);
        assertEquals(uiccSlotInfo.getPorts().iterator().next(), uiccPortInfo);
    }

    private void verifyCommonFields(UiccSlotInfo uiccSlotInfo) {
        assertEquals(uiccSlotInfo.getIsExtendedApduSupported(), true);
        assertEquals(uiccSlotInfo.getCardId(), CARD_ID);
        assertEquals(uiccSlotInfo.getCardStateInfo(), CARD_STATE_INFO_PRESENT);
        assertEquals(uiccSlotInfo.getLogicalSlotIdx(), 1);
        assertEquals(uiccSlotInfo.getIsActive(), true);

        assertEquals(uiccSlotInfo.describeContents(), 0);
        assertNotEquals(uiccSlotInfo.hashCode(), 0);
        assertFalse(TextUtils.isEmpty(uiccSlotInfo.toString()));
    }

    private void verifyParcelable(UiccSlotInfo uiccSlotInfo) {
        // Parcel read and write.
        Parcel parcel = Parcel.obtain();
        uiccSlotInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        UiccSlotInfo toCompare = UiccSlotInfo.CREATOR.createFromParcel(parcel);
        assertEquals(uiccSlotInfo.hashCode(), toCompare.hashCode());
        assertEquals(uiccSlotInfo, toCompare);
    }
}
