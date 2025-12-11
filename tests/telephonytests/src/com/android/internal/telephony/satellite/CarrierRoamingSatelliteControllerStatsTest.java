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

package com.android.internal.telephony.satellite;

import static android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID;

import static com.android.internal.telephony.satellite.SatelliteConstants.CONFIG_DATA_SOURCE_CONFIG_UPDATER;
import static com.android.internal.telephony.satellite.SatelliteConstants.CONFIG_DATA_SOURCE_ENTITLEMENT;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.metrics.SatelliteStats;
import com.android.internal.telephony.satellite.metrics.CarrierRoamingSatelliteControllerStats;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import com.android.telephony.Rlog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class CarrierRoamingSatelliteControllerStatsTest extends TelephonyTest {
    private static final String TAG = "CarrierRoamingSatelliteControllerStatsTest";
    private static final int TEST_SUB_ID_0 = 0;
    private static final int TEST_SUB_ID_1 = 1;
    private static final int TEST_CARRIER_ID_0 = 1000;
    private static final int TEST_CARRIER_ID_1 = 1111;
    private static final long SESSION_TIME = 100L;
    private static final long SESSION_GAP_1 = 1000000L;
    private static final long SESSION_GAP_2 = 2000000L;
    private static final long SESSION_GAP_3 = 4000000L;

    private TestCarrierRoamingSatelliteControllerStats mTestCarrierRoamingSatelliteControllerStats;
    @Mock
    private SatelliteStats mMockSatelliteStats;
    @Mock
    private SubscriptionManagerService mMockSubscriptionManagerService;
    @Mock
    private SatelliteController mMockSatellitecontroller;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);
        logd(TAG + " Setup!");
        BackupAndRestoreCarrierRoamContParam.backUpStaticParams();
        replaceInstance(SatelliteStats.class, "sInstance", null, mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats =
                new TestCarrierRoamingSatelliteControllerStats();
        replaceInstance(SubscriptionManagerService.class, "sInstance", null,
                mMockSubscriptionManagerService);
        replaceInstance(SatelliteController.class, "sInstance", null, mMockSatellitecontroller);
    }

    @After
    public void tearDown() throws Exception {
        Rlog.d(TAG, "tearDown()");
        BackupAndRestoreCarrierRoamContParam.restoreStaticParams();
        super.tearDown();
    }

    @Test
    public void testReportConfigDataSource() {
        final ExpectedCarrierRoamingSatelliteControllerStatsParam expected =
                new ExpectedCarrierRoamingSatelliteControllerStatsParam();
        doReturn(new int[]{TEST_SUB_ID_0}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());
        doReturn(false).when(mMockSatellitecontroller).isInCarrierRoamingNbIotNtn(any());

        initializeStaticParams();
        expected.initializeParams();
        expected.setConfigDataSource(CONFIG_DATA_SOURCE_ENTITLEMENT);
        expected.setCarrierId(TEST_CARRIER_ID_0);
        expected.setIsMultiSim(false);
        expected.setIsNbIotNtn(false);
        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportConfigDataSource(TEST_SUB_ID_0,
                CONFIG_DATA_SOURCE_ENTITLEMENT);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        doReturn(new int[]{TEST_SUB_ID_0, TEST_SUB_ID_1}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());
        doReturn(true).when(mMockSatellitecontroller).isInCarrierRoamingNbIotNtn(any());

        initializeStaticParams();
        expected.initializeParams();
        expected.setConfigDataSource(CONFIG_DATA_SOURCE_CONFIG_UPDATER);
        expected.setCarrierId(TEST_CARRIER_ID_1);
        expected.setIsMultiSim(true);
        expected.setIsNbIotNtn(true);
        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportConfigDataSource(TEST_SUB_ID_1,
                CONFIG_DATA_SOURCE_CONFIG_UPDATER);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));
    }

    @Test
    public void testReportCountOfEntitlementStatusQueryRequest() {
        final ExpectedCarrierRoamingSatelliteControllerStatsParam expected =
                new ExpectedCarrierRoamingSatelliteControllerStatsParam();
        doReturn(new int[]{TEST_SUB_ID_0}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());
        doReturn(false).when(mMockSatellitecontroller).isInCarrierRoamingNbIotNtn(any());

        initializeStaticParams();
        expected.initializeParams();
        expected.setCountOfEntitlementStatusQueryRequest(1);
        expected.setCarrierId(TEST_CARRIER_ID_0);
        expected.setIsMultiSim(false);
        expected.setIsNbIotNtn(false);
        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportCountOfEntitlementStatusQueryRequest(
                TEST_SUB_ID_0);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        doReturn(new int[]{TEST_SUB_ID_0, TEST_SUB_ID_1}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());
        doReturn(true).when(mMockSatellitecontroller).isInCarrierRoamingNbIotNtn(any());

        initializeStaticParams();
        expected.initializeParams();
        expected.setCountOfEntitlementStatusQueryRequest(1);
        expected.setCarrierId(TEST_CARRIER_ID_1);
        expected.setIsMultiSim(true);
        expected.setIsNbIotNtn(true);
        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportCountOfEntitlementStatusQueryRequest(
                TEST_SUB_ID_1);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));
    }

    @Test
    public void testReportCountOfSatelliteConfigUpdateRequest() {
        final ExpectedCarrierRoamingSatelliteControllerStatsParam expected =
                new ExpectedCarrierRoamingSatelliteControllerStatsParam();
        doReturn(new int[]{TEST_SUB_ID_0}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());

        initializeStaticParams();
        expected.initializeParams();
        expected.setCountOfSatelliteConfigUpdateRequest(1);
        expected.setCarrierId(UNKNOWN_CARRIER_ID);
        expected.setIsMultiSim(false);

        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportCountOfSatelliteConfigUpdateRequest();
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        doReturn(new int[]{TEST_SUB_ID_0, TEST_SUB_ID_1}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());

        initializeStaticParams();
        expected.initializeParams();
        expected.setCountOfSatelliteConfigUpdateRequest(1);
        expected.setCarrierId(UNKNOWN_CARRIER_ID);
        expected.setIsMultiSim(true);

        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportCountOfSatelliteConfigUpdateRequest();
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));
    }

    @Test
    public void testReportCountOfSatelliteNotificationDisplayed() {
        final ExpectedCarrierRoamingSatelliteControllerStatsParam expected =
                new ExpectedCarrierRoamingSatelliteControllerStatsParam();
        doReturn(new int[]{TEST_SUB_ID_0}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());
        doReturn(false).when(mMockSatellitecontroller).isInCarrierRoamingNbIotNtn(any());

        initializeStaticParams();
        expected.initializeParams();
        expected.setCountOfSatelliteNotificationDisplayed(1);
        expected.setCarrierId(TEST_CARRIER_ID_0);
        expected.setIsMultiSim(false);
        expected.setIsNbIotNtn(false);

        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportCountOfSatelliteNotificationDisplayed(
                TEST_SUB_ID_0);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        doReturn(new int[]{TEST_SUB_ID_0, TEST_SUB_ID_1}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());
        doReturn(true).when(mMockSatellitecontroller).isInCarrierRoamingNbIotNtn(any());

        initializeStaticParams();
        expected.initializeParams();
        expected.setCountOfSatelliteNotificationDisplayed(1);
        expected.setCarrierId(TEST_CARRIER_ID_1);
        expected.setIsMultiSim(true);
        expected.setIsNbIotNtn(true);
        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportCountOfSatelliteNotificationDisplayed(
                TEST_SUB_ID_1);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));
    }

    @Test
    public void testReportCarrierId() {
        final ExpectedCarrierRoamingSatelliteControllerStatsParam expected =
                new ExpectedCarrierRoamingSatelliteControllerStatsParam();
        doReturn(new int[]{TEST_SUB_ID_0}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());

        initializeStaticParams();
        expected.initializeParams();
        expected.setCarrierId(TEST_CARRIER_ID_0);
        expected.setIsMultiSim(false);

        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportCarrierId(TEST_CARRIER_ID_0,
                SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        doReturn(new int[]{TEST_SUB_ID_0, TEST_SUB_ID_1}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());

        initializeStaticParams();
        expected.initializeParams();
        expected.setCarrierId(TEST_CARRIER_ID_1);
        expected.setIsMultiSim(true);
        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportCarrierId(TEST_CARRIER_ID_1,
                SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));
    }

    @Test
    public void testReportIsDeviceEntitled() {
        final ExpectedCarrierRoamingSatelliteControllerStatsParam expected =
                new ExpectedCarrierRoamingSatelliteControllerStatsParam();
        doReturn(new int[]{TEST_SUB_ID_0}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());
        doReturn(false).when(mMockSatellitecontroller).isInCarrierRoamingNbIotNtn(any());

        initializeStaticParams();
        expected.initializeParams();
        expected.setIsDeviceEntitled(true);
        expected.setCarrierId(TEST_CARRIER_ID_0);
        expected.setIsMultiSim(false);
        expected.setIsNbIotNtn(false);

        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportIsDeviceEntitled(TEST_SUB_ID_0, true);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        doReturn(new int[]{TEST_SUB_ID_0, TEST_SUB_ID_1}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());
        doReturn(true).when(mMockSatellitecontroller).isInCarrierRoamingNbIotNtn(any());

        initializeStaticParams();
        expected.initializeParams();
        expected.setIsDeviceEntitled(false);
        expected.setCarrierId(TEST_CARRIER_ID_1);
        expected.setIsMultiSim(true);
        expected.setIsNbIotNtn(true);
        clearInvocations(mMockSatelliteStats);
        mTestCarrierRoamingSatelliteControllerStats.reportIsDeviceEntitled(TEST_SUB_ID_1, false);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));
    }

    @Test
    public void testSatelliteSessionGaps() {
        final ExpectedCarrierRoamingSatelliteControllerStatsParam expected =
                new ExpectedCarrierRoamingSatelliteControllerStatsParam();
        doReturn(new int[]{TEST_SUB_ID_0}).when(
                mMockSubscriptionManagerService).getActiveSubIdList(anyBoolean());
        doReturn(false).when(mMockSatellitecontroller).isInCarrierRoamingNbIotNtn(any());

        initializeStaticParams();
        expected.initializeParams();
        expected.setCarrierId(TEST_CARRIER_ID_0);
        expected.setIsMultiSim(false);
        expected.setIsNbIotNtn(false);
        clearInvocations(mMockSatelliteStats);
        // first satellite session starts
        mTestCarrierRoamingSatelliteControllerStats.setCurrentTime(0L);
        // session counter is increased when session starts
        expected.setCountOfSatelliteSessions(1);
        mTestCarrierRoamingSatelliteControllerStats.onSessionStart(TEST_SUB_ID_0);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        clearInvocations(mMockSatelliteStats);
        // first satellite session ends
        mTestCarrierRoamingSatelliteControllerStats.increaseCurrentTime(SESSION_TIME);
        mTestCarrierRoamingSatelliteControllerStats.onSessionEnd(TEST_SUB_ID_0);

        // session gaps would be 0
        expected.setSatelliteSessionGapMinSec(0);
        expected.setSatelliteSessionGapAvgSec(0);
        expected.setSatelliteSessionGapMaxSec(0);
        // session counter is not reported when session ends
        expected.setCountOfSatelliteSessions(0);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        clearInvocations(mMockSatelliteStats);
        // second session starts, gap between 1st and 2nd session is 1000sec
        mTestCarrierRoamingSatelliteControllerStats.increaseCurrentTime(SESSION_GAP_1);
        expected.setCountOfSatelliteSessions(1);
        mTestCarrierRoamingSatelliteControllerStats.onSessionStart(TEST_SUB_ID_0);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        clearInvocations(mMockSatelliteStats);
        // second session end
        mTestCarrierRoamingSatelliteControllerStats.increaseCurrentTime(SESSION_TIME);
        mTestCarrierRoamingSatelliteControllerStats.onSessionEnd(TEST_SUB_ID_0);

        // session gap min / avg / max would be 1000 each
        expected.setSatelliteSessionGapMinSec(1000);
        expected.setSatelliteSessionGapAvgSec(1000);
        expected.setSatelliteSessionGapMaxSec(1000);
        expected.setCountOfSatelliteSessions(0);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        clearInvocations(mMockSatelliteStats);
        // 3rd session starts, gap between 2nd and 3rd session is 2000
        mTestCarrierRoamingSatelliteControllerStats.increaseCurrentTime(SESSION_GAP_2);
        expected.setCountOfSatelliteSessions(1);
        mTestCarrierRoamingSatelliteControllerStats.onSessionStart(TEST_SUB_ID_0);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        clearInvocations(mMockSatelliteStats);
        // 3rd session end
        mTestCarrierRoamingSatelliteControllerStats.increaseCurrentTime(SESSION_TIME);
        mTestCarrierRoamingSatelliteControllerStats.onSessionEnd(TEST_SUB_ID_0);

        // session gap min would be 1000
        expected.setSatelliteSessionGapMinSec(1000);
        // session gap avg would be 1500
        int avgGapSec = (int) ((SESSION_GAP_1 + SESSION_GAP_2) / (2 * 1000));
        expected.setSatelliteSessionGapAvgSec(avgGapSec);
        // session gap max would be 2000
        expected.setSatelliteSessionGapMaxSec(2000);
        expected.setCountOfSatelliteSessions(0);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        clearInvocations(mMockSatelliteStats);
        // 4th session starts, gap between 3rd and 4th session is 4000
        mTestCarrierRoamingSatelliteControllerStats.increaseCurrentTime(SESSION_GAP_3);
        expected.setCountOfSatelliteSessions(1);
        mTestCarrierRoamingSatelliteControllerStats.onSessionStart(TEST_SUB_ID_0);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));

        clearInvocations(mMockSatelliteStats);
        // 4th session end
        mTestCarrierRoamingSatelliteControllerStats.increaseCurrentTime(SESSION_TIME);
        mTestCarrierRoamingSatelliteControllerStats.onSessionEnd(TEST_SUB_ID_0);

        // session gap min would be 1000
        expected.setSatelliteSessionGapMinSec(1000);
        // session gap avg would be 2333
        avgGapSec = (int) ((SESSION_GAP_1 + SESSION_GAP_2 + SESSION_GAP_3) / (3 * 1000));
        expected.setSatelliteSessionGapAvgSec(avgGapSec);
        // session gap max would be 4000
        expected.setSatelliteSessionGapMaxSec(4000);
        expected.setCountOfSatelliteSessions(0);
        verify(mMockSatelliteStats, times(1)).onCarrierRoamingSatelliteControllerStatsMetrics(
                ArgumentMatchers.argThat(argument -> verifyAssets(expected, argument)));
    }

    private static class BackupAndRestoreCarrierRoamContParam {
        private static int sSatelliteSessionGapMinSec;
        private static int sSatelliteSessionGapAvgSec;
        private static int sSatelliteSessionGapMaxSec;
        private static int sCarrierId;
        private static boolean sIsDeviceEntitled;
        private static boolean sIsMultiSim;
        private static boolean sIsNbIotNtn;

        public static void backUpStaticParams() {
            SatelliteStats.CarrierRoamingSatelliteControllerStatsParams param =
                    new SatelliteStats.CarrierRoamingSatelliteControllerStatsParams.Builder()
                            .build();
            sSatelliteSessionGapMinSec = param.getSatelliteSessionGapMinSec();
            sSatelliteSessionGapAvgSec = param.getSatelliteSessionGapAvgSec();
            sSatelliteSessionGapMaxSec = param.getSatelliteSessionGapMaxSec();
            sCarrierId = param.getCarrierId();
            sIsDeviceEntitled = param.isDeviceEntitled();
            sIsMultiSim = param.isMultiSim();
            sIsNbIotNtn = param.isNbIotNtn();
        }

        public static void restoreStaticParams() {
            SatelliteStats.getInstance().onCarrierRoamingSatelliteControllerStatsMetrics(
                    new SatelliteStats.CarrierRoamingSatelliteControllerStatsParams.Builder()
                            .setSatelliteSessionGapMinSec(sSatelliteSessionGapMinSec)
                            .setSatelliteSessionGapAvgSec(sSatelliteSessionGapAvgSec)
                            .setSatelliteSessionGapMaxSec(sSatelliteSessionGapMaxSec)
                            .setCarrierId(sCarrierId)
                            .setIsDeviceEntitled(sIsDeviceEntitled)
                            .setIsMultiSim(sIsMultiSim)
                            .setIsNbIotNtn(sIsNbIotNtn)
                            .build());
        }
    }

    private void initializeStaticParams() {
        SatelliteStats.getInstance().onCarrierRoamingSatelliteControllerStatsMetrics(
                new SatelliteStats.CarrierRoamingSatelliteControllerStatsParams.Builder()
                        .setSatelliteSessionGapMinSec(0)
                        .setSatelliteSessionGapAvgSec(0)
                        .setSatelliteSessionGapMaxSec(0)
                        .setCarrierId(UNKNOWN_CARRIER_ID)
                        .setIsDeviceEntitled(false)
                        .setIsMultiSim(false)
                        .setIsNbIotNtn(false)
                        .build());
    }

    private boolean verifyAssets(ExpectedCarrierRoamingSatelliteControllerStatsParam expected,
            SatelliteStats.CarrierRoamingSatelliteControllerStatsParams actual) {
        assertEquals(expected.getConfigDataSource(), actual.getConfigDataSource());
        assertEquals(expected.getCountOfEntitlementStatusQueryRequest(),
                actual.getCountOfEntitlementStatusQueryRequest());
        assertEquals(expected.getCountOfSatelliteConfigUpdateRequest(),
                actual.getCountOfSatelliteConfigUpdateRequest());
        assertEquals(expected.getCountOfSatelliteNotificationDisplayed(),
                actual.getCountOfSatelliteNotificationDisplayed());
        assertEquals(expected.getSatelliteSessionGapMinSec(),
                actual.getSatelliteSessionGapMinSec());
        assertEquals(expected.getSatelliteSessionGapAvgSec(),
                actual.getSatelliteSessionGapAvgSec());
        assertEquals(expected.getSatelliteSessionGapMaxSec(),
                actual.getSatelliteSessionGapMaxSec());
        assertEquals(expected.getCarrierId(), actual.getCarrierId());
        assertEquals(expected.isDeviceEntitled(), actual.isDeviceEntitled());
        assertEquals(expected.isMultiSim(), actual.isMultiSim());
        assertEquals(expected.getCountOfSatelliteSessions(), actual.getCountOfSatelliteSessions());
        assertEquals(expected.isNbIotNtn(), actual.isNbIotNtn());
        return true;
    }

    private static class ExpectedCarrierRoamingSatelliteControllerStatsParam {
        private int mConfigDataSource;
        private int mCountOfEntitlementStatusQueryRequest;
        private int mCountOfSatelliteConfigUpdateRequest;
        private int mCountOfSatelliteNotificationDisplayed;
        private int mSatelliteSessionGapMinSec;
        private int mSatelliteSessionGapAvgSec;
        private int mSatelliteSessionGapMaxSec;
        private int mCarrierId;
        private boolean mIsDeviceEntitled;
        private boolean mIsMultiSim;
        private int mCountOfSatelliteSessions;
        private boolean mIsNbIotNtn;

        public int getConfigDataSource() {
            return mConfigDataSource;
        }

        public int getCountOfEntitlementStatusQueryRequest() {
            return mCountOfEntitlementStatusQueryRequest;
        }

        public int getCountOfSatelliteConfigUpdateRequest() {
            return mCountOfSatelliteConfigUpdateRequest;
        }

        public int getCountOfSatelliteNotificationDisplayed() {
            return mCountOfSatelliteNotificationDisplayed;
        }

        public int getSatelliteSessionGapMinSec() {
            return mSatelliteSessionGapMinSec;
        }

        public int getSatelliteSessionGapAvgSec() {
            return mSatelliteSessionGapAvgSec;
        }

        public int getSatelliteSessionGapMaxSec() {
            return mSatelliteSessionGapMaxSec;
        }

        public int getCarrierId() {
            return mCarrierId;
        }

        public boolean isDeviceEntitled() {
            return mIsDeviceEntitled;
        }

        public boolean isMultiSim() {
            return mIsMultiSim;
        }

        public int getCountOfSatelliteSessions() {
            return mCountOfSatelliteSessions;
        }

        public boolean isNbIotNtn() {
            return mIsNbIotNtn;
        }

        public void setConfigDataSource(int configDataSource) {
            mConfigDataSource = configDataSource;
        }

        public void setCountOfEntitlementStatusQueryRequest(
                int countOfEntitlementStatusQueryRequest) {
            mCountOfEntitlementStatusQueryRequest = countOfEntitlementStatusQueryRequest;
        }

        public void setCountOfSatelliteConfigUpdateRequest(
                int countOfSatelliteConfigUpdateRequest) {
            mCountOfSatelliteConfigUpdateRequest = countOfSatelliteConfigUpdateRequest;
        }

        public void setCountOfSatelliteNotificationDisplayed(
                int countOfSatelliteNotificationDisplayed) {
            mCountOfSatelliteNotificationDisplayed = countOfSatelliteNotificationDisplayed;
        }

        public void setSatelliteSessionGapMinSec(int satelliteSessionGapMinSec) {
            mSatelliteSessionGapMinSec = satelliteSessionGapMinSec;
        }

        public void setSatelliteSessionGapAvgSec(int satelliteSessionGapAvgSec) {
            mSatelliteSessionGapAvgSec = satelliteSessionGapAvgSec;
        }

        public void setSatelliteSessionGapMaxSec(int satelliteSessionGapMaxSec) {
            mSatelliteSessionGapMaxSec = satelliteSessionGapMaxSec;
        }

        public void setCarrierId(int carrierId) {
            mCarrierId = carrierId;
        }

        public void setIsDeviceEntitled(boolean isDeviceEntitled) {
            mIsDeviceEntitled = isDeviceEntitled;
        }

        public void setIsMultiSim(boolean isMultiSim) {
            mIsMultiSim = isMultiSim;
        }

        public void setCountOfSatelliteSessions(int countOfSatelliteSessions) {
            mCountOfSatelliteSessions = countOfSatelliteSessions;
        }

        public void setIsNbIotNtn(boolean isNbIotNtn) {
            mIsNbIotNtn = isNbIotNtn;
        }

        public void initializeParams() {
            mConfigDataSource = SatelliteConstants.CONFIG_DATA_SOURCE_UNKNOWN;
            mCountOfEntitlementStatusQueryRequest = 0;
            mCountOfSatelliteConfigUpdateRequest = 0;
            mCountOfSatelliteNotificationDisplayed = 0;
            mSatelliteSessionGapMinSec = 0;
            mSatelliteSessionGapAvgSec = 0;
            mSatelliteSessionGapMaxSec = 0;
            mCarrierId = UNKNOWN_CARRIER_ID;
            mIsDeviceEntitled = false;
            mIsMultiSim = false;
            mCountOfSatelliteSessions = 0;
            mIsNbIotNtn = false;
        }
    }

    static class TestCarrierRoamingSatelliteControllerStats extends
            CarrierRoamingSatelliteControllerStats {
        private long mCurrentTime;
        TestCarrierRoamingSatelliteControllerStats() {
            super();
            logd("constructing TestCarrierRoamingSatelliteControllerStats");
        }

        @Override
        public int getCarrierIdFromSubscription(int subId) {
            logd("getCarrierIdFromSubscription()");
            if (subId == TEST_SUB_ID_0) {
                return TEST_CARRIER_ID_0;
            } else if (subId == TEST_SUB_ID_1) {
                return TEST_CARRIER_ID_1;
            } else {
                return UNKNOWN_CARRIER_ID;
            }
        }

        @Override
        public long getElapsedRealtime() {
            return mCurrentTime;
        }

        public void setCurrentTime(long currentTime) {
            mCurrentTime = currentTime;
        }

        public void increaseCurrentTime(long incTime) {
            mCurrentTime += incTime;
        }
    }
}
