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
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.NetworkTemplate;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.metrics.SatelliteStats;
import com.android.internal.telephony.satellite.SatelliteConstants;
import com.android.internal.telephony.satellite.SatelliteServiceUtils;
import com.android.internal.telephony.subscription.SubscriptionInfoInternal;
import com.android.internal.telephony.subscription.SubscriptionManagerService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

public class CarrierRoamingSatelliteSessionStats {
    private static final String TAG = CarrierRoamingSatelliteSessionStats.class.getSimpleName();
    private static final SparseArray<CarrierRoamingSatelliteSessionStats>
            sCarrierRoamingSatelliteSessionStats = new SparseArray<>();
    @NonNull private final SubscriptionManagerService mSubscriptionManagerService;
    private int mCarrierId;
    private boolean mIsNtnRoamingInHomeCountry;
    private int mCountOfIncomingSms;
    private int mCountOfOutgoingSms;
    private int mCountOfIncomingMms;
    private int mCountOfOutgoingMms;
    private long mIncomingMessageId;
    private int mSessionStartTimeSec;
    private SatelliteConnectionTimes mSatelliteConnectionTimes;
    private List<SatelliteConnectionTimes> mSatelliteConnectionTimesList;
    private List<Integer> mRsrpList;
    private List<Integer> mRssnrList;
    private int[] mSupportedSatelliteServices;
    private int mServiceDataPolicy;
    private Phone mPhone;
    private Context mContext;
    private long mSatelliteDataConsumedBytes = 0L;
    private long mDataUsageOnSessionStartBytes = 0L;

    public CarrierRoamingSatelliteSessionStats(int subId) {
        logd("Create new CarrierRoamingSatelliteSessionStats. subId=" + subId);
        initializeParams();
        mSubscriptionManagerService = SubscriptionManagerService.getInstance();
    }

    /** Gets a CarrierRoamingSatelliteSessionStats instance. */
    public static CarrierRoamingSatelliteSessionStats getInstance(int subId) {
        synchronized (sCarrierRoamingSatelliteSessionStats) {
            if (sCarrierRoamingSatelliteSessionStats.get(subId) == null) {
                sCarrierRoamingSatelliteSessionStats.put(subId,
                        new CarrierRoamingSatelliteSessionStats(subId));
            }
            return sCarrierRoamingSatelliteSessionStats.get(subId);
        }
    }

    /** Log carrier roaming satellite session start */
    public void onSessionStart(int carrierId, Phone phone, int[] supportedServices,
            int serviceDataPolicy) {
        mPhone = phone;
        mContext = mPhone.getContext();
        mCarrierId = carrierId;
        mSupportedSatelliteServices = supportedServices;
        mServiceDataPolicy = serviceDataPolicy;
        mSessionStartTimeSec = getElapsedRealtimeInSec();
        mIsNtnRoamingInHomeCountry = false;
        onConnectionStart(mPhone);
        mDataUsageOnSessionStartBytes = getDataUsage();
        logd("current data consumed: " + mDataUsageOnSessionStartBytes);
    }

    /** Log carrier roaming satellite connection start */
    public void onConnectionStart(Phone phone) {
        mSatelliteConnectionTimes = new SatelliteConnectionTimes(getElapsedRealtime());
        updateNtnRoamingInHomeCountry(phone);
    }

    /** calculate total satellite data consumed at the session */
    private long getDataUsage() {
        if (mContext == null) {
            return 0L;
        }

        NetworkStatsManager networkStatsManager =
                mContext.getSystemService(NetworkStatsManager.class);

        if (networkStatsManager != null) {
            final NetworkTemplate.Builder builder =
                    new NetworkTemplate.Builder(NetworkTemplate.MATCH_MOBILE);
            final String subscriberId = mPhone.getSubscriberId();
            logd("subscriber id for data consumed:" + subscriberId);

            if (!TextUtils.isEmpty(subscriberId)) {
                builder.setSubscriberIds(Set.of(subscriberId));
                // Consider data usage calculation of only metered capabilities / data network
                builder.setMeteredness(android.net.NetworkStats.METERED_YES);
                NetworkTemplate template = builder.build();
                final NetworkStats.Bucket ret = networkStatsManager
                        .querySummaryForDevice(template, 0L, System.currentTimeMillis());
                return ret.getRxBytes() + ret.getTxBytes();
            }
        }
        return 0L;
    }

