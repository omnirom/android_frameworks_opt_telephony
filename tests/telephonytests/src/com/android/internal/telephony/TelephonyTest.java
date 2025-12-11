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

import static com.android.internal.telephony.TelephonyStatsLog.CELLULAR_SERVICE_STATE__FOLD_STATE__STATE_UNKNOWN;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.app.PropertyInvalidatedCache;
import android.app.admin.DevicePolicyManager;
import android.app.usage.NetworkStatsManager;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicyManager;
import android.net.vcn.VcnManager;
import android.net.vcn.VcnNetworkPolicyResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RegistrantList;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.UserManager;
import android.permission.LegacyPermissionManager;
import android.provider.BlockedNumberContract;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.CellIdentity;
import android.telephony.CellLocation;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyRegistryManager;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.euicc.EuiccManager;
import android.telephony.ims.ImsCallProfile;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.testing.TestableLooper;
import android.util.Log;
import android.util.Singleton;
import android.view.textclassifier.TextClassifier;

import com.android.ims.ImsCall;
import com.android.ims.ImsEcbm;
import com.android.ims.ImsManager;
import com.android.internal.telephony.analytics.TelephonyAnalytics;
import com.android.internal.telephony.data.AccessNetworksManager;
import com.android.internal.telephony.data.CellularNetworkValidator;
import com.android.internal.telephony.data.DataConfigManager;
import com.android.internal.telephony.data.DataNetworkController;
import com.android.internal.telephony.data.DataProfileManager;
import com.android.internal.telephony.data.DataRetryManager;
import com.android.internal.telephony.data.DataServiceManager;
import com.android.internal.telephony.data.DataSettingsManager;
import com.android.internal.telephony.data.LinkBandwidthEstimator;
import com.android.internal.telephony.data.PhoneSwitcher;
import com.android.internal.telephony.domainselection.DomainSelectionResolver;
import com.android.internal.telephony.emergency.EmergencyNumberTracker;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.imsphone.ImsExternalCallTracker;
import com.android.internal.telephony.imsphone.ImsNrSaModeHandler;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsPhoneCallTracker;
import com.android.internal.telephony.metrics.DefaultNetworkMonitor;
import com.android.internal.telephony.metrics.DeviceStateHelper;
import com.android.internal.telephony.metrics.ImsStats;
import com.android.internal.telephony.metrics.MetricsCollector;
import com.android.internal.telephony.metrics.PersistAtomsStorage;
import com.android.internal.telephony.metrics.ServiceStateStats;
import com.android.internal.telephony.metrics.SmsStats;
import com.android.internal.telephony.metrics.VoiceCallSessionStats;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.internal.telephony.security.CellularIdentifierDisclosureNotifier;
import com.android.internal.telephony.security.CellularNetworkSecuritySafetySource;
import com.android.internal.telephony.security.NullCipherNotifier;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import com.android.internal.telephony.uicc.IccCardStatus;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IsimUiccRecords;
import com.android.internal.telephony.uicc.PinStorage;
import com.android.internal.telephony.uicc.RuimRecords;
import com.android.internal.telephony.uicc.SIMRecords;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccPort;
import com.android.internal.telephony.uicc.UiccProfile;
import com.android.internal.telephony.uicc.UiccSlot;
import com.android.internal.telephony.util.WorkerThread;
import com.android.server.pm.permission.LegacyPermissionManagerService;

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public abstract class TelephonyTest {
    protected static String TAG;

    private static final int MAX_INIT_WAIT_MS = 30000; // 30 seconds

    private static final EmergencyNumber SAMPLE_EMERGENCY_NUMBER =
            new EmergencyNumber("911", "us", "30",
                    EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
            new ArrayList<String>(), EmergencyNumber.EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING,
            EmergencyNumber.EMERGENCY_CALL_ROUTING_NORMAL);

    // Mocked classes
    protected FeatureFlags mFeatureFlags;
    protected GsmCdmaPhone mPhone;
    protected GsmCdmaPhone mPhone2;
    protected ImsPhone mImsPhone;
    protected ServiceStateTracker mSST;
    protected EmergencyNumberTracker mEmergencyNumberTracker;
    protected GsmCdmaCallTracker mCT;
    protected ImsPhoneCallTracker mImsCT;
    protected UiccController mUiccController;
    protected UiccProfile mUiccProfile;
    protected UiccSlot mUiccSlot;
    protected CallManager mCallManager;
    protected PhoneNotifier mNotifier;
    protected TelephonyComponentFactory mTelephonyComponentFactory;
    protected RegistrantList mRegistrantList;
    protected IccPhoneBookInterfaceManager mIccPhoneBookIntManager;
    protected ImsManager mImsManager;
    protected DataNetworkController mDataNetworkController;
    protected DataRetryManager mDataRetryManager;
    protected DataSettingsManager mDataSettingsManager;
    protected DataConfigManager mDataConfigManager;
    protected DataProfileManager mDataProfileManager;
    protected DisplayInfoController mDisplayInfoController;
    protected GsmCdmaCall mGsmCdmaCall;
    protected ImsCall mImsCall;
    protected ImsEcbm mImsEcbm;
    protected SubscriptionManagerService mSubscriptionManagerService;
    protected ServiceState mServiceState;
    protected IPackageManager.Stub mMockPackageManager;
    protected LegacyPermissionManagerService mMockLegacyPermissionManager;
    protected SimulatedCommandsVerifier mSimulatedCommandsVerifier;
    protected InboundSmsHandler mInboundSmsHandler;
    protected WspTypeDecoder mWspTypeDecoder;
    protected UiccCardApplication mUiccCardApplication3gpp;
    protected UiccCardApplication mUiccCardApplication3gpp2;
    protected UiccCardApplication mUiccCardApplicationIms;
    protected SIMRecords mSimRecords;
    protected SignalStrengthController mSignalStrengthController;
    protected RuimRecords mRuimRecords;
    protected IsimUiccRecords mIsimUiccRecords;
    protected ProxyController mProxyController;
    protected PhoneSwitcher mPhoneSwitcher;
    protected Singleton<IActivityManager> mIActivityManagerSingleton;
    protected IActivityManager mIActivityManager;
    protected IIntentSender mIIntentSender;
    protected IBinder mIBinder;
    protected SmsStorageMonitor mSmsStorageMonitor;
    protected SmsUsageMonitor mSmsUsageMonitor;
    protected PackageInfo mPackageInfo;
    protected ApplicationInfo mApplicationInfo;
    protected IBinder mConnMetLoggerBinder;
    protected CarrierSignalAgent mCarrierSignalAgent;
    protected CarrierActionAgent mCarrierActionAgent;
    protected ImsExternalCallTracker mImsExternalCallTracker;
    protected ImsNrSaModeHandler mImsNrSaModeHandler;
    protected AppSmsManager mAppSmsManager;
    protected IccSmsInterfaceManager mIccSmsInterfaceManager;
    protected SmsDispatchersController mSmsDispatchersController;
    protected DeviceStateMonitor mDeviceStateMonitor;
    protected AccessNetworksManager mAccessNetworksManager;
    protected IntentBroadcaster mIntentBroadcaster;
    protected NitzStateMachine mNitzStateMachine;
    protected RadioConfig mMockRadioConfig;
    protected RadioConfigProxy mMockRadioConfigProxy;
    protected LocaleTracker mLocaleTracker;
    protected RestrictedState mRestrictedState;
    protected PhoneConfigurationManager mPhoneConfigurationManager;
    protected CellularNetworkValidator mCellularNetworkValidator;
    protected UiccCard mUiccCard;
    protected UiccPort mUiccPort;
    protected MultiSimSettingController mMultiSimSettingController;
    protected IccCard mIccCard;
    protected NetworkStatsManager mStatsManager;
    protected CarrierPrivilegesTracker mCarrierPrivilegesTracker;
    protected VoiceCallSessionStats mVoiceCallSessionStats;
    protected PersistAtomsStorage mPersistAtomsStorage;
    protected DefaultNetworkMonitor mDefaultNetworkMonitor;
    protected MetricsCollector mMetricsCollector;
    protected SmsStats mSmsStats;
    protected TelephonyAnalytics mTelephonyAnalytics;
    protected SignalStrength mSignalStrength;
    protected WifiManager mWifiManager;
    protected WifiInfo mWifiInfo;
    protected ImsStats mImsStats;
    protected LinkBandwidthEstimator mLinkBandwidthEstimator;
    protected PinStorage mPinStorage;
    protected LocationManager mLocationManager;
    protected CellIdentity mCellIdentity;
    protected CellLocation mCellLocation;
    protected DataServiceManager mMockedWwanDataServiceManager;
    protected DataServiceManager mMockedWlanDataServiceManager;
    protected ServiceStateStats mServiceStateStats;
    protected SatelliteController mSatelliteController;
    protected DeviceStateHelper mDeviceStateHelper;
    protected CellularNetworkSecuritySafetySource mSafetySource;
    protected CellularIdentifierDisclosureNotifier mIdentifierDisclosureNotifier;
    protected DomainSelectionResolver mDomainSelectionResolver;
    protected NullCipherNotifier mNullCipherNotifier;

    // Initialized classes
    protected ActivityManager mActivityManager;
    protected ImsCallProfile mImsCallProfile;
    protected TelephonyManager mTelephonyManager;
    protected TelecomManager mTelecomManager;
    protected TelephonyRegistryManager mTelephonyRegistryManager;
    protected SubscriptionManager mSubscriptionManager;
    protected EuiccManager mEuiccManager;
    protected PackageManager mPackageManager;
    protected ConnectivityManager mConnectivityManager;
    protected AppOpsManager mAppOpsManager;
    protected CarrierConfigManager mCarrierConfigManager;
    protected UserManager mUserManager;
    protected DevicePolicyManager mDevicePolicyManager;
    protected KeyguardManager mKeyguardManager;
    protected VcnManager mVcnManager;
    protected NetworkPolicyManager mNetworkPolicyManager;
    protected SimulatedCommands mSimulatedCommands;
    protected ContextFixture mContextFixture;
    protected Context mContext;
    protected FakeBlockedNumberContentProvider mFakeBlockedNumberContentProvider;
    private final ContentProvider mContentProvider = spy(new ContextFixture.FakeContentProvider());
    private final Object mLock = new Object();
    private boolean mReady;
    protected HashMap<String, IBinder> mServiceManagerMockedServices = new HashMap<>();
    protected Phone[] mPhones;
    protected NetworkRegistrationInfo mNetworkRegistrationInfo =
            new NetworkRegistrationInfo.Builder()
                    .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_LTE)
                    .setRegistrationState(NetworkRegistrationInfo.REGISTRATION_STATE_HOME)
                    .build();
    protected List<TestableLooper> mTestableLoopers = new ArrayList<>();
    protected TestableLooper mTestableLooper;

    private final HashMap<InstanceKey, Object> mOldInstances = new HashMap<>();

    private final List<InstanceKey> mInstanceKeys = new ArrayList<>();

    protected int mIntegerConsumerResult;

    protected TextClassifier mTextClassifier;

    protected Semaphore mIntegerConsumerSemaphore = new Semaphore(0);
    protected  Consumer<Integer> mIntegerConsumer = new Consumer<Integer>() {
        @Override
        public void accept(Integer integer) {
            logd("mIIntegerConsumer: result=" + integer);
            mIntegerConsumerResult =  integer;
            try {
                mIntegerConsumerSemaphore.release();
            } catch (Exception ex) {
                logd("mIIntegerConsumer: Got exception in releasing semaphore, ex=" + ex);
            }
        }
    };

    protected boolean waitForIntegerConsumerResponse(int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!mIntegerConsumerSemaphore.tryAcquire(500 /*Timeout*/, TimeUnit.MILLISECONDS)) {
                    logd("Timeout to receive IIntegerConsumer() callback");
                    return false;
                }
            } catch (Exception ex) {
                logd("waitForIIntegerConsumerResult: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    private class InstanceKey {
        public final Class mClass;
        public final String mInstName;
        public final Object mObj;
        InstanceKey(final Class c, final String instName, final Object obj) {
            mClass = c;
            mInstName = instName;
            mObj = obj;
        }

        @Override
        public int hashCode() {
            return (mClass.getName().hashCode() * 31 + mInstName.hashCode()) * 31;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || !(obj instanceof InstanceKey)) {
                return false;
            }

            InstanceKey other = (InstanceKey) obj;
            return (other.mClass == mClass && other.mInstName.equals(mInstName)
                    && other.mObj == mObj);
        }
    }

    protected void waitUntilReady() {
        synchronized (mLock) {
            long now = SystemClock.elapsedRealtime();
            long deadline = now + MAX_INIT_WAIT_MS;
            while (!mReady && now < deadline) {
                try {
                    mLock.wait(MAX_INIT_WAIT_MS);
                } catch (Exception e) {
                    fail("Telephony tests failed to initialize: e=" + e);
                }
                now = SystemClock.elapsedRealtime();
            }
            if (!mReady) {
                fail("Telephony tests failed to initialize");
            }
        }
    }

    protected void setReady(boolean ready) {
        synchronized (mLock) {
            mReady = ready;
            mLock.notifyAll();
        }
    }

    protected synchronized void replaceInstance(final Class c, final String instanceName,
                                                final Object obj, final Object newValue)
            throws Exception {
        Field field = c.getDeclaredField(instanceName);
        field.setAccessible(true);

        InstanceKey key = new InstanceKey(c, instanceName, obj);
        if (!mOldInstances.containsKey(key)) {
            mOldInstances.put(key, field.get(obj));
            mInstanceKeys.add(key);
        }
        field.set(obj, newValue);
    }

    protected static <T> T getPrivateField(Object object, String fieldName, Class<T> fieldType)
            throws Exception {

        Class<?> clazz = object.getClass();
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);

        return fieldType.cast(field.get(object));
    }

    protected synchronized void restoreInstance(final Class c, final String instanceName,
                                                final Object obj) throws Exception {
        InstanceKey key = new InstanceKey(c, instanceName, obj);
        if (mOldInstances.containsKey(key)) {
            Field field = c.getDeclaredField(instanceName);
            field.setAccessible(true);
            field.set(obj, mOldInstances.get(key));
            mOldInstances.remove(key);
            mInstanceKeys.remove(key);
        }
    }

    protected synchronized void restoreInstances() throws Exception {
        for (int i = mInstanceKeys.size() - 1; i >= 0; i--) {
            InstanceKey key = mInstanceKeys.get(i);
            Field field = key.mClass.getDeclaredField(key.mInstName);
            field.setAccessible(true);
            field.set(key.mObj, mOldInstances.get(key));
        }

        mInstanceKeys.clear();
        mOldInstances.clear();
    }

    // TODO: Unit tests that do not extend TelephonyTest or ImsTestBase should enable strict mode
    //   by calling this method.
    public static void enableStrictMode() {
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectIncorrectContextUse()
                .detectLeakedRegistrationObjects()
                .detectUnsafeIntentLaunch()
                .detectActivityLeaks()
                .penaltyLog()
                .penaltyDeath()
                .build());
    }

    protected void setUp(String tag) throws Exception {
        TAG = tag;
        enableStrictMode();
        mFeatureFlags = Mockito.mock(FeatureFlags.class);
        mPhone = Mockito.mock(GsmCdmaPhone.class);
        mPhone2 = Mockito.mock(GsmCdmaPhone.class);
        mTextClassifier = Mockito.mock(TextClassifier.class);
        mImsPhone = Mockito.mock(ImsPhone.class);
        mSST = Mockito.mock(ServiceStateTracker.class);
        mEmergencyNumberTracker = Mockito.mock(EmergencyNumberTracker.class);
        mCT = Mockito.mock(GsmCdmaCallTracker.class);
        mImsCT = Mockito.mock(ImsPhoneCallTracker.class);
        mUiccController = Mockito.mock(UiccController.class);
        mUiccProfile = Mockito.mock(UiccProfile.class);
        mUiccSlot = Mockito.mock(UiccSlot.class);
        mCallManager = Mockito.mock(CallManager.class);
        mNotifier = Mockito.mock(PhoneNotifier.class);
        mTelephonyComponentFactory = Mockito.mock(TelephonyComponentFactory.class);
        mRegistrantList = Mockito.mock(RegistrantList.class);
        mIccPhoneBookIntManager = Mockito.mock(IccPhoneBookInterfaceManager.class);
        mImsManager = Mockito.mock(ImsManager.class);
        mDataNetworkController = Mockito.mock(DataNetworkController.class);
        mDataRetryManager = Mockito.mock(DataRetryManager.class);
        mDataSettingsManager = Mockito.mock(DataSettingsManager.class);
        mDataConfigManager = Mockito.mock(DataConfigManager.class);
        mDataProfileManager = Mockito.mock(DataProfileManager.class);
        mDisplayInfoController = Mockito.mock(DisplayInfoController.class);
        mGsmCdmaCall = Mockito.mock(GsmCdmaCall.class);
        mImsCall = Mockito.mock(ImsCall.class);
        mImsEcbm = Mockito.mock(ImsEcbm.class);
        mSubscriptionManagerService = Mockito.mock(SubscriptionManagerService.class);
        mServiceState = Mockito.mock(ServiceState.class);
        mMockPackageManager = Mockito.mock(IPackageManager.Stub.class);
        mMockLegacyPermissionManager = Mockito.mock(LegacyPermissionManagerService.class);
        mSimulatedCommandsVerifier = Mockito.mock(SimulatedCommandsVerifier.class);
        mInboundSmsHandler = Mockito.mock(InboundSmsHandler.class);
        mWspTypeDecoder = Mockito.mock(WspTypeDecoder.class);
        mUiccCardApplication3gpp = Mockito.mock(UiccCardApplication.class);
        mUiccCardApplication3gpp2 = Mockito.mock(UiccCardApplication.class);
        mUiccCardApplicationIms = Mockito.mock(UiccCardApplication.class);
        mSimRecords = Mockito.mock(SIMRecords.class);
        mSignalStrengthController = Mockito.mock(SignalStrengthController.class);
        mRuimRecords = Mockito.mock(RuimRecords.class);
        mIsimUiccRecords = Mockito.mock(IsimUiccRecords.class);
        mProxyController = Mockito.mock(ProxyController.class);
        mPhoneSwitcher = Mockito.mock(PhoneSwitcher.class);
        mIActivityManagerSingleton = Mockito.mock(Singleton.class);
        mIActivityManager = Mockito.mock(IActivityManager.class);
        mIIntentSender = Mockito.mock(IIntentSender.class);
        mIBinder = Mockito.mock(IBinder.class);
        mSmsStorageMonitor = Mockito.mock(SmsStorageMonitor.class);
        mSmsUsageMonitor = Mockito.mock(SmsUsageMonitor.class);
        mPackageInfo = Mockito.mock(PackageInfo.class);
        mApplicationInfo = Mockito.mock(ApplicationInfo.class);
        mConnMetLoggerBinder = Mockito.mock(IBinder.class);
        mCarrierSignalAgent = Mockito.mock(CarrierSignalAgent.class);
        mCarrierActionAgent = Mockito.mock(CarrierActionAgent.class);
        mImsExternalCallTracker = Mockito.mock(ImsExternalCallTracker.class);
        mImsNrSaModeHandler = Mockito.mock(ImsNrSaModeHandler.class);
        mAppSmsManager = Mockito.mock(AppSmsManager.class);
        mIccSmsInterfaceManager = Mockito.mock(IccSmsInterfaceManager.class);
        mSmsDispatchersController = Mockito.mock(SmsDispatchersController.class);
        mDeviceStateMonitor = Mockito.mock(DeviceStateMonitor.class);
        mAccessNetworksManager = Mockito.mock(AccessNetworksManager.class);
        mIntentBroadcaster = Mockito.mock(IntentBroadcaster.class);
        mNitzStateMachine = Mockito.mock(NitzStateMachine.class);
        mMockRadioConfig = Mockito.mock(RadioConfig.class);
        mMockRadioConfigProxy = Mockito.mock(RadioConfigProxy.class);
        mLocaleTracker = Mockito.mock(LocaleTracker.class);
        mRestrictedState = Mockito.mock(RestrictedState.class);
        mPhoneConfigurationManager = Mockito.mock(PhoneConfigurationManager.class);
        mCellularNetworkValidator = Mockito.mock(CellularNetworkValidator.class);
        mUiccCard = Mockito.mock(UiccCard.class);
        mUiccPort = Mockito.mock(UiccPort.class);
        mMultiSimSettingController = Mockito.mock(MultiSimSettingController.class);
        mIccCard = Mockito.mock(IccCard.class);
        mStatsManager = Mockito.mock(NetworkStatsManager.class);
        mCarrierPrivilegesTracker = Mockito.mock(CarrierPrivilegesTracker.class);
        mVoiceCallSessionStats = Mockito.mock(VoiceCallSessionStats.class);
        mPersistAtomsStorage = Mockito.mock(PersistAtomsStorage.class);
        mDefaultNetworkMonitor = Mockito.mock(DefaultNetworkMonitor.class);
        mMetricsCollector = Mockito.mock(MetricsCollector.class);
        mSmsStats = Mockito.mock(SmsStats.class);
        mTelephonyAnalytics = Mockito.mock(TelephonyAnalytics.class);
        mSignalStrength = Mockito.mock(SignalStrength.class);
        mWifiManager = Mockito.mock(WifiManager.class);
        mWifiInfo = Mockito.mock(WifiInfo.class);
        mImsStats = Mockito.mock(ImsStats.class);
        mLinkBandwidthEstimator = Mockito.mock(LinkBandwidthEstimator.class);
        mPinStorage = Mockito.mock(PinStorage.class);
        mLocationManager = Mockito.mock(LocationManager.class);
        mCellIdentity = Mockito.mock(CellIdentity.class);
        mCellLocation = Mockito.mock(CellLocation.class);
        mMockedWwanDataServiceManager = Mockito.mock(DataServiceManager.class);
        mMockedWlanDataServiceManager = Mockito.mock(DataServiceManager.class);
        mServiceStateStats = Mockito.mock(ServiceStateStats.class);
        mSatelliteController = Mockito.mock(SatelliteController.class);
        mDeviceStateHelper = Mockito.mock(DeviceStateHelper.class);
        mSafetySource = Mockito.mock(CellularNetworkSecuritySafetySource.class);
        mIdentifierDisclosureNotifier = Mockito.mock(CellularIdentifierDisclosureNotifier.class);
        mDomainSelectionResolver = Mockito.mock(DomainSelectionResolver.class);
        mNullCipherNotifier = Mockito.mock(NullCipherNotifier.class);

        lenient().doReturn(true).when(mFeatureFlags).dataServiceCheck();
        lenient().doReturn(true).when(mFeatureFlags).dynamicModemShutdown();
        lenient().doReturn(true).when(mFeatureFlags).dataServiceNotifyImsDataNetwork();
        lenient().doReturn(true).when(mFeatureFlags).keepWfcOnApm();
        lenient().doReturn(true).when(mFeatureFlags).allowMultiCountryMcc();
        lenient().doReturn(true).when(mFeatureFlags).deleteCdma();

        WorkerThread.reset();
        TelephonyManager.disableServiceHandleCaching();
        PropertyInvalidatedCache.disableForTestMode();
        // For testing do not allow Log.WTF as it can cause test process to crash
        Log.setWtfHandler((tagString, what, system) -> Log.d(TAG, "WTF captured, ignoring. Tag: "
                + tagString + ", exception: " + what));

        mPhones = new Phone[] {mPhone};
        mImsCallProfile = new ImsCallProfile();
        mImsCallProfile.setCallerNumberVerificationStatus(
                ImsCallProfile.VERIFICATION_STATUS_PASSED);
        mSimulatedCommands = new SimulatedCommands();
        mContextFixture = new ContextFixture();
        mContext = mContextFixture.getTestDouble();
        mFakeBlockedNumberContentProvider = new FakeBlockedNumberContentProvider();
        ((MockContentResolver)mContext.getContentResolver()).addProvider(
                BlockedNumberContract.AUTHORITY, mFakeBlockedNumberContentProvider);
        ((MockContentResolver) mContext.getContentResolver()).addProvider(
                Settings.AUTHORITY, mContentProvider);
        ((MockContentResolver) mContext.getContentResolver()).addProvider(
                Telephony.ServiceStateTable.AUTHORITY, mContentProvider);
        replaceContentProvider(mContentProvider);

        Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0);

        mServiceManagerMockedServices.put("isub", mSubscriptionManagerService);
        lenient().doReturn(mSubscriptionManagerService).when(mSubscriptionManagerService)
                .queryLocalInterface(anyString());

        mPhone.mCi = mSimulatedCommands;
        mPhone.mCT = mCT;
        mCT.mCi = mSimulatedCommands;
        lenient().doReturn(mUiccCard).when(mPhone).getUiccCard();
        lenient().doReturn(mUiccCard).when(mUiccSlot).getUiccCard();
        lenient().doReturn(mUiccCard).when(mUiccController).getUiccCardForPhone(anyInt());
        lenient().doReturn(mUiccPort).when(mPhone).getUiccPort();
        lenient().doReturn(mUiccProfile).when(mUiccPort).getUiccProfile();

        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mTelecomManager = mContext.getSystemService(TelecomManager.class);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mTelephonyRegistryManager = (TelephonyRegistryManager) mContext.getSystemService(
            Context.TELEPHONY_REGISTRY_SERVICE);
        mSubscriptionManager = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mEuiccManager = (EuiccManager) mContext.getSystemService(Context.EUICC_SERVICE);
        mConnectivityManager = (ConnectivityManager)
                mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mPackageManager = mContext.getPackageManager();
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mCarrierConfigManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mDevicePolicyManager = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mVcnManager = mContext.getSystemService(VcnManager.class);
        mNetworkPolicyManager = mContext.getSystemService(NetworkPolicyManager.class);
        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);

        //mTelephonyComponentFactory
        lenient().doReturn(mTelephonyComponentFactory).when(mTelephonyComponentFactory)
                .inject(anyString());
        lenient().doReturn(mSST).when(mTelephonyComponentFactory)
                .makeServiceStateTracker(nullable(GsmCdmaPhone.class),
                        nullable(CommandsInterface.class), nullable(FeatureFlags.class));
        lenient().doReturn(mEmergencyNumberTracker).when(mTelephonyComponentFactory)
                .makeEmergencyNumberTracker(nullable(Phone.class),
                        nullable(CommandsInterface.class), any(FeatureFlags.class));
        lenient().doReturn(getTestEmergencyNumber()).when(mEmergencyNumberTracker)
                .getEmergencyNumber(any());
        lenient().doReturn(mUiccProfile).when(mTelephonyComponentFactory)
                .makeUiccProfile(nullable(Context.class), nullable(CommandsInterface.class),
                        nullable(IccCardStatus.class), anyInt(), nullable(UiccCard.class),
                        nullable(Object.class), any(FeatureFlags.class));
        lenient().doReturn(mCT).when(mTelephonyComponentFactory)
                .makeGsmCdmaCallTracker(nullable(GsmCdmaPhone.class), any(FeatureFlags.class));
        lenient().doReturn(mIccPhoneBookIntManager).when(mTelephonyComponentFactory)
                .makeIccPhoneBookInterfaceManager(nullable(Phone.class));
        lenient().doReturn(mDisplayInfoController).when(mTelephonyComponentFactory)
                .makeDisplayInfoController(nullable(Phone.class), any(FeatureFlags.class));
        lenient().doReturn(mWspTypeDecoder).when(mTelephonyComponentFactory)
                .makeWspTypeDecoder(nullable(byte[].class));
        lenient().doReturn(mImsCT).when(mTelephonyComponentFactory)
                .makeImsPhoneCallTracker(nullable(ImsPhone.class), any(FeatureFlags.class));
        lenient().doReturn(mImsExternalCallTracker).when(mTelephonyComponentFactory)
                .makeImsExternalCallTracker(nullable(ImsPhone.class));
        lenient().doReturn(mImsNrSaModeHandler).when(mTelephonyComponentFactory)
                .makeImsNrSaModeHandler(nullable(ImsPhone.class));
        lenient().doReturn(mAppSmsManager).when(mTelephonyComponentFactory)
                .makeAppSmsManager(nullable(Context.class));
        lenient().doReturn(mCarrierSignalAgent).when(mTelephonyComponentFactory)
                .makeCarrierSignalAgent(nullable(Phone.class));
        lenient().doReturn(mCarrierActionAgent).when(mTelephonyComponentFactory)
                .makeCarrierActionAgent(nullable(Phone.class));
        lenient().doReturn(mDeviceStateMonitor).when(mTelephonyComponentFactory)
                .makeDeviceStateMonitor(nullable(Phone.class), any(FeatureFlags.class));
        lenient().doReturn(mAccessNetworksManager).when(mTelephonyComponentFactory)
                .makeAccessNetworksManager(nullable(Phone.class), any(Looper.class),
                        any(FeatureFlags.class));
        lenient().doReturn(mNitzStateMachine).when(mTelephonyComponentFactory)
                .makeNitzStateMachine(nullable(GsmCdmaPhone.class));
        lenient().doReturn(mLocaleTracker).when(mTelephonyComponentFactory)
                .makeLocaleTracker(nullable(Phone.class), nullable(NitzStateMachine.class),
                        nullable(Looper.class), any(FeatureFlags.class));
        lenient().doReturn(mLinkBandwidthEstimator).when(mTelephonyComponentFactory)
                .makeLinkBandwidthEstimator(nullable(Phone.class), any(Looper.class));
        lenient().doReturn(mDataProfileManager).when(mTelephonyComponentFactory)
                .makeDataProfileManager(any(Phone.class), any(DataNetworkController.class),
                        any(DataServiceManager.class), any(Looper.class),
                        any(FeatureFlags.class),
                        any(DataProfileManager.DataProfileManagerCallback.class));
        lenient().doReturn(mSafetySource).when(mTelephonyComponentFactory)
                .makeCellularNetworkSecuritySafetySource(any(Context.class));
        lenient().doReturn(mIdentifierDisclosureNotifier)
                .when(mTelephonyComponentFactory)
                .makeIdentifierDisclosureNotifier(
                        nullable(CellularNetworkSecuritySafetySource.class));
        lenient().doReturn(mNullCipherNotifier)
                .when(mTelephonyComponentFactory)
                .makeNullCipherNotifier(nullable(CellularNetworkSecuritySafetySource.class));

        //mPhone
        lenient().doReturn(mContext).when(mPhone).getContext();
        lenient().doReturn(mContext).when(mPhone2).getContext();
        lenient().doReturn(mContext).when(mImsPhone).getContext();
        lenient().doReturn(true).when(mPhone).getUnitTestMode();
        lenient().doReturn(mUiccProfile).when(mPhone).getIccCard();
        lenient().doReturn(mServiceState).when(mPhone).getServiceState();
        lenient().doReturn(mServiceState).when(mImsPhone).getServiceState();
        lenient().doReturn(mPhone).when(mImsPhone).getDefaultPhone();
        lenient().doReturn(PhoneConstants.PHONE_TYPE_GSM).when(mPhone).getPhoneType();
        lenient().doReturn(mCT).when(mPhone).getCallTracker();
        lenient().doReturn(mSST).when(mPhone).getServiceStateTracker();
        lenient().doReturn(mDeviceStateMonitor).when(mPhone).getDeviceStateMonitor();
        lenient().doReturn(mDisplayInfoController).when(mPhone).getDisplayInfoController();
        lenient().doReturn(mSignalStrengthController).when(mPhone).getSignalStrengthController();
        lenient().doReturn(mEmergencyNumberTracker).when(mPhone).getEmergencyNumberTracker();
        lenient().doReturn(mCarrierSignalAgent).when(mPhone).getCarrierSignalAgent();
        lenient().doReturn(mCarrierActionAgent).when(mPhone).getCarrierActionAgent();
        lenient().doReturn(mAppSmsManager).when(mPhone).getAppSmsManager();
        lenient().doReturn(mIccSmsInterfaceManager).when(mPhone).getIccSmsInterfaceManager();
        lenient().doReturn(mAccessNetworksManager).when(mPhone).getAccessNetworksManager();
        lenient().doReturn(mDataSettingsManager).when(mDataNetworkController)
                .getDataSettingsManager();
        lenient().doReturn(mDataNetworkController).when(mPhone).getDataNetworkController();
        lenient().doReturn(mDataSettingsManager).when(mPhone).getDataSettingsManager();
        lenient().doReturn(mCarrierPrivilegesTracker).when(mPhone).getCarrierPrivilegesTracker();
        lenient().doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        lenient().doReturn(mVoiceCallSessionStats).when(mPhone).getVoiceCallSessionStats();
        lenient().doReturn(mVoiceCallSessionStats).when(mImsPhone).getVoiceCallSessionStats();
        lenient().doReturn(mSmsStats).when(mPhone).getSmsStats();
        lenient().doReturn(mTelephonyAnalytics).when(mPhone).getTelephonyAnalytics();
        lenient().doReturn(mImsStats).when(mImsPhone).getImsStats();
        mIccSmsInterfaceManager.mDispatchersController = mSmsDispatchersController;
        lenient().doReturn(mLinkBandwidthEstimator).when(mPhone).getLinkBandwidthEstimator();
        lenient().doReturn(mCellIdentity).when(mPhone).getCurrentCellIdentity();
        lenient().doReturn(mCellLocation).when(mCellIdentity).asCellLocation();
        lenient().doReturn(mDataConfigManager).when(mDataNetworkController).getDataConfigManager();
        lenient().doReturn(mDataProfileManager).when(mDataNetworkController)
                .getDataProfileManager();
        lenient().doReturn(mDataRetryManager).when(mDataNetworkController).getDataRetryManager();
        lenient().doReturn(mCarrierPrivilegesTracker).when(mPhone).getCarrierPrivilegesTracker();
        lenient().doReturn(0).when(mPhone).getPhoneId();
        lenient().doReturn(1).when(mPhone2).getPhoneId();
        lenient().doReturn(true).when(mPhone).hasCalling();
        lenient().doReturn(true).when(mPhone2).hasCalling();

        //mUiccController
        lenient().doReturn(mUiccCardApplication3gpp).when(mUiccController).getUiccCardApplication(
                anyInt(), eq(UiccController.APP_FAM_3GPP));
        lenient().doReturn(mUiccCardApplication3gpp2).when(mUiccController).getUiccCardApplication(
                anyInt(), eq(UiccController.APP_FAM_3GPP2));
        lenient().doReturn(mUiccCardApplicationIms).when(mUiccController).getUiccCardApplication(
                anyInt(), eq(UiccController.APP_FAM_IMS));
        lenient().doReturn(mUiccCard).when(mUiccController).getUiccCard(anyInt());
        lenient().doReturn(mUiccPort).when(mUiccController).getUiccPort(anyInt());

        lenient().doAnswer(new Answer<IccRecords>() {
            public IccRecords answer(InvocationOnMock invocation) {
                switch ((Integer) invocation.getArguments()[1]) {
                    case UiccController.APP_FAM_3GPP:
                        return mSimRecords;
                    case UiccController.APP_FAM_3GPP2:
                        return mRuimRecords;
                    case UiccController.APP_FAM_IMS:
                        return mIsimUiccRecords;
                    default:
                        logd("Unrecognized family " + invocation.getArguments()[1]);
                        return null;
                }
            }
        }).when(mUiccController).getIccRecords(anyInt(), anyInt());
        lenient().doReturn(new UiccSlot[] {mUiccSlot}).when(mUiccController).getUiccSlots();
        lenient().doReturn(mUiccSlot).when(mUiccController).getUiccSlotForPhone(anyInt());
        lenient().doReturn(mPinStorage).when(mUiccController).getPinStorage();

        //UiccCardApplication
        lenient().doReturn(mSimRecords).when(mUiccCardApplication3gpp).getIccRecords();
        lenient().doReturn(mRuimRecords).when(mUiccCardApplication3gpp2).getIccRecords();
        lenient().doReturn(mIsimUiccRecords).when(mUiccCardApplicationIms).getIccRecords();

        //mUiccProfile
        lenient().doReturn(mSimRecords).when(mUiccProfile).getIccRecords();
        lenient().doAnswer(new Answer<IccRecords>() {
            public IccRecords answer(InvocationOnMock invocation) {
                return mSimRecords;
            }
        }).when(mUiccProfile).getIccRecords();

        //mUiccProfile
        lenient().doReturn(mUiccCardApplication3gpp).when(mUiccProfile).getApplication(
                eq(UiccController.APP_FAM_3GPP));
        lenient().doReturn(mUiccCardApplication3gpp2).when(mUiccProfile).getApplication(
                eq(UiccController.APP_FAM_3GPP2));
        lenient().doReturn(mUiccCardApplicationIms).when(mUiccProfile).getApplication(
                eq(UiccController.APP_FAM_IMS));

        //SMS
        lenient().doReturn(true).when(mSmsStorageMonitor).isStorageAvailable();
        lenient().doReturn(true).when(mSmsUsageMonitor).check(nullable(String.class), anyInt());
        lenient().doReturn(true).when(mTelephonyManager).getSmsReceiveCapableForPhone(anyInt(),
                anyBoolean());
        lenient().doReturn(true).when(mTelephonyManager).getSmsSendCapableForPhone(
                anyInt(), anyBoolean());

        //Misc
        lenient().doReturn(ServiceState.RIL_RADIO_TECHNOLOGY_LTE).when(mServiceState)
                .getRilDataRadioTechnology();
        lenient().doReturn(new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_LTE,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE, false, false, false))
                .when(mDisplayInfoController).getTelephonyDisplayInfo();
        lenient().doReturn(mPhone).when(mCT).getPhone();
        lenient().doReturn(mImsEcbm).when(mImsManager).getEcbmInterface();
        lenient().doReturn(mPhone).when(mInboundSmsHandler).getPhone();
        Mockito.when(mInboundSmsHandler.getTextClassifier()).thenReturn(mTextClassifier);
        lenient().doReturn(mImsCallProfile).when(mImsCall).getCallProfile();
        lenient().doReturn(mIBinder).when(mIIntentSender).asBinder();
        doAnswer(invocation -> {
            Intent[] intents = invocation.getArgument(6);
            if (intents != null && intents.length > 0) {
                lenient().doReturn(intents[0]).when(mIActivityManager)
                        .getIntentForIntentSender(mIIntentSender);
            }
            return mIIntentSender;
        }).when(mIActivityManager).getIntentSenderWithFeature(anyInt(),
                nullable(String.class), nullable(String.class), nullable(IBinder.class),
                nullable(String.class), anyInt(), nullable(Intent[].class),
                nullable(String[].class), anyInt(), nullable(Bundle.class), anyInt());
        lenient().doReturn(mTelephonyManager).when(mTelephonyManager)
                .createForSubscriptionId(anyInt());
        lenient().doReturn(true).when(mTelephonyManager).isDataCapable();

        mContextFixture.addSystemFeature(PackageManager.FEATURE_TELECOM);
        mContextFixture.addSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING);
        mContextFixture.addSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA);
        mContextFixture.addSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC);
        mContextFixture.addSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING);

        lenient().doReturn(TelephonyManager.PHONE_TYPE_GSM).when(mTelephonyManager).getPhoneType();
        lenient().doReturn(mServiceState).when(mSST).getServiceState();
        lenient().doReturn(mServiceStateStats).when(mSST).getServiceStateStats();
        mSST.mSS = mServiceState;
        mSST.mRestrictedState = mRestrictedState;
        mServiceManagerMockedServices.put("connectivity_metrics_logger", mConnMetLoggerBinder);
        mServiceManagerMockedServices.put("package", mMockPackageManager);
        mServiceManagerMockedServices.put("legacy_permission", mMockLegacyPermissionManager);
        logd("mMockLegacyPermissionManager replaced");
        lenient().doReturn(new int[]{AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN})
                .when(mAccessNetworksManager).getAvailableTransports();
        lenient().doReturn(true).when(mDataSettingsManager).isDataEnabled();
        lenient().doReturn(mNetworkRegistrationInfo).when(mServiceState).getNetworkRegistrationInfo(
                anyInt(), anyInt());
        lenient().doReturn(RIL.RADIO_HAL_VERSION_2_0).when(mPhone).getHalVersion(anyInt());
        lenient().doReturn(2).when(mSignalStrength).getLevel();
        lenient().doReturn(mMockRadioConfigProxy).when(mMockRadioConfig).getRadioConfigProxy(any());

        // WiFi
        lenient().doReturn(mWifiInfo).when(mWifiManager).getConnectionInfo();
        lenient().doReturn(2).when(mWifiManager).calculateSignalLevel(anyInt());
        lenient().doReturn(4).when(mWifiManager).getMaxSignalLevel();

        lenient().doAnswer(invocation -> {
            NetworkCapabilities nc = invocation.getArgument(0);
            return new VcnNetworkPolicyResult(
                    false /* isTearDownRequested */, nc);
        }).when(mVcnManager).applyVcnNetworkPolicy(any(), any());

        //SIM
        lenient().doReturn(1).when(mTelephonyManager).getSimCount();
        lenient().doReturn(1).when(mTelephonyManager).getPhoneCount();
        lenient().doReturn(1).when(mTelephonyManager).getActiveModemCount();
        // Have getMaxPhoneCount always return the same value with getPhoneCount by default.
        lenient().doAnswer((invocation)->Math.max(mTelephonyManager.getActiveModemCount(),
                mTelephonyManager.getPhoneCount()))
                .when(mTelephonyManager).getSupportedModemCount();
        lenient().doReturn(mStatsManager).when(mContext)
                .getSystemService(eq(Context.NETWORK_STATS_SERVICE));

        //Data
        //Initial state is: userData enabled, provisioned.
        ContentResolver resolver = mContext.getContentResolver();
        Settings.Global.putInt(resolver, Settings.Global.MOBILE_DATA, 1);
        Settings.Global.putInt(resolver, Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Global.putInt(resolver,
                Settings.Global.DEVICE_PROVISIONING_MOBILE_DATA_ENABLED, 1);
        Settings.Global.putInt(resolver, Settings.Global.DATA_ROAMING, 0);

        lenient().doReturn(90).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_EIMS));
        lenient().doReturn(80).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_SUPL));
        lenient().doReturn(70).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_MMS));
        lenient().doReturn(70).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_XCAP));
        lenient().doReturn(50).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_CBS));
        lenient().doReturn(50).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_MCX));
        lenient().doReturn(50).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_FOTA));
        lenient().doReturn(40).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_IMS));
        lenient().doReturn(30).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_DUN));
        lenient().doReturn(20).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_ENTERPRISE));
        lenient().doReturn(20).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        lenient().doReturn(60000).when(mDataConfigManager).getAnomalyNetworkConnectingTimeoutMs();
        lenient().doReturn(60000).when(mDataConfigManager)
                .getAnomalyNetworkDisconnectingTimeoutMs();
        lenient().doReturn(60000).when(mDataConfigManager).getNetworkHandoverTimeoutMs();
        lenient().doReturn(new DataConfigManager.EventFrequency(300000, 12))
                .when(mDataConfigManager).getAnomalySetupDataCallThreshold();
        lenient().doReturn(new DataConfigManager.EventFrequency(0, 2))
                .when(mDataConfigManager).getAnomalyImsReleaseRequestThreshold();
        lenient().doReturn(new DataConfigManager.EventFrequency(300000, 12))
                .when(mDataConfigManager).getAnomalyNetworkUnwantedThreshold();

        // CellularNetworkValidator
        lenient().doReturn(SubscriptionManager.INVALID_PHONE_INDEX)
                .when(mCellularNetworkValidator).getSubIdInValidation();
        lenient().doReturn(true).when(mCellularNetworkValidator).isValidationFeatureSupported();

        // Metrics
        lenient().doReturn(null).when(mContext).getFileStreamPath(anyString());
        lenient().doReturn(mPersistAtomsStorage).when(mMetricsCollector).getAtomsStorage();
        lenient().doReturn(mDefaultNetworkMonitor).when(mMetricsCollector)
                .getDefaultNetworkMonitor();
        lenient().doReturn(mWifiManager).when(mContext).getSystemService(eq(Context.WIFI_SERVICE));
        lenient().doReturn(mDeviceStateHelper).when(mMetricsCollector).getDeviceStateHelper();
        lenient().doReturn(CELLULAR_SERVICE_STATE__FOLD_STATE__STATE_UNKNOWN)
                .when(mDeviceStateHelper)
                .getFoldState();
        lenient().doReturn(null).when(mContext).getSystemService(eq(Context.DEVICE_STATE_SERVICE));

        lenient().doReturn(false).when(mDomainSelectionResolver).isDomainSelectionSupported();
        DomainSelectionResolver.setDomainSelectionResolver(mDomainSelectionResolver);

        //Use reflection to mock singletons
        replaceInstance(CallManager.class, "INSTANCE", null, mCallManager);
        replaceInstance(TelephonyComponentFactory.class, "sInstance", null,
                mTelephonyComponentFactory);
        replaceInstance(UiccController.class, "mInstance", null, mUiccController);
        replaceInstance(SubscriptionManagerService.class, "sInstance", null,
                mSubscriptionManagerService);
        replaceInstance(ProxyController.class, "sProxyController", null, mProxyController);
        replaceInstance(PhoneSwitcher.class, "sPhoneSwitcher", null, mPhoneSwitcher);
        replaceInstance(ActivityManager.class, "IActivityManagerSingleton", null,
                mIActivityManagerSingleton);
        replaceInstance(SimulatedCommandsVerifier.class, "sInstance", null,
                mSimulatedCommandsVerifier);
        replaceInstance(Singleton.class, "mInstance", mIActivityManagerSingleton,
                mIActivityManager);
        replaceInstance(ServiceManager.class, "sCache", null, mServiceManagerMockedServices);
        replaceInstance(IntentBroadcaster.class, "sIntentBroadcaster", null, mIntentBroadcaster);
        replaceInstance(TelephonyManager.class, "sInstance", null,
                mContext.getSystemService(Context.TELEPHONY_SERVICE));
        replaceInstance(TelephonyManager.class, "sServiceHandleCacheEnabled", null, false);
        replaceInstance(PhoneFactory.class, "sMadeDefaults", null, true);
        replaceInstance(PhoneFactory.class, "sPhone", null, mPhone);
        replaceInstance(PhoneFactory.class, "sPhones", null, mPhones);
        replaceInstance(RadioConfig.class, "sRadioConfig", null, mMockRadioConfig);
        replaceInstance(PhoneConfigurationManager.class, "sInstance", null,
                mPhoneConfigurationManager);
        replaceInstance(CellularNetworkValidator.class, "sInstance", null,
                mCellularNetworkValidator);
        replaceInstance(MultiSimSettingController.class, "sInstance", null,
                mMultiSimSettingController);
        replaceInstance(PhoneFactory.class, "sCommandsInterfaces", null,
                new CommandsInterface[] {mSimulatedCommands});
        replaceInstance(PhoneFactory.class, "sMetricsCollector", null, mMetricsCollector);
        replaceInstance(SatelliteController.class, "sInstance", null, mSatelliteController);

        setReady(false);
        // create default TestableLooper for test and add to list of monitored loopers
        mTestableLooper = TestableLooper.get(TelephonyTest.this);
        if (mTestableLooper != null) {
            monitorTestableLooper(mTestableLooper);
        }
    }

    protected void tearDown() throws Exception {
        // Clear all remaining messages
        if (!mTestableLoopers.isEmpty()) {
            for (TestableLooper looper : mTestableLoopers) {
                looper.getLooper().quit();
            }
        }
        // Ensure there are no references to handlers between tests.
        PhoneConfigurationManager.unregisterAllMultiSimConfigChangeRegistrants();
        // unmonitor TestableLooper for TelephonyTest class
        if (mTestableLooper != null) {
            unmonitorTestableLooper(mTestableLooper);
        }
        // destroy all newly created TestableLoopers so they can be reused
        for (TestableLooper looper : mTestableLoopers) {
            looper.destroy();
        }
        TestableLooper.remove(TelephonyTest.this);

        if (mSimulatedCommands != null) {
            mSimulatedCommands.dispose();
        }
        if (mContext != null) {
            SharedPreferences sharedPreferences = mContext.getSharedPreferences((String) null, 0);
            if (sharedPreferences != null) {
                sharedPreferences.edit().clear().commit();
            }
        }
        restoreInstances();
        TelephonyManager.enableServiceHandleCaching();

        mNetworkRegistrationInfo = null;
        mActivityManager = null;
        mImsCallProfile = null;
        mTelephonyManager = null;
        mTelephonyRegistryManager = null;
        mSubscriptionManager = null;
        mEuiccManager = null;
        mPackageManager = null;
        mConnectivityManager = null;
        mAppOpsManager = null;
        mCarrierConfigManager = null;
        mUserManager = null;
        mKeyguardManager = null;
        mVcnManager = null;
        mNetworkPolicyManager = null;
        mSimulatedCommands = null;
        mContextFixture = null;
        mContext = null;
        mFakeBlockedNumberContentProvider = null;
        mServiceManagerMockedServices.clear();
        mServiceManagerMockedServices = null;
        mPhone = null;
        mTestableLoopers.clear();
        mTestableLoopers = null;
        mTestableLooper = null;
        DomainSelectionResolver.setDomainSelectionResolver(null);
    }

    protected static void logd(String s) {
        Log.d(TAG, s);
    }

    protected void unmockActivityManager() throws Exception {
        // Normally, these two should suffice. But we're having some flakiness due to restored
        // instances being mocks...
        restoreInstance(Singleton.class, "mInstance", mIActivityManagerSingleton);
        restoreInstance(ActivityManager.class, "IActivityManagerSingleton", null);

        // Copy-paste from android.app.ActivityManager.IActivityManagerSingleton
        Singleton<IActivityManager> amSingleton = new Singleton<IActivityManager>() {
                @Override
                protected IActivityManager create() {
                    final IBinder b = ServiceManager.getService(Context.ACTIVITY_SERVICE);
                    final IActivityManager am = IActivityManager.Stub.asInterface(b);
                    return am;
                }
            };

        // ...so we're setting correct values explicitly, to be sure and not let the flake propagate
        // to other tests.
        replaceInstance(Singleton.class, "mInstance", mIActivityManagerSingleton, null);
        replaceInstance(ActivityManager.class, "IActivityManagerSingleton", null, amSingleton);
    }

    public static class FakeBlockedNumberContentProvider extends MockContentProvider {
        public Set<String> mBlockedNumbers = new HashSet<>();
        public int mNumEmergencyContactNotifications = 0;

        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            switch (method) {
                case BlockedNumberContract.SystemContract.METHOD_SHOULD_SYSTEM_BLOCK_NUMBER:
                    Bundle bundle = new Bundle();
                    int blockStatus = mBlockedNumbers.contains(arg)
                            ? BlockedNumberContract.STATUS_BLOCKED_IN_LIST
                            : BlockedNumberContract.STATUS_NOT_BLOCKED;
                    bundle.putInt(BlockedNumberContract.RES_BLOCK_STATUS, blockStatus);
                    return bundle;
                case BlockedNumberContract.SystemContract.METHOD_NOTIFY_EMERGENCY_CONTACT:
                    mNumEmergencyContactNotifications++;
                    return new Bundle();
                default:
                    fail("Method not expected: " + method);
            }
            return null;
        }
    }

    public static class FakeSettingsConfigProvider extends MockContentProvider {
        private static final String PROPERTY_DEVICE_IDENTIFIER_ACCESS_RESTRICTIONS_DISABLED =
                DeviceConfig.NAMESPACE_PRIVACY + "/"
                        + "device_identifier_access_restrictions_disabled";
        private HashMap<String, String> mFlags = new HashMap<>();

        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            logd("FakeSettingsConfigProvider: call called,  method: " + method +
                    " request: " + arg + ", args=" + extras);
            Bundle bundle = new Bundle();
            switch (method) {
                case Settings.CALL_METHOD_GET_CONFIG: {
                    switch (arg) {
                        case PROPERTY_DEVICE_IDENTIFIER_ACCESS_RESTRICTIONS_DISABLED: {
                            bundle.putString(
                                    PROPERTY_DEVICE_IDENTIFIER_ACCESS_RESTRICTIONS_DISABLED,
                                    "0");
                            return bundle;
                        }
                        default: {
                            fail("arg not expected: " + arg);
                        }
                    }
                    break;
                }
                case Settings.CALL_METHOD_LIST_CONFIG:
                    logd("LIST_config: " + mFlags);
                    Bundle result = new Bundle();
                    result.putSerializable(Settings.NameValueTable.VALUE, mFlags);
                    return result;
                case Settings.CALL_METHOD_SET_ALL_CONFIG:
                    mFlags = (extras != null)
                            ? (HashMap) extras.getSerializable(Settings.CALL_METHOD_FLAGS_KEY)
                            : new HashMap<>();
                    bundle.putInt(Settings.KEY_CONFIG_SET_ALL_RETURN,
                            Settings.SET_ALL_RESULT_SUCCESS);
                    return bundle;
                default:
                    fail("Method not expected: " + method);
            }
            return null;
        }
    }

    protected void setupMockPackagePermissionChecks() throws Exception {
        doReturn(new String[]{TAG}).when(mPackageManager).getPackagesForUid(anyInt());
        doReturn(mPackageInfo).when(mPackageManager).getPackageInfo(eq(TAG), anyInt());
    }

    protected void setupMocksForTelephonyPermissions() throws Exception {
        setupMocksForTelephonyPermissions(Build.VERSION_CODES.Q);
    }

    protected void setupMocksForTelephonyPermissions(int targetSdkVersion)
            throws Exception {
        // If the calling package does not meet the new requirements for device identifier access
        // TelephonyPermissions will query the PackageManager for the ApplicationInfo of the package
        // to determine the target SDK. For apps targeting Q a SecurityException is thrown
        // regardless of if the package satisfies the previous requirements for device ID access.

        // Any tests that query for SubscriptionInfo objects will trigger a phone number access
        // check that will first query the ApplicationInfo as apps targeting R+ can no longer
        // access the phone number with the READ_PHONE_STATE permission and instead must meet one of
        // the other requirements. This ApplicationInfo is generalized to any package name since
        // some tests will simulate invocation from other packages.
        mApplicationInfo.targetSdkVersion = targetSdkVersion;
        doReturn(mApplicationInfo).when(mPackageManager).getApplicationInfoAsUser(anyString(),
                anyInt(), any());

        // TelephonyPermissions uses a SystemAPI to check if the calling package meets any of the
        // generic requirements for device identifier access (currently READ_PRIVILEGED_PHONE_STATE,
        // appop, and device / profile owner checks). This sets up the PermissionManager to return
        // that access requirements are met.
        setIdentifierAccess(true);
        LegacyPermissionManager legacyPermissionManager =
                new LegacyPermissionManager(mMockLegacyPermissionManager);
        doReturn(legacyPermissionManager).when(mContext)
                .getSystemService(Context.LEGACY_PERMISSION_SERVICE);
        // Also make sure all appop checks fails, to not interfere tests. Tests should explicitly
        // mock AppOpManager to return allowed/default mode. Note by default a mock returns 0 which
        // is MODE_ALLOWED, hence this setup is necessary.
        doReturn(AppOpsManager.MODE_IGNORED).when(mAppOpsManager).noteOpNoThrow(
                /* op= */ anyString(), /* uid= */ anyInt(),
                /* packageName= */ nullable(String.class),
                /* attributionTag= */ nullable(String.class),
                /* message= */ nullable(String.class));

        // TelephonyPermissions queries DeviceConfig to determine if the identifier access
        // restrictions should be enabled; this results in a NPE when DeviceConfig uses
        // Activity.currentActivity.getContentResolver as the resolver for Settings.Config.getString
        // since the IContentProvider in the NameValueCache's provider holder is null.
        replaceContentProvider(new FakeSettingsConfigProvider());
    }

    private void replaceContentProvider(ContentProvider contentProvider) throws Exception {
        Class c = Class.forName("android.provider.Settings$Config");
        Field field = c.getDeclaredField("sNameValueCache");
        field.setAccessible(true);
        Object cache = field.get(null);

        c = Class.forName("android.provider.Settings$NameValueCache");
        field = c.getDeclaredField("mProviderHolder");
        field.setAccessible(true);
        Object providerHolder = field.get(cache);

        field = MockContentProvider.class.getDeclaredField("mIContentProvider");
        field.setAccessible(true);
        Object iContentProvider = field.get(contentProvider);

        replaceInstance(Class.forName("android.provider.Settings$ContentProviderHolder"),
                "mContentProvider", providerHolder, iContentProvider);
    }

    protected void setIdentifierAccess(boolean hasAccess) {
        doReturn(hasAccess ? PackageManager.PERMISSION_GRANTED
                : PackageManager.PERMISSION_DENIED).when(mMockLegacyPermissionManager)
                .checkDeviceIdentifierAccess(any(), any(), any(), anyInt(), anyInt());
    }

    protected void setPhoneNumberAccess(int value) {
        doReturn(value).when(mMockLegacyPermissionManager).checkPhoneNumberAccess(any(), any(),
                any(), anyInt(), anyInt());
    }

    protected void setCarrierPrivileges(boolean hasCarrierPrivileges) {
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        doReturn(hasCarrierPrivileges ? TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS
                : TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS).when(
                mTelephonyManager).getCarrierPrivilegeStatus(anyInt());
    }

    protected void setCarrierPrivilegesForSubId(boolean hasCarrierPrivileges, int subId) {
        TelephonyManager mockTelephonyManager = Mockito.mock(TelephonyManager.class);
        doReturn(mockTelephonyManager).when(mTelephonyManager).createForSubscriptionId(subId);
        doReturn(hasCarrierPrivileges ? TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS
                : TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS).when(
                mockTelephonyManager).getCarrierPrivilegeStatus(anyInt());
    }

    protected final void waitForDelayedHandlerAction(Handler h, long delayMillis,
            long timeoutMillis) {
        final CountDownLatch lock = new CountDownLatch(1);
        h.postDelayed(lock::countDown, delayMillis);
        while (lock.getCount() > 0) {
            try {
                lock.await(delayMillis + timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // do nothing
            }
        }
    }

    protected final void waitForHandlerAction(Handler h, long timeoutMillis) {
        final CountDownLatch lock = new CountDownLatch(1);
        h.post(lock::countDown);
        while (lock.getCount() > 0) {
            try {
                lock.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // do nothing
            }
        }
    }

    /**
     * Wait for up to 1 second for the handler message queue to clear.
     */
    protected final void waitForLastHandlerAction(Handler h) {
        CountDownLatch lock = new CountDownLatch(1);
        // Allow the handler to start work on stuff.
        h.postDelayed(lock::countDown, 100);
        int timeoutCount = 0;
        while (timeoutCount < 5) {
            try {
                if (lock.await(200, TimeUnit.MILLISECONDS)) {
                    // no messages in queue, stop waiting.
                    if (!h.hasMessagesOrCallbacks()) break;
                    lock = new CountDownLatch(1);
                    // Delay to allow the handler thread to start work on stuff.
                    h.postDelayed(lock::countDown, 100);
                }

            } catch (InterruptedException e) {
                // do nothing
            }
            timeoutCount++;
        }
        assertTrue("Handler was not empty before timeout elapsed", timeoutCount < 5);
    }

    protected final EmergencyNumber getTestEmergencyNumber() {
        return SAMPLE_EMERGENCY_NUMBER;
    }

    public static Object invokeMethod(
            Object instance, String methodName, Class<?>[] parameterClasses, Object[] parameters) {
        try {
            Method method = instance.getClass().getDeclaredMethod(methodName, parameterClasses);
            method.setAccessible(true);
            return method.invoke(instance, parameters);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            fail(instance.getClass() + " " + methodName + " " + e.getClass().getName());
        }
        return null;
    }

    /**
     * Add a TestableLooper to the list of monitored loopers
     * @param looper added if it doesn't already exist
     */
    public void monitorTestableLooper(TestableLooper looper) {
        if (!mTestableLoopers.contains(looper)) {
            mTestableLoopers.add(looper);
        }
    }

    /**
     * Remove a TestableLooper from the list of monitored loopers
     * @param looper removed if it does exist
     */
    private void unmonitorTestableLooper(TestableLooper looper) {
        if (mTestableLoopers.contains(looper)) {
            mTestableLoopers.remove(looper);
        }
    }

    /**
     * Handle all messages that can be processed at the current time
     * for all monitored TestableLoopers
     */
    public void processAllMessages() {
        if (mTestableLoopers.isEmpty()) {
            fail("mTestableLoopers is empty. Please make sure to add @RunWithLooper annotation");
        }
        while (!areAllTestableLoopersIdle()) {
            for (TestableLooper looper : mTestableLoopers) looper.processAllMessages();
        }
    }

    /**
     * @return {@code true} if there are any messages in the queue.
     */
    private boolean messagesExist() {
        for (TestableLooper looper : mTestableLoopers) {
            if (looper.peekWhen() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handle all messages including the delayed messages.
     */
    public void processAllFutureMessages() {
        final long now = SystemClock.uptimeMillis();
        while (messagesExist()) {
            for (TestableLooper looper : mTestableLoopers) {
                long nextDelay = looper.peekWhen() - now;
                if (nextDelay > 0) {
                    looper.moveTimeForward(nextDelay);
                }
            }
            processAllMessages();
        }
    }

    /**
     * Check if there are any messages to be processed in any monitored TestableLooper
     * Delayed messages to be handled at a later time will be ignored
     * @return true if there are no messages that can be handled at the current time
     *         across all monitored TestableLoopers
     */
    private boolean areAllTestableLoopersIdle() {
        for (TestableLooper looper : mTestableLoopers) {
            if (!looper.getLooper().getQueue().isIdle()) return false;
        }
        return true;
    }

    /**
     * Effectively moves time forward by reducing the time of all messages
     * for all monitored TestableLoopers
     * @param milliSeconds number of milliseconds to move time forward by
     */
    public void moveTimeForward(long milliSeconds) {
        for (TestableLooper looper : mTestableLoopers) {
            looper.moveTimeForward(milliSeconds);
        }
    }
}
