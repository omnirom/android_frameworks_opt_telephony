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

package com.android.internal.telephony.satellite.metrics;

import android.annotation.NonNull;
import android.os.SystemClock;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.metrics.SatelliteStats;
import com.android.internal.telephony.satellite.SatelliteConstants;
import com.android.internal.telephony.satellite.SatelliteServiceUtils;
import com.android.internal.telephony.subscription.SubscriptionManagerService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CarrierRoamingSatelliteControllerStats {
    private static final String TAG = CarrierRoamingSatelliteControllerStats.class.getSimpleName();
    private static CarrierRoamingSatelliteControllerStats sInstance = null;
    private static final int ADD_COUNT = 1;
    private SatelliteStats mSatelliteStats;
    /** Map key subId, value: list of session start time in milliseconds */
    private Map<Integer, List<Long>> mSessionStartTimeMap = new HashMap<>();
    /** Map key subId, list of session end time in milliseconds */
    private Map<Integer, List<Long>> mSessionEndTimeMap = new HashMap<>();

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public CarrierRoamingSatelliteControllerStats() {
        mSatelliteStats = SatelliteStats.getInstance();
        resetSessionGapLists();
    }

    /**
     * Returns the Singleton instance of CarrierRoamingSatelliteControllerStats class.
     * If an instance of the Singleton class has not been created,
     * it creates a new instance and returns it. Otherwise, it returns
     * the existing instance.
     * @return the Singleton instance of CarrierRoamingSatelliteControllerStats
     */
    public static CarrierRoamingSatelliteControllerStats getOrCreateInstance() {
        if (sInstance == null) {
            logd("Create new CarrierRoamingSatelliteControllerStats.");
            sInstance = new CarrierRoamingSatelliteControllerStats();
        }
        return sInstance;
    }

    /** Report config data source */
    public void reportConfigDataSource(int subId,
            @SatelliteConstants.ConfigDataSource int configDataSource) {
        mSatelliteStats.onCarrierRoamingSatelliteControllerStatsMetrics(
                new SatelliteStats.CarrierRoamingSatelliteControllerStatsParams.Builder()
                        .setConfigDataSource(configDataSource)
                        .setCarrierId(getCarrierIdFromSubscription(subId))
                        .setIsMultiSim(isMultiSim())
                        .setIsNbIotNtn(SatelliteServiceUtils.isNbIotNtn(subId))
                        .build());
    }

    /** Report count of entitlement status query request */
    public void reportCountOfEntitlementStatusQueryRequest(int subId) {
        mSatelliteStats.onCarrierRoamingSatelliteControllerStatsMetrics(
                new SatelliteStats.CarrierRoamingSatelliteControllerStatsParams.Builder()
                        .setCountOfEntitlementStatusQueryRequest(ADD_COUNT)
                        .setCarrierId(getCarrierIdFromSubscription(subId))
                        .setIsMultiSim(isMultiSim())
                        .setIsNbIotNtn(SatelliteServiceUtils.isNbIotNtn(subId))
                        .build());
    }

    /** Report count of satellite config update request */
    public void reportCountOfSatelliteConfigUpdateRequest() {
        mSatelliteStats.onCarrierRoamingSatelliteControllerStatsMetrics(
                new SatelliteStats.CarrierRoamingSatelliteControllerStatsParams.Builder()
                        .setCountOfSatelliteConfigUpdateRequest(ADD_COUNT)
                        .setIsMultiSim(isMultiSim())
                        .build());
    }

    /** Report count of satellite notification displayed */
    public void reportCountOfSatelliteNotificationDisplayed(int subId) {
        mSatelliteStats.onCarrierRoamingSatelliteControllerStatsMetrics(
                new SatelliteStats.CarrierRoamingSatelliteControllerStatsParams.Builder()
                        .setCountOfSatelliteNotificationDisplayed(ADD_COUNT)
                        .setCarrierId(getCarrierIdFromSubscription(subId))
                        .setIsMultiSim(isMultiSim())
                        .setIsNbIotNtn(SatelliteServiceUtils.isNbIotNtn(subId))
                        .build());
    }

    /** Capture the NB-IoT NTN carrier ID */
    public void reportCarrierId(int carrierId) {
        mSatelliteStats.onCarrierRoamingSatelliteControllerStatsMetrics(
                new SatelliteStats.CarrierRoamingSatelliteControllerStatsParams.Builder()
                        .setCarrierId(carrierId)
                        .setIsMultiSim(isMultiSim())
                        .build());
    }

    /** Capture whether the device is satellite entitled or not */
    public void reportIsDeviceEntitled(int subId, boolean isDeviceEntitled) {
        mSatelliteStats.onCarrierRoamingSatelliteControllerStatsMetrics(
                new SatelliteStats.CarrierRoamingSatelliteControllerStatsParams.Builder()
                        .setIsDeviceEntitled(isDeviceEntitled)
                        .setCarrierId(getCarrierIdFromSubscription(subId))
                        .setIsMultiSim(isMultiSim())
                        .setIsNbIotNtn(SatelliteServiceUtils.isNbIotNtn(subId))
                        .build());
    }

    /** Log carrier roaming satellite session start */
    public void onSessionStart(int subId) {
        List<Long> sessionStartTimeListForSubscription = mSessionStartTimeMap.getOrDefault(subId,
                new ArrayList<>());
        sessionStartTimeListForSubscription.add(getElapsedRealtime());
        mSessionStartTimeMap.put(subId, sessionStartTimeListForSubscription);

        mSatelliteStats.onCarrierRoamingSatelliteControllerStatsMetrics(
                new SatelliteStats.CarrierRoamingSatelliteControllerStatsParams.Builder()
                        .setCarrierId(getCarrierIdFromSubscription(subId))
                        .increaseCountOfSatelliteSessions()
                        .build());
    }

    /** Log carrier roaming satellite session end */
    public void onSessionEnd(int subId) {
        List<Long> sessionEndTimeListForSubscription = mSessionEndTimeMap.getOrDefault(subId,
                new ArrayList<>());
        sessionEndTimeListForSubscription.add(getElapsedRealtime());
        mSessionEndTimeMap.put(subId, sessionEndTimeListForSubscription);

        int numberOfSatelliteSessions = getNumberOfSatelliteSessions(subId);
        List<Integer> sessionGapList = getSatelliteSessionGapList(subId, numberOfSatelliteSessions);
        int satelliteSessionGapMinSec = 0;
        int satelliteSessionGapMaxSec = 0;
        if (!sessionGapList.isEmpty()) {
            satelliteSessionGapMinSec = Collections.min(sessionGapList);
            satelliteSessionGapMaxSec = Collections.max(sessionGapList);
        }

        mSatelliteStats.onCarrierRoamingSatelliteControllerStatsMetrics(
                new SatelliteStats.CarrierRoamingSatelliteControllerStatsParams.Builder()
                        .setSatelliteSessionGapMinSec(satelliteSessionGapMinSec)
                        .setSatelliteSessionGapAvgSec(getAvg(sessionGapList))
                        .setSatelliteSessionGapMaxSec(satelliteSessionGapMaxSec)
                        .setCarrierId(getCarrierIdFromSubscription(subId))
                        .setIsMultiSim(isMultiSim())
                        .setIsNbIotNtn(SatelliteServiceUtils.isNbIotNtn(subId))
                        .build());
    }

    /** Atom is pulled once per day. Reset session gap lists after the atom is pulled. */
    public void resetSessionGapLists() {
        mSessionStartTimeMap = new HashMap<>();
        mSessionEndTimeMap = new HashMap<>();
    }

    private int getNumberOfSatelliteSessions(int subId) {
        return Math.min(mSessionStartTimeMap.getOrDefault(subId, new ArrayList<>()).size(),
                mSessionEndTimeMap.getOrDefault(subId, new ArrayList<>()).size());
    }

    private List<Integer> getSatelliteSessionGapList(int subId, int numberOfSatelliteSessions) {
        if (numberOfSatelliteSessions == 0) {
            return new ArrayList<>();
        }

        List<Long> sessionStartTimeList = mSessionStartTimeMap.getOrDefault(subId,
                new ArrayList<>());
        List<Long> sessionEndTimeList = mSessionEndTimeMap.getOrDefault(subId, new ArrayList<>());
        List<Integer> sessionGapList = new ArrayList<>();
        for (int i = 1; i < numberOfSatelliteSessions; i++) {
            long prevSessionEndTime = sessionEndTimeList.get(i - 1);
            long currentSessionStartTime = sessionStartTimeList.get(i);
            if (currentSessionStartTime > prevSessionEndTime && prevSessionEndTime > 0) {
                sessionGapList.add((int) (
                        (currentSessionStartTime - prevSessionEndTime) / 1000));
            }
        }
        return sessionGapList;
    }

    private int getAvg(@NonNull List<Integer> list) {
        if (list.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (int num : list) {
            total += num;
        }

        return total / list.size();
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected long getElapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean isMultiSim() {
        return SubscriptionManagerService.getInstance().getActiveSubIdList(true).length > 1;
    }

    /** Returns the carrier ID of the given subscription id. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected int getCarrierIdFromSubscription(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        Phone phone = PhoneFactory.getPhone(phoneId);
        return phone != null ? phone.getCarrierId() : TelephonyManager.UNKNOWN_CARRIER_ID;
    }

    private static void logd(@NonNull String log) {
        Log.d(TAG, log);
    }
}