    /** Log carrier roaming satellite session end */
    public void onSessionEnd(int subId) {
        onConnectionEnd();
        long dataUsageOnSessionEndBytes = getDataUsage();
        logd("update data consumed: " + dataUsageOnSessionEndBytes);
        if (dataUsageOnSessionEndBytes > 0L
                && dataUsageOnSessionEndBytes > mDataUsageOnSessionStartBytes) {
            mSatelliteDataConsumedBytes =
                    dataUsageOnSessionEndBytes - mDataUsageOnSessionStartBytes;
        }
        logd("satellite data consumed at session: " + mSatelliteDataConsumedBytes);
        reportMetrics(subId);
        mIsNtnRoamingInHomeCountry = false;
        mSupportedSatelliteServices = new int[0];
        mServiceDataPolicy = SatelliteConstants.SATELLITE_ENTITLEMENT_SERVICE_POLICY_UNKNOWN;
        mSatelliteDataConsumedBytes = 0L;
        mDataUsageOnSessionStartBytes = 0L;
    }

    /** Log carrier roaming satellite connection end */
    public void onConnectionEnd() {
        if (mSatelliteConnectionTimes != null) {
            mSatelliteConnectionTimes.setEndTime(getElapsedRealtime());
            mSatelliteConnectionTimesList.add(mSatelliteConnectionTimes);
            mSatelliteConnectionTimes = null;
        } else {
            loge("onConnectionEnd: mSatelliteConnectionTimes is null");
        }
    }

    /** Log rsrp and rssnr when occurred the service state change with NTN is connected. */
    public void onSignalStrength(Phone phone) {
        CellSignalStrengthLte cellSignalStrengthLte = getCellSignalStrengthLte(phone);
        int rsrp = cellSignalStrengthLte.getRsrp();
        int rssnr = cellSignalStrengthLte.getRssnr();
        if (rsrp == CellInfo.UNAVAILABLE) {
            logd("onSignalStrength: rsrp unavailable");
            return;
        }
        if (rssnr == CellInfo.UNAVAILABLE) {
            logd("onSignalStrength: rssnr unavailable");
            return;
        }
        mRsrpList.add(rsrp);
        mRssnrList.add(rssnr);
        logd("onSignalStrength : rsrp=" + rsrp + ", rssnr=" + rssnr);
    }

    /** Log incoming sms success case */
    public void onIncomingSms(int subId) {
        if (!isNtnConnected()) {
            return;
        }
        mCountOfIncomingSms += 1;
        logd("onIncomingSms: subId=" + subId + ", count=" + mCountOfIncomingSms);
    }

    /** Log outgoing sms success case */
    public void onOutgoingSms(int subId) {
        if (!isNtnConnected()) {
            return;
        }
        mCountOfOutgoingSms += 1;
        logd("onOutgoingSms: subId=" + subId + ", count=" + mCountOfOutgoingSms);
    }

    /** Log incoming or outgoing mms success case */
    public void onMms(boolean isIncomingMms, long messageId) {
        if (!isNtnConnected()) {
            return;
        }
        if (isIncomingMms) {
            mIncomingMessageId = messageId;
            mCountOfIncomingMms += 1;
            logd("onMms: messageId=" + messageId + ", countOfIncomingMms=" + mCountOfIncomingMms);
        } else {
            if (mIncomingMessageId == messageId) {
                logd("onMms: NotifyResponse ignore it.");
                mIncomingMessageId = 0;
                return;
            }
            mCountOfOutgoingMms += 1;
            logd("onMms: countOfOutgoingMms=" + mCountOfOutgoingMms);
        }
    }

