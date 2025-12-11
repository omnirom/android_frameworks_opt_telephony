/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.internal.telephony.TelephonyTestUtils.waitForMs;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.emergency.EmergencyNumber;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;

import com.android.internal.telephony.PhoneInternalInterface.DialArgs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class GsmCdmaConnectionTest extends TelephonyTest {
    private GsmCdmaConnection connection;

    // Mocked classes
    DriverCall mDC;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        mDC = mock(DriverCall.class);
        replaceInstance(Handler.class, "mLooper", mCT, Looper.myLooper());

        mCT.mForegroundCall = new GsmCdmaCall(mCT);
        mCT.mBackgroundCall = new GsmCdmaCall(mCT);
        mCT.mRingingCall = new GsmCdmaCall(mCT);
    }

    @After
    public void tearDown() throws Exception {
        if (connection != null) {
            connection.dispose();
            connection = null;
        }
        super.tearDown();
    }

    @Test @SmallTest
    public void testOriginalDialString(){
        connection = new GsmCdmaConnection(mPhone, "+8610000", mCT, null,
                new DialArgs.Builder().build());
        assertEquals("+8610000", connection.getOrigDialString());
    }

    @Test @SmallTest
    public void testSanityGSM() {
        connection = new GsmCdmaConnection(mPhone, String.format(
                "+1 (700).555-41NN%c1234", PhoneNumberUtils.PAUSE), mCT, null,
                new DialArgs.Builder().build());
        logd("Testing initial state of GsmCdmaConnection");
        assertEquals(GsmCdmaCall.State.IDLE, connection.getState());
        assertEquals(Connection.PostDialState.NOT_STARTED, connection.getPostDialState());
        assertEquals(DisconnectCause.NOT_DISCONNECTED, DisconnectCause.NOT_DISCONNECTED);
        assertEquals(0, connection.getDisconnectTime());
        assertEquals(0, connection.getHoldDurationMillis());
        assertEquals(PhoneConstants.PRESENTATION_ALLOWED, connection.getNumberPresentation());
        assertFalse(connection.isMultiparty());
        assertNotNull(connection.getRemainingPostDialString());
        assertEquals("+1 (700).555-41NN,1234", connection.getOrigDialString());
    }

    @Test @SmallTest
    public void testConnectionStateUpdate() {
        connection = new GsmCdmaConnection(mPhone, String.format(
                "+1 (700).555-41NN%c1234", PhoneNumberUtils.PAUSE), mCT, null,
                new DialArgs.Builder().build());
        logd("Update the connection state from idle to active");
        mDC.state = DriverCall.State.ACTIVE;
        connection.update(mDC);
        assertEquals(GsmCdmaCall.State.ACTIVE, connection.getState());
        logd("update connection state from active to holding");
        mDC.state = DriverCall.State.HOLDING;
        connection.update(mDC);
        assertEquals(GsmCdmaCall.State.HOLDING, connection.getState());
        // getHoldDurationMillis() calculated using System.currentTimeMillis()
        waitForMs(50);
        processAllMessages();
        assertTrue(connection.getHoldDurationMillis() >= 50);
    }

    @Test @MediumTest
    public void testGSMPostDialPause() {
        connection = new GsmCdmaConnection(mPhone, String.format(
                "+1 (700).555-41NN%c1234", PhoneNumberUtils.PAUSE), mCT, null,
                new DialArgs.Builder().build());
        logd("Mock connection state from alerting to active ");
        mDC.state = DriverCall.State.ALERTING;
        connection.update(mDC);
        mDC.state = DriverCall.State.ACTIVE;
        connection.update(mDC);
        logd("process post dail sequence with pause");
        assertEquals(Connection.PostDialState.STARTED, connection.getPostDialState());
        /* pause for 2000 ms */
        moveTimeForward(GsmCdmaConnection.PAUSE_DELAY_MILLIS_GSM);
        processAllMessages();
        assertEquals(Connection.PostDialState.COMPLETE, connection.getPostDialState());
    }

    @Test @SmallTest
    public void testHangUpConnection() {
        connection = new GsmCdmaConnection(mPhone, String.format(
                "+1 (700).555-41NN%c1234", PhoneNumberUtils.PAUSE), mCT, null,
                new DialArgs.Builder().build());
        mDC.state = DriverCall.State.ACTIVE;
        connection.update(mDC);
        logd("Hangup the connection locally");
        connection.onDisconnect(DisconnectCause.LOCAL);
        assertEquals(GsmCdmaCall.State.DISCONNECTED, connection.getState());
        assertEquals(DisconnectCause.LOCAL, connection.getDisconnectCause());
        assertTrue(connection.getDisconnectTime() <= System.currentTimeMillis());
    }

    @Test @SmallTest
    public void testAddressUpdate() {
        String[] testAddressMappingSet[] = {
                /* {"0:inputAddress", "1:updateAddress", "2:ExpectResult"} */
                {"12345", "12345", "12345"},
                {"12345", "67890", "67890"},
                {"12345*00000", "12345", "12345*00000"},
                {"12345*00000", "67890", "67890"},
                {"12345*00000", "12345*00000", "12345*00000"},
                {"12345;11111*00000", "12345", "12345"},
                {"12345*00000;11111", "12345", "12345*00000"},
                {"18412345*00000", "18412345", "18412345*00000"},
                {"+8112345*00000", "+8112345", "+8112345*00000"}};
        mDC.state = DriverCall.State.ALERTING;
        for (String[] testAddress : testAddressMappingSet) {
            connection = new GsmCdmaConnection(mPhone, testAddress[0], mCT, null,
                    new DialArgs.Builder().build());
            connection.setIsIncoming(true);
            mDC.number = testAddress[1];
            connection.update(mDC);
            assertEquals(testAddress[2], connection.getAddress());
        }
    }

    /**
     * Ensures outgoing calls do not apply address changes.
     */
    @Test @SmallTest
    public void testAddressUpdateOutgoing() {
        mDC.state = DriverCall.State.ALERTING;
        connection = new GsmCdmaConnection(mPhone, "12345", mCT, null,
                new DialArgs.Builder().build());
        connection.setIsIncoming(false);
        mDC.number = "678";
        connection.update(mDC);
        assertEquals("12345", connection.getAddress());
    }

    @Test @SmallTest
    public void testRedirectingAddressUpdate() {
        String forwardedNumber = "11111";

        connection = new GsmCdmaConnection(mPhone, "12345", mCT, null,
                new DialArgs.Builder().build());
        connection.setIsIncoming(true);
        assertEquals(null, connection.getForwardedNumber());
        mDC.state = DriverCall.State.ALERTING;
        mDC.forwardedNumber = forwardedNumber;
        connection.update(mDC);
        assertEquals(new ArrayList<String>(Arrays.asList(forwardedNumber)),
                connection.getForwardedNumber());

        connection = new GsmCdmaConnection(mPhone, mDC, mCT, 0);
        assertEquals(new ArrayList<String>(Arrays.asList(forwardedNumber)),
                connection.getForwardedNumber());
    }

    @Test @SmallTest
    public void testForwardedNumberEmptyNull() {
        mDC.state = DriverCall.State.INCOMING;
        mDC.forwardedNumber = "";
        connection = new GsmCdmaConnection(mPhone, mDC, mCT, 0);
        assertNull(connection.getForwardedNumber());
        mDC.forwardedNumber = null;
        connection.update(mDC);
        assertNull(connection.getForwardedNumber());

        mDC.forwardedNumber = null;
        connection = new GsmCdmaConnection(mPhone, mDC, mCT, 0);
        assertNull(connection.getForwardedNumber());
        mDC.forwardedNumber = "";
        connection.update(mDC);
        assertNull(connection.getForwardedNumber());
    }

    /**
     * Verifies that the mappings for CallFailCause.NO_VALID_SIM,
     * CallFailCause.LOCAL_NETWORK_NO_SERVICE, and CallFailCause.LOCAL_SERVICE_UNAVAILABLE are as
     * expected.
     */
    @Test @SmallTest
    public void testNoSimNoServiceMapping() {
        connection = new GsmCdmaConnection(mPhone, "12345", mCT, null,
                new DialArgs.Builder().build());
        assertEquals(DisconnectCause.ICC_ERROR,
                connection.disconnectCauseFromCode(CallFailCause.NO_VALID_SIM));
        assertEquals(DisconnectCause.OUT_OF_SERVICE,
                connection.disconnectCauseFromCode(CallFailCause.LOCAL_NETWORK_NO_SERVICE));
        assertEquals(DisconnectCause.OUT_OF_SERVICE,
                connection.disconnectCauseFromCode(CallFailCause.LOCAL_SERVICE_UNAVAILABLE));
    }

    @Test
    public void testUpdateEmergencyRouting() {
        Bundle extras = new Bundle();
        extras.putBoolean(PhoneConstants.EXTRA_USE_EMERGENCY_ROUTING, true);

        DialArgs dialArgs = new DialArgs.Builder()
                .setIsEmergency(true)
                .setIntentExtras(extras)
                .build();

        doReturn(true).when(mDomainSelectionResolver).isDomainSelectionSupported();

        connection = new GsmCdmaConnection(mPhone, "911", mCT, null, dialArgs);
        // Not updated when category is unset.
        assertEquals(getTestEmergencyNumber(), connection.getEmergencyNumberInfo());

        extras.putInt(PhoneConstants.EXTRA_EMERGENCY_SERVICE_CATEGORY,
                EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED);

        dialArgs = new DialArgs.Builder()
                .setIsEmergency(true)
                .setIntentExtras(extras)
                .build();

        connection = new GsmCdmaConnection(mPhone, "911", mCT, null, dialArgs);
        // Not updated when category is EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED.
        assertEquals(getTestEmergencyNumber(), connection.getEmergencyNumberInfo());

        extras.putInt(PhoneConstants.EXTRA_EMERGENCY_SERVICE_CATEGORY,
                EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE);

        dialArgs = new DialArgs.Builder()
                .setIsEmergency(true)
                .setIntentExtras(extras)
                .build();

        connection = new GsmCdmaConnection(mPhone, "911", mCT, null, dialArgs);

        EmergencyNumber expectedNumber = new EmergencyNumber("911", "us", "30",
                EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE,
                new ArrayList<String>(), EmergencyNumber.EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING,
                EmergencyNumber.EMERGENCY_CALL_ROUTING_EMERGENCY);

        // Updated when category is not EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED.
        assertNotEquals(getTestEmergencyNumber(), connection.getEmergencyNumberInfo());
        assertEquals(expectedNumber, connection.getEmergencyNumberInfo());
    }
}
