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

import android.os.Parcel;
import android.telephony.UiccPortInfo;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.telephony.TelephonyTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
public class UiccPortInfoTest extends TelephonyTest {

    private static final String TEST_ICCID = "12345678901234567890";
    private static final int TEST_PORT_INDEX = 0;
    private static final int TEST_LOGICAL_SLOT_INDEX = 1;
    private static final boolean TEST_IS_ACTIVE = true;

    private UiccPortInfo mUiccPortInfo;

    @Before
    public void setUp() throws Exception {
        super.setUp("UiccPortInfoTest");
        mUiccPortInfo = new UiccPortInfo(TEST_ICCID, TEST_PORT_INDEX, TEST_LOGICAL_SLOT_INDEX,
                TEST_IS_ACTIVE);
    }

    @After
    public void tearDown() throws Exception {
        mUiccPortInfo = null;
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testGetters() {
        assertEquals(TEST_ICCID, mUiccPortInfo.getIccId());
        assertEquals(TEST_PORT_INDEX, mUiccPortInfo.getPortIndex());
        assertEquals(TEST_LOGICAL_SLOT_INDEX, mUiccPortInfo.getLogicalSlotIndex());
        assertEquals(TEST_IS_ACTIVE, mUiccPortInfo.isActive());
    }

    @Test
    @SmallTest
    public void testCreateSensitiveInfoSanitizedCopy() {
        UiccPortInfo sanitizedInfo = mUiccPortInfo.createSensitiveInfoSanitizedCopy();

        UiccPortInfo expectedInfo = new UiccPortInfo(
                UiccPortInfo.ICCID_REDACTED, // iccId
                TEST_PORT_INDEX, // portIndex
                TEST_LOGICAL_SLOT_INDEX, // logicalSlotIndex
                TEST_IS_ACTIVE // isActive
        );
        assertEquals(expectedInfo, sanitizedInfo);
    }

    @Test
    @SmallTest
    public void testParcelable() {
        Parcel parcel = Parcel.obtain();
        mUiccPortInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        UiccPortInfo fromParcel = UiccPortInfo.CREATOR.createFromParcel(parcel);

        assertEquals(mUiccPortInfo.getIccId(), fromParcel.getIccId());
        assertEquals(mUiccPortInfo.getPortIndex(), fromParcel.getPortIndex());
        assertEquals(mUiccPortInfo.getLogicalSlotIndex(), fromParcel.getLogicalSlotIndex());
        assertEquals(mUiccPortInfo.isActive(), fromParcel.isActive());
        assertEquals(mUiccPortInfo, fromParcel);
        parcel.recycle();
    }

    @Test
    @SmallTest
    public void testEqualsAndHashCode() {
        UiccPortInfo portInfo1 = new UiccPortInfo(TEST_ICCID, TEST_PORT_INDEX,
                TEST_LOGICAL_SLOT_INDEX, TEST_IS_ACTIVE);
        UiccPortInfo portInfo2 = new UiccPortInfo(TEST_ICCID, TEST_PORT_INDEX,
                TEST_LOGICAL_SLOT_INDEX, TEST_IS_ACTIVE);
        UiccPortInfo portInfo3 = new UiccPortInfo("09876543210987654321", 1, 0, false);

        assertEquals(portInfo1, portInfo2);
        assertEquals(portInfo1.hashCode(), portInfo2.hashCode());

        assertFalse(portInfo1.equals(portInfo3));

        assertFalse(portInfo1.equals(null));
        assertFalse(portInfo1.equals(new Object()));
    }
}