    private void reportMetrics(int subId) {
        int totalSatelliteModeTimeSec = mSessionStartTimeSec > 0
                ? getElapsedRealtimeInSec() - mSessionStartTimeSec : 0;
        int numberOfSatelliteConnections = getNumberOfSatelliteConnections();

        List<Integer> connectionGapList = getSatelliteConnectionGapList(
                numberOfSatelliteConnections);
        int satelliteConnectionGapMinSec = 0;
        int satelliteConnectionGapMaxSec = 0;
        if (!connectionGapList.isEmpty()) {
            satelliteConnectionGapMinSec = Collections.min(connectionGapList);
            satelliteConnectionGapMaxSec = Collections.max(connectionGapList);
        }
        boolean isMultiSim = mSubscriptionManagerService.getActiveSubIdList(true).length > 1;

        SatelliteStats.CarrierRoamingSatelliteSessionParams params =
                new SatelliteStats.CarrierRoamingSatelliteSessionParams.Builder()
                        .setCarrierId(mCarrierId)
                        .setIsNtnRoamingInHomeCountry(mIsNtnRoamingInHomeCountry)
                        .setTotalSatelliteModeTimeSec(totalSatelliteModeTimeSec)
                        .setNumberOfSatelliteConnections(numberOfSatelliteConnections)
                        .setAvgDurationOfSatelliteConnectionSec(
                                getAvgDurationOfSatelliteConnection())
                        .setSatelliteConnectionGapMinSec(satelliteConnectionGapMinSec)
                        .setSatelliteConnectionGapAvgSec(getAvg(connectionGapList))
                        .setSatelliteConnectionGapMaxSec(satelliteConnectionGapMaxSec)
                        .setRsrpAvg(getAvg(mRsrpList))
                        .setRsrpMedian(getMedian(mRsrpList))
                        .setRssnrAvg(getAvg(mRssnrList))
                        .setRssnrMedian(getMedian(mRssnrList))
                        .setCountOfIncomingSms(mCountOfIncomingSms)
                        .setCountOfOutgoingSms(mCountOfOutgoingSms)
                        .setCountOfIncomingMms(mCountOfIncomingMms)
                        .setCountOfOutgoingMms(mCountOfOutgoingMms)
                        .setSupportedSatelliteServices(mSupportedSatelliteServices)
                        .setServiceDataPolicy(mServiceDataPolicy)
                        .setSatelliteDataConsumedBytes(mSatelliteDataConsumedBytes)
                        .setIsMultiSim(isMultiSim)
                        .setIsNbIotNtn(SatelliteServiceUtils.isNbIotNtn(subId))
                        .build();
        SatelliteStats.getInstance().onCarrierRoamingSatelliteSessionMetrics(params);
        logd("Supported satellite services: " + Arrays.toString(mSupportedSatelliteServices));
        logd("reportMetrics: " + params);
        initializeParams();
    }

    private void initializeParams() {
        mCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
        mIsNtnRoamingInHomeCountry = false;
        mCountOfIncomingSms = 0;
        mCountOfOutgoingSms = 0;
        mCountOfIncomingMms = 0;
        mCountOfOutgoingMms = 0;
        mIncomingMessageId = 0;

        mSessionStartTimeSec = 0;
        mSatelliteConnectionTimes = null;
        mSatelliteConnectionTimesList = new ArrayList<>();
        mRsrpList = new ArrayList<>();
        mRssnrList = new ArrayList<>();
        logd("initializeParams");
    }

    private CellSignalStrengthLte getCellSignalStrengthLte(Phone phone) {
        SignalStrength signalStrength = phone.getSignalStrength();
        List<CellSignalStrength> cellSignalStrengths = signalStrength.getCellSignalStrengths();
        for (CellSignalStrength cellSignalStrength : cellSignalStrengths) {
            if (cellSignalStrength instanceof CellSignalStrengthLte) {
                return (CellSignalStrengthLte) cellSignalStrength;
            }
        }

        return new CellSignalStrengthLte();
    }

    private int getNumberOfSatelliteConnections() {
        return mSatelliteConnectionTimesList.size();
    }

    private int getAvgDurationOfSatelliteConnection() {
        if (mSatelliteConnectionTimesList.isEmpty()) {
            return 0;
        }

        OptionalDouble averageDuration = mSatelliteConnectionTimesList.stream()
                .filter(SatelliteConnectionTimes::isValid)
                .mapToLong(SatelliteConnectionTimes::getDuration)
                .average();

        return (int) (averageDuration.isPresent() ? averageDuration.getAsDouble() / 1000 : 0);
    }

