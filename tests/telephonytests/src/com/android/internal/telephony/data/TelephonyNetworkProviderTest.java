/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.net.MatchAllNetworkSpecifier;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.TelephonyNetworkSpecifier;
import android.net.connectivity.android.net.INetworkOfferCallback;
import android.os.Looper;
import android.telephony.Annotation.NetCapability;
import android.telephony.SubscriptionManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.data.PhoneSwitcher.PhoneSwitcherCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class TelephonyNetworkProviderTest extends TelephonyTest {

    private TelephonyNetworkProvider mTelephonyNetworkProvider;

    private PhoneSwitcherCallback mPhoneSwitcherCallback;

    // Mocked classes
    private DataNetworkController mDataNetworkController2;


    /**
     * Set the preferred data phone, which is supposed to take the network request.
     *
     * @param phoneId The phone id
     */
    private void setPreferredDataPhone(int phoneId) {
        doAnswer(invocation -> {
            TelephonyNetworkRequest request = (TelephonyNetworkRequest)
                    invocation.getArguments()[0];
            int id = (int) invocation.getArguments()[1];

            logd("shouldApplyNetworkRequest: request phone id=" + id
                    + ", preferred data phone id=" + phoneId);

            TelephonyNetworkSpecifier specifier = (TelephonyNetworkSpecifier)
                    request.getNetworkSpecifier();
            if (specifier != null) {
                int subId = specifier.getSubscriptionId();
                logd("shouldApplyNetworkRequest: requested on sub " + subId);
                if (subId == 1 && mPhone.getPhoneId() == id) {
                    logd("shouldApplyNetworkRequest: matched phone 0");
                    return true;
                }
                if (subId == 2 && mPhone2.getPhoneId() == id) {
                    logd("shouldApplyNetworkRequest: matched phone 1");
                    return true;
                }
                return false;
            }

            if (request.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)) return true;
            return id == phoneId;
        }).when(mPhoneSwitcher).shouldApplyNetworkRequest(any(TelephonyNetworkRequest.class),
                anyInt());
    }

    /**
     * Create a simple network request with internet capability.
     *
     * @return The network request
     */
    @NonNull
    private NetworkRequest createNetworkRequest() {
        return createNetworkRequestForSub(SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * Create a network request with specified network capabilities.
     *
     * @param caps Network capabilities
     *
     * @return The network request
     */
    @NonNull
    private NetworkRequest createNetworkRequest(@NetCapability int... caps) {
        return createNetworkRequestForSub(SubscriptionManager.INVALID_SUBSCRIPTION_ID, caps);
    }

    /**
     * Create a network request with subscription id specified.
     *
     * @param subId The subscription in for the network request
     *
     * @return The network request
     */
    @NonNull
    private NetworkRequest createNetworkRequestForSub(int subId) {
        return createNetworkRequestForSub(subId, NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * Create the network request.
     *
     * @param subId The subscription id. {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} if no
     * @param caps Network capabilities in the network request need to specify.
     *
     * @return The network request
     */
    @NonNull
    private NetworkRequest createNetworkRequestForSub(int subId, @NetCapability int... caps) {
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        Arrays.stream(caps).boxed().toList().forEach(builder::addCapability);
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            builder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
            builder.setNetworkSpecifier(new TelephonyNetworkSpecifier(subId));
            builder.setSubscriptionIds(Set.of(subId));
        }

        return builder.build();
    }

    /** Clear all invocations from all DataNetworkControllers. */
    private void resetInvocations() {
        clearInvocations(mDataNetworkController);
        clearInvocations(mDataNetworkController2);
    }

    /**
     * Verify the request was sent to the correct phone's DataNetworkController.
     *
     * @param phoneId The id of the phone that the request is supposed to send
     * @param request The network request
     */
    private void verifyRequestSentOnPhone(int phoneId, @NonNull NetworkRequest request) {
        ArgumentCaptor<TelephonyNetworkRequest> requestCaptor =
                ArgumentCaptor.forClass(TelephonyNetworkRequest.class);

        for (Phone phone : PhoneFactory.getPhones()) {
            if (phone.getPhoneId() == phoneId) {
                verify(phone.getDataNetworkController(), times(1)
                        .description("Did not request on phone " + phoneId))
                        .addNetworkRequest(requestCaptor.capture());
                assertThat(requestCaptor.getValue().getNativeNetworkRequest()).isEqualTo(request);
            } else {
                verifyNoRequestSentOnPhone(phone.getPhoneId());
            }
        }
    }

    /**
     * Verify the request was released on the specified phone's DataNetworkController.
     *
     * @param phoneId The id of the phone that the request is supposed to send
     * @param request The network request
     */
    private void verifyRequestReleasedOnPhone(int phoneId, @NonNull NetworkRequest request) {
        ArgumentCaptor<TelephonyNetworkRequest> requestCaptor =
                ArgumentCaptor.forClass(TelephonyNetworkRequest.class);

        for (Phone phone : PhoneFactory.getPhones()) {
            if (phone.getPhoneId() == phoneId) {
                verify(phone.getDataNetworkController(), times(1)
                        .description("Did not remove on phone " + phoneId))
                        .removeNetworkRequest(requestCaptor.capture());
                assertThat(requestCaptor.getValue().getNativeNetworkRequest()).isEqualTo(request);
            } else {
                verifyNoRequestReleasedOnPhone(phone.getPhoneId());
            }
        }
    }

    /**
     * Verify there is no request sent on specified phone.
     *
     * @param phoneId The phone id
     */
    private void verifyNoRequestSentOnPhone(int phoneId) {
        verify(PhoneFactory.getPhone(phoneId).getDataNetworkController(), never()
                .description("Should not request on phone " + phoneId))
                .addNetworkRequest(any(TelephonyNetworkRequest.class));
    }

    /**
     * Verify there is no request released on specified phone.
     *
     * @param phoneId The phone id
     */
    private void verifyNoRequestReleasedOnPhone(int phoneId) {
        verify(PhoneFactory.getPhone(phoneId).getDataNetworkController(), never()
                .description("Should not release on phone " + phoneId))
                .removeNetworkRequest(any(TelephonyNetworkRequest.class));
    }

    @Before
    public void setUp() throws Exception {
        logd("TelephonyNetworkProviderTest +Setup!");
        super.setUp(getClass().getSimpleName());
        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[] {mPhone, mPhone2});

        mDataNetworkController2 = mock(DataNetworkController.class);

        doReturn(0).when(mPhone).getPhoneId();
        doReturn(1).when(mPhone).getSubId();
        doReturn(1).when(mPhone2).getPhoneId();
        doReturn(2).when(mPhone2).getSubId();

        doReturn(mDataNetworkController2).when(mPhone2).getDataNetworkController();

        setPreferredDataPhone(0);

        doAnswer(invocation -> {
            NetworkProvider provider = (NetworkProvider) invocation.getArguments()[0];
            provider.setProviderId(1);
            return 1;
        }).when(mConnectivityManager).registerNetworkProvider(any(NetworkProvider.class));

        mTelephonyNetworkProvider = new TelephonyNetworkProvider(Looper.myLooper(),
                mContext, mFeatureFlags);

        ArgumentCaptor<PhoneSwitcherCallback> callbackCaptor =
                ArgumentCaptor.forClass(PhoneSwitcherCallback.class);
        verify(mPhoneSwitcher).registerCallback(callbackCaptor.capture());
        mPhoneSwitcherCallback = callbackCaptor.getValue();

        logd("TelephonyNetworkProviderTest -Setup!");
    }

    @After
    public void tearDown() throws Exception {
        logd("tearDown");
        super.tearDown();
    }

    @Test
    public void testRegisterProvider() {
        verify(mConnectivityManager).registerNetworkProvider(any(TelephonyNetworkProvider.class));

        ArgumentCaptor<NetworkCapabilities> capsCaptor =
                ArgumentCaptor.forClass(NetworkCapabilities.class);
        verify(mConnectivityManager).offerNetwork(anyInt(), any(NetworkScore.class),
                capsCaptor.capture(), any(INetworkOfferCallback.class));

        NetworkCapabilities caps = capsCaptor.getValue();

        List<Integer> allCaps = new ArrayList<>(
                TelephonyNetworkRequest.getAllSupportedNetworkCapabilities());
        // TODO: Remove the following after NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS
        //   is added into NetworkCapabilities.java
        allCaps.remove(Integer.valueOf(DataUtils.NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS));
        allCaps.forEach((cap) -> assertThat(caps.hasCapability(cap)).isTrue());
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IA)).isTrue();
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMTEL)).isTrue();
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)).isTrue();
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)).isTrue();
        assertThat(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isTrue();
        assertThat(caps.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)).isTrue();

        assertThat(caps.getNetworkSpecifier()).isInstanceOf(MatchAllNetworkSpecifier.class);
    }

    @Test
    public void testRequestNetwork() {
        NetworkRequest request = createNetworkRequest();
        mTelephonyNetworkProvider.onNetworkNeeded(request);
        // Should request on phone 0
        verifyRequestSentOnPhone(0, request);
    }

    @Test
    public void testReleaseNetwork() {
        NetworkRequest request = createNetworkRequest();
        mTelephonyNetworkProvider.onNetworkNeeded(request);
        // Should request on phone 0
        verifyRequestSentOnPhone(0, request);
        resetInvocations();

        // Now release the network request.
        mTelephonyNetworkProvider.onNetworkUnneeded(request);
        // Should release on phone 0
        verifyRequestReleasedOnPhone(0, request);
        resetInvocations();

        // Release the same request again should not result in another remove
        mTelephonyNetworkProvider.onNetworkUnneeded(request);
        verifyNoRequestReleasedOnPhone(0);
        verifyNoRequestReleasedOnPhone(1);
    }

    @Test
    public void testRequestNetworkDuplicate() {
        NetworkRequest request = createNetworkRequest();
        mTelephonyNetworkProvider.onNetworkNeeded(request);
        // Should request on phone 0
        verifyRequestSentOnPhone(0, request);

        resetInvocations();
        // send the same request again should be blocked.
        mTelephonyNetworkProvider.onNetworkNeeded(request);
        verifyNoRequestSentOnPhone(0);
        verifyNoRequestSentOnPhone(1);
    }

    @Test
    public void testRequestNetworkPreferredPhone1() {
        setPreferredDataPhone(1);

        NetworkRequest request = createNetworkRequest();
        mTelephonyNetworkProvider.onNetworkNeeded(request);
        // Should request on phone 1
        verifyRequestSentOnPhone(1, request);
    }

    @Test
    public void testRequestEmergencyNetwork() {
        setPreferredDataPhone(1);

        NetworkRequest request = createNetworkRequest(NetworkCapabilities.NET_CAPABILITY_EIMS);
        mTelephonyNetworkProvider.onNetworkNeeded(request);
        // Should request on phone 0
        verifyRequestSentOnPhone(0, request);
    }

    @Test
    public void testRequestNetworkOnSpecifiedSub() {
        NetworkRequest request = createNetworkRequestForSub(1);
        mTelephonyNetworkProvider.onNetworkNeeded(request);
        verifyRequestSentOnPhone(0, request);

        resetInvocations();
        request = createNetworkRequestForSub(2);
        mTelephonyNetworkProvider.onNetworkNeeded(request);
        // Should request on phone 1
        verifyRequestSentOnPhone(1, request);
    }

    @Test
    public void testPreferredDataSwitch() {
        NetworkRequest request = createNetworkRequest();
        mTelephonyNetworkProvider.onNetworkNeeded(request);
        // Should request on phone 0
        verifyRequestSentOnPhone(0, request);
        resetInvocations();

        // Now switch from phone 0 to phone 1
        setPreferredDataPhone(1);
        mPhoneSwitcherCallback.onPreferredDataPhoneIdChanged(1);
        verifyRequestReleasedOnPhone(0, request);
        verifyRequestSentOnPhone(1, request);
        resetInvocations();

        // Now switch back to phone 0
        setPreferredDataPhone(0);
        mPhoneSwitcherCallback.onPreferredDataPhoneIdChanged(0);
        verifyRequestReleasedOnPhone(1, request);
        verifyRequestSentOnPhone(0, request);
    }

    @Test
    public void testMakeNetworkFilter() {
        doReturn(Set.of(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH,
                NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY,
                NetworkCapabilities.NET_CAPABILITY_VSIM, NetworkCapabilities.NET_CAPABILITY_MMS,
                NetworkCapabilities.NET_CAPABILITY_XCAP)).when(mDataConfigManager)
                .getUnsupportedNetworkCapabilities();

        NetworkCapabilities caps = mTelephonyNetworkProvider.makeNetworkFilter();
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isTrue();
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOTA)).isTrue();
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IA)).isTrue();
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH))
                .isFalse();
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY))
                .isFalse();
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VSIM)).isFalse();
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMS)).isFalse();
        assertThat(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_XCAP)).isFalse();
    }
}
