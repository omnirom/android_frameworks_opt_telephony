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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import android.os.Parcel;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.UiccPortInfo;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.telephony.TelephonyTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidTestingRunner.class)
public class UiccCardInfoTest extends TelephonyTest {

    private UiccPortInfo mUiccPortInfo1;
    private UiccPortInfo mUiccPortInfo2;
    private List<UiccPortInfo> mPortList;

    @Before
    public void setUp() throws Exception {
        super.setUp("UiccCardInfoTest");
        mUiccPortInfo1 = new UiccPortInfo("test_icc_id_1", 0, 0, true);
        mUiccPortInfo2 = new UiccPortInfo("test_icc_id_2", 1, 0, true);
        mPortList = new ArrayList<>();
        mPortList.add(mUiccPortInfo1);
        mPortList.add(mUiccPortInfo2);
    }

    @After
    public void tearDown() throws Exception {
        mPortList = null;
        mUiccPortInfo1 = null;
        mUiccPortInfo2 = null;
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testCreateSensitiveInfoSanitizedCopy_withCarrierPrivileges() {
        UiccCardInfo originalCardInfo = new UiccCardInfo(
                true /*isEuicc*/,
                123 /*cardId*/,
                "test_eid" /*eid*/,
                0 /*physicalSlotIndex*/,
                false /*isRemovable*/,
                true /*isMultipleEnabledProfilesSupported*/,
                mPortList);

        UiccCardInfo sanitizedInfo = originalCardInfo.createSensitiveInfoSanitizedCopy(true);

        List<UiccPortInfo> expectedPorts = new ArrayList<>();
        expectedPorts.add(new UiccPortInfo(UiccPortInfo.ICCID_REDACTED, 0, 0, true));
        expectedPorts.add(new UiccPortInfo(UiccPortInfo.ICCID_REDACTED, 1, 0, true));

        UiccCardInfo expectedCardInfo = new UiccCardInfo(
                true, // isEuicc
                123, //cardId
                null, // eid
                0, // physicalSlotIndex
                false, // isRemovable
                true, // isMultipleEnabledProfilesSupported
                expectedPorts // portList
        );
        assertEquals(expectedCardInfo, sanitizedInfo);
    }

    @Test
    @SmallTest
    public void testCreateSensitiveInfoSanitizedCopy_withoutCarrierPrivileges() {
        UiccCardInfo originalCardInfo = new UiccCardInfo(
                true /*isEuicc*/,
                123 /*cardId*/,
                "test_eid" /*eid*/,
                1 /*physicalSlotIndex*/,
                true /*isRemovable*/,
                false /*isMultipleEnabledProfilesSupported*/,
                mPortList);

        UiccCardInfo sanitizedInfo = originalCardInfo.createSensitiveInfoSanitizedCopy(false);
        List<UiccPortInfo> expectedPorts = new ArrayList<>();
        for (UiccPortInfo portInfo : mPortList) {
            expectedPorts.add(portInfo.createSensitiveInfoSanitizedCopy());
        }

        UiccCardInfo expectedCardInfo = new UiccCardInfo(
                true, // isEuicc
                TelephonyManager.UNINITIALIZED_CARD_ID, //cardId
                null, // eid
                1, // physicalSlotIndex
                true, // isRemovable
                false, // isMultipleEnabledProfilesSupported
                expectedPorts // portList
        );
        assertEquals(expectedCardInfo, sanitizedInfo);
    }

    @Test
    @SmallTest
    public void testParcelable() {
        UiccCardInfo cardInfo = new UiccCardInfo(
                true /*isEuicc*/,
                456 /*cardId*/,
                "another_eid" /*eid*/,
                0 /*physicalSlotIndex*/,
                false /*isRemovable*/,
                true /*isMultipleEnabledProfilesSupported*/,
                Collections.singletonList(mUiccPortInfo1));
        cardInfo.setIccIdAccessRestricted(true);

        Parcel parcel = Parcel.obtain();
        cardInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        UiccCardInfo fromParcel = UiccCardInfo.CREATOR.createFromParcel(parcel);

        assertEquals(cardInfo.isEuicc(), fromParcel.isEuicc());
        assertEquals(cardInfo.getCardId(), fromParcel.getCardId());
        assertEquals(cardInfo.getEid(), fromParcel.getEid());
        assertEquals(cardInfo.getPhysicalSlotIndex(), fromParcel.getPhysicalSlotIndex());
        assertEquals(cardInfo.isRemovable(), fromParcel.isRemovable());
        assertEquals(cardInfo.isMultipleEnabledProfilesSupported(),
                fromParcel.isMultipleEnabledProfilesSupported());
        assertEquals(cardInfo.getPorts().size(), fromParcel.getPorts().size());
        // Detailed port comparison
        List<UiccPortInfo> originalPorts = new ArrayList<>(cardInfo.getPorts());
        List<UiccPortInfo> parcelPorts = new ArrayList<>(fromParcel.getPorts());
        assertEquals(originalPorts.get(0).getIccId(), parcelPorts.get(0).getIccId());
        assertEquals(originalPorts.get(0).getPortIndex(), parcelPorts.get(0).getPortIndex());
        assertEquals(originalPorts.get(0).getLogicalSlotIndex(),
                parcelPorts.get(0).getLogicalSlotIndex());
        assertEquals(originalPorts.get(0).isActive(), parcelPorts.get(0).isActive());

        assertThrows(UnsupportedOperationException.class, () -> fromParcel.getIccId());
        parcel.recycle();
    }

    @Test
    @SmallTest
    public void testEqualsAndHashCode() {
        UiccCardInfo cardInfo1 = new UiccCardInfo(
                true, 1, "eid1", 0, true, true, mPortList);
        UiccCardInfo cardInfo2 = new UiccCardInfo(
                true, 1, "eid1", 0, true, true, mPortList);
        UiccCardInfo cardInfo3 = new UiccCardInfo(
                false, 2, "eid2", 1, false, false, Collections.emptyList());

        assertEquals(cardInfo1, cardInfo2);
        assertEquals(cardInfo1.hashCode(), cardInfo2.hashCode());
        assertFalse(cardInfo1.equals(cardInfo3));
        assertFalse(cardInfo1.hashCode()
                == cardInfo3.hashCode());
        assertFalse(cardInfo1.equals(null));
        assertFalse(cardInfo1.equals(new Object()));
    }
}