    private List<Integer> getSatelliteConnectionGapList(int numberOfSatelliteConnections) {
        if (mSatelliteConnectionTimesList.size() < 2) {
            return new ArrayList<>();
        }

        List<Integer> connectionGapList = new ArrayList<>();
        for (int i = 1; i < mSatelliteConnectionTimesList.size(); i++) {
            SatelliteConnectionTimes prevConnection =
                    mSatelliteConnectionTimesList.get(i - 1);
            SatelliteConnectionTimes currentConnection =
                    mSatelliteConnectionTimesList.get(i);

            if (prevConnection.getEndTime() > 0
                    && currentConnection.getStartTime() > prevConnection.getEndTime()) {
                int gap = (int) ((currentConnection.getStartTime() - prevConnection.getEndTime())
                        / 1000);
                connectionGapList.add(gap);
            }
        }
        return connectionGapList;
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

    private int getMedian(@NonNull List<Integer> list) {
        if (list.isEmpty()) {
            return 0;
        }
        int size = list.size();
        if (size == 1) {
            return list.get(0);
        }

        Collections.sort(list);
        return size % 2 == 0 ? (list.get(size / 2 - 1) + list.get(size / 2)) / 2
                : list.get(size / 2);
    }

    private int getElapsedRealtimeInSec() {
        return (int) (getElapsedRealtime() / 1000);
    }

    private long getElapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    private boolean isNtnConnected() {
        return mSessionStartTimeSec != 0;
    }

    private void updateNtnRoamingInHomeCountry(Phone phone) {
        int subId = phone.getSubId();
        ServiceState serviceState = phone.getServiceState();
        if (serviceState == null) {
            logd("ServiceState is null");
            return;
        }

        String satelliteRegisteredPlmn = "";
        for (NetworkRegistrationInfo nri
                : serviceState.getNetworkRegistrationInfoList()) {
            if (nri.isNonTerrestrialNetwork()) {
                satelliteRegisteredPlmn = nri.getRegisteredPlmn();
            }
        }

        SubscriptionInfoInternal subscriptionInfoInternal =
                mSubscriptionManagerService.getSubscriptionInfoInternal(subId);
        if (subscriptionInfoInternal == null) {
            logd("SubscriptionInfoInternal is null");
            return;
        }
        String simCountry = MccTable.countryCodeForMcc(subscriptionInfoInternal.getMcc());
        mIsNtnRoamingInHomeCountry = true;
        if (satelliteRegisteredPlmn != null
                && satelliteRegisteredPlmn.length() >= 3) {
            String satelliteRegisteredCountry = MccTable.countryCodeForMcc(
                    satelliteRegisteredPlmn.substring(0, 3));
            if (simCountry.equalsIgnoreCase(satelliteRegisteredCountry)) {
                mIsNtnRoamingInHomeCountry = true;
            } else {
                // If device is connected to roaming non-terrestrial network, then marking as
                // roaming in external country
                mIsNtnRoamingInHomeCountry = false;
            }
        }
        logd("updateNtnRoamingInHomeCountry: mIsNtnRoamingInHomeCountry="
                + mIsNtnRoamingInHomeCountry);
    }

    private static class SatelliteConnectionTimes {
        private final long mStartTime;
        private long mEndTime;

        SatelliteConnectionTimes(long startTime) {
            this.mStartTime = startTime;
            this.mEndTime = 0;
        }

        public void setEndTime(long endTime) {
            this.mEndTime = endTime;
        }

        public long getStartTime() {
            return mStartTime;
        }

        public long getEndTime() {
            return mEndTime;
        }

        public long getDuration() {
            if (isValid()) {
                return mEndTime - mStartTime;
            }
            return 0;
        }

        public boolean isValid() {
            return mEndTime > mStartTime && mStartTime > 0;
        }
    }

    private void logd(@NonNull String log) {
        Log.d(TAG, log);
    }

    private void loge(@NonNull String log) {
        Log.e(TAG, log);
    }
}
