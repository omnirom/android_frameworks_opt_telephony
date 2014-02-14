/*
 * Copyright (C) 2012-2013 The CyanogenMod Project
 * Copyright (C) 2013 The OmniROM Project
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

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.media.AudioManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.telephony.SmsMessage;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.telephony.Rlog;

import android.telephony.SignalStrength;

import android.telephony.PhoneNumberUtils;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.cdma.CdmaInformationRecords;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaSignalInfoRec;
import com.android.internal.telephony.cdma.SignalToneUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardStatus;

/**
 * Qualcomm RIL for the Samsung family.
 * Quad core Exynos4 with Qualcomm modem and later is supported
 * Snapdragon S3 and later is supported
 * This RIL is univerisal meaning it supports CDMA and GSM radio.
 * Handles most GSM and CDMA cases.
 * {@hide}
 */
public class SamsungQualcommRIL extends QualcommMSIM42RIL implements CommandsInterface {

    private AudioManager mAudioManager;

    private Object mSMSLock = new Object();
    private boolean mIsSendingSMS = false;
    private boolean isGSM = false;
    private boolean passedCheck=true;
    public static final long SEND_SMS_TIMEOUT_IN_MS = 30000;
    private String homeOperator= SystemProperties.get("ro.cdma.home.operator.numeric");
    private String operator= SystemProperties.get("ro.cdma.home.operator.alpha");
    private boolean oldRilState = needsOldRilFeature("exynos4RadioState");
    private boolean googleEditionSS = needsOldRilFeature("googleEditionSS");
    private boolean driverCall = needsOldRilFeature("newDriverCall");
    private boolean dialCode = needsOldRilFeature("newDialCode");
    private String[] lastKnownOfGood = {null, null, null};
    public SamsungQualcommRIL(Context context, int networkMode,
            int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription);
        mAudioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    protected Object
    responseIccCardStatus(Parcel p) {
        IccCardApplicationStatus appStatus;

        IccCardStatus cardStatus = new IccCardStatus();
        cardStatus.setCardState(p.readInt());
        cardStatus.setUniversalPinState(p.readInt());
        cardStatus.mGsmUmtsSubscriptionAppIndex = p.readInt();
        cardStatus.mCdmaSubscriptionAppIndex = p.readInt();
        cardStatus.mImsSubscriptionAppIndex = p.readInt();

        int numApplications = p.readInt();

        // limit to maximum allowed applications
        if (numApplications > IccCardStatus.CARD_MAX_APPS) {
            numApplications = IccCardStatus.CARD_MAX_APPS;
        }
        cardStatus.mApplications = new IccCardApplicationStatus[numApplications];
        if (numApplications==1 && !isGSM){
            cardStatus.mApplications = new IccCardApplicationStatus[numApplications+2];
        }

        appStatus = new IccCardApplicationStatus();
        for (int i = 0 ; i < numApplications ; i++) {
            appStatus = new IccCardApplicationStatus();
            appStatus.app_type       = appStatus.AppTypeFromRILInt(p.readInt());
            appStatus.app_state      = appStatus.AppStateFromRILInt(p.readInt());
            appStatus.perso_substate = appStatus.PersoSubstateFromRILInt(p.readInt());
            appStatus.aid            = p.readString();
            appStatus.app_label      = p.readString();
            appStatus.pin1_replaced  = p.readInt();
            appStatus.pin1           = appStatus.PinStateFromRILInt(p.readInt());
            appStatus.pin2           = appStatus.PinStateFromRILInt(p.readInt());
            p.readInt(); // remaining_count_pin1 - pin1_num_retries
            p.readInt(); // remaining_count_puk1 - puk1_num_retries
            p.readInt(); // remaining_count_pin2 - pin2_num_retries
            p.readInt(); // remaining_count_puk2 - puk2_num_retries
            p.readInt(); // - perso_unblock_retries
            cardStatus.mApplications[i] = appStatus;
        }
        if (numApplications==1 && !isGSM) {
            cardStatus.mCdmaSubscriptionAppIndex = 1;
            cardStatus.mImsSubscriptionAppIndex = 2;
            IccCardApplicationStatus appStatus2 = new IccCardApplicationStatus();
            appStatus2.app_type       = appStatus2.AppTypeFromRILInt(4); // csim state
            appStatus2.app_state      = appStatus.app_state;
            appStatus2.perso_substate = appStatus.perso_substate;
            appStatus2.aid            = appStatus.aid;
            appStatus2.app_label      = appStatus.app_label;
            appStatus2.pin1_replaced  = appStatus.pin1_replaced;
            appStatus2.pin1           = appStatus.pin1;
            appStatus2.pin2           = appStatus.pin2;
            cardStatus.mApplications[cardStatus.mCdmaSubscriptionAppIndex] = appStatus2;
            IccCardApplicationStatus appStatus3 = new IccCardApplicationStatus();
            appStatus3.app_type       = appStatus3.AppTypeFromRILInt(5); // ims state
            appStatus3.app_state      = appStatus.app_state;
            appStatus3.perso_substate = appStatus.perso_substate;
            appStatus3.aid            = appStatus.aid;
            appStatus3.app_label      = appStatus.app_label;
            appStatus3.pin1_replaced  = appStatus.pin1_replaced;
            appStatus3.pin1           = appStatus.pin1;
            appStatus3.pin2           = appStatus.pin2;
            cardStatus.mApplications[cardStatus.mImsSubscriptionAppIndex] = appStatus3;
        }
        return cardStatus;
    }

    @Override
    public void
    sendCdmaSms(byte[] pdu, Message result) {
        smsLock();
        super.sendCdmaSms(pdu, result);
    }

    @Override
    public void
        sendSMS (String smscPDU, String pdu, Message result) {
        smsLock();
        super.sendSMS(smscPDU, pdu, result);
    }

    private void smsLock(){
        // Do not send a new SMS until the response for the previous SMS has been received
        //   * for the error case where the response never comes back, time out after
        //     30 seconds and just try the next SEND_SMS
        synchronized (mSMSLock) {
            long timeoutTime  = SystemClock.elapsedRealtime() + SEND_SMS_TIMEOUT_IN_MS;
            long waitTimeLeft = SEND_SMS_TIMEOUT_IN_MS;
            while (mIsSendingSMS && (waitTimeLeft > 0)) {
                Rlog.d(RILJ_LOG_TAG, "sendSMS() waiting for response of previous SEND_SMS");
                try {
                    mSMSLock.wait(waitTimeLeft);
                } catch (InterruptedException ex) {
                    // ignore the interrupt and rewait for the remainder
                }
                waitTimeLeft = timeoutTime - SystemClock.elapsedRealtime();
            }
            if (waitTimeLeft <= 0) {
                Rlog.e(RILJ_LOG_TAG, "sendSms() timed out waiting for response of previous CDMA_SEND_SMS");
            }
            mIsSendingSMS = true;
        }

    }

    @Override
    protected Object responseSignalStrength(Parcel p) {
        int numInts = 12;
        int response[];

        // This is a mashup of algorithms used in
        // SamsungQualcommUiccRIL.java

        // Get raw data
        response = new int[numInts];
        for (int i = 0; i < numInts; i++) {
            response[i] = p.readInt();
        }
        //gsm
        response[0] &= 0xff; //gsmDbm

        //cdma
        // Take just the least significant byte as the signal strength
        response[2] %= 256;
        response[4] %= 256;

        // RIL_LTE_SignalStrength
        if (googleEditionSS && !isGSM){
            response[8] = response[2];
        }else if (response[7] == 99) {
            // If LTE is not enabled, clear LTE results
            // 7-11 must be -1 for GSM signal strength to be used (see
            // frameworks/base/telephony/java/android/telephony/SignalStrength.java)
            response[8] = SignalStrength.INVALID;
            response[9] = SignalStrength.INVALID;
            response[10] = SignalStrength.INVALID;
            response[11] = SignalStrength.INVALID;
        }else{ // lte is gsm on samsung/qualcomm cdma stack
            response[7] &= 0xff;
        }
        return new SignalStrength(response[0], response[1], response[2], response[3], response[4], response[5], response[6], response[7], response[8], response[9], response[10], response[11], (p.readInt() != 0));

    }

    @Override
    protected RadioState getRadioStateFromInt(int stateInt) {
        if(!oldRilState)
            super.getRadioStateFromInt(stateInt);
        RadioState state;

        /* RIL_RadioState ril.h */
        switch(stateInt) {
            case 0: state = RadioState.RADIO_OFF; break;
            case 1:
            case 2: state = RadioState.RADIO_UNAVAILABLE; break;
            case 4:
                // When SIM is PIN-unlocked, RIL doesn't respond with RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED.
                // We notify the system here.
                Rlog.d(RILJ_LOG_TAG, "SIM is PIN-unlocked now");
                if (mIccStatusChangedRegistrants != null) {
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
            case 3:
            case 5:
            case 6:
            case 7:
            case 8:
            case 9:
            case 10:
            case 13: state = RadioState.RADIO_ON; break;

            default:
                throw new RuntimeException(
                                           "Unrecognized RIL_RadioState: " + stateInt);
        }
        return state;
    }

    @Override
    public void setPhoneType(int phoneType){
        super.setPhoneType(phoneType);
        isGSM = (phoneType != RILConstants.CDMA_PHONE);
    }

    @Override
    protected Object
    responseCallList(Parcel p) {
        samsungDriverCall = (driverCall && !isGSM) || mRilVersion < 7 ? false : true;
        if(driverCall && passedCheck)
            mAudioManager.setParameters("wide_voice_enable=false");
        return super.responseCallList(p);
    }

    @Override
    protected void
    processUnsolicited (Parcel p) {
        Object ret;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        int response = p.readInt();

        switch(response) {
            case RIL_UNSOL_RIL_CONNECTED: // Fix for NV/RUIM setting on CDMA SIM devices
                // skip getcdmascriptionsource as if qualcomm handles it in the ril binary
                ret = responseInts(p);
                setRadioPower(false, null);
                setPreferredNetworkType(mPreferredNetworkType, null);
                int cdmaSubscription = Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.CDMA_SUBSCRIPTION_MODE, -1);
                if(cdmaSubscription != -1) {
                    setCdmaSubscriptionSource(mCdmaSubscription, null);
                }
                setCellInfoListRate(Integer.MAX_VALUE, null);
                notifyRegistrantsRilConnectionChanged(((int[])ret)[0]);
                break;
            case RIL_UNSOL_NITZ_TIME_RECEIVED:
                handleNitzTimeReceived(p);
                break;
            // SAMSUNG STATES
            case SamsungExynos4RIL.RIL_UNSOL_AM:
                ret = responseString(p);
                String amString = (String) ret;
                Rlog.d(RILJ_LOG_TAG, "Executing AM: " + amString);

                try {
                    Runtime.getRuntime().exec("am " + amString);
                } catch (IOException e) {
                    e.printStackTrace();
                    Rlog.e(RILJ_LOG_TAG, "am " + amString + " could not be executed.");
                }
                break;
            case SamsungExynos4RIL.RIL_UNSOL_RESPONSE_HANDOVER:
                ret = responseVoid(p);
                break;
            case 1036:
                ret = responseVoid(p);
                break;
            case SamsungExynos4RIL.RIL_UNSOL_WB_AMR_STATE:
                ret = responseInts(p);
                setWbAmr(((int[])ret)[0]);
                break;
            default:
                // Rewind the Parcel
                p.setDataPosition(dataPosition);

                // Forward responses that we are not overriding to the super class
                super.processUnsolicited(p);
                return;
        }

    }

    @Override
    protected Object getOverridenRequestResponse(int mRequest, Parcel p) {
        switch(mRequest) {
            // prevent exceptions from happenimg because the null value is null or a hexadecimel. so convert if it is not null
            case RIL_REQUEST_VOICE_REGISTRATION_STATE: return responseVoiceDataRegistrationState(p);
            case RIL_REQUEST_DATA_REGISTRATION_STATE: return responseVoiceDataRegistrationState(p);
            // this fixes bogus values the modem creates
            // sometimes the  ril may print out
            // (always on sprint)
            // sprint: (empty,empty,31000)
            // this problemaic on sprint, lte won't start, response is slow
            //speeds up response time on eherpderpd/lte networks
            case RIL_REQUEST_OPERATOR: return operatorCheck(p);
            default: return null;
        }
    }

    // CDMA FIXES, this fixes  bogus values in nv/sim on d2/jf/t0 cdma family or bogus information from sim card
    private Object
    operatorCheck(Parcel p) {
        String response[] = (String[])responseStrings(p);
        for(int i=0; i<3; i++){
            if (response[i]!= null){
                if (i<2){
                    if (response[i].equals("       Empty") || (response[i].equals("") && !isGSM)) {
                        response[i]=operator;
                    } else if (!response[i].equals(""))  {
                        try {
                            Integer.parseInt(response[i]);
                            response[i]=Operators.operatorReplace(response[i]);
                            //optimize
                            if(i==0)
                                response[i+1]=response[i];
                        }  catch(NumberFormatException E){
                            // do nothing
                        }
                    }
                } else if (response[i].equals("31000")|| response[i].equals("11111") || response[i].equals("123456") || response[i].equals("31099") || (response[i].equals("") && !isGSM)){
                        response[i]=homeOperator;
                }
                lastKnownOfGood[i]=response[i];
            }else{
                if(lastKnownOfGood[i]!=null)
                    response[i]=lastKnownOfGood[i];
            }
        }
        return response;
    }
    // handle exceptions
    private Object
    responseVoiceDataRegistrationState(Parcel p) {
        String response[] = (String[])responseStrings(p);
        if (isGSM){
            return response;
        }
        if ( response.length>=10){
            for(int i=6; i<=9; i++){
                if (response[i]== null){
                    response[i]=Integer.toString(Integer.MAX_VALUE);
                } else {
                    try {
                        Integer.parseInt(response[i]);
                    } catch(NumberFormatException e) {
                        response[i]=Integer.toString(Integer.parseInt(response[i],16));
                    }
                }
            }
        }

        return response;
    }
    // has no effect
    // for debugging purposes , just generate out anything from response
    public static String s(String a[]){
        StringBuffer result = new StringBuffer();

        for (int i = 0; i < a.length; i++) {
            result.append( a[i] );
            result.append(",");
        }
        return result.toString();
    }
    // end  of cdma fix

    /**
     * Set audio parameter "wb_amr" for HD-Voice (Wideband AMR).
     *
     * @param state: 0 = unsupported, 1 = supported.
     * REQUIRED FOR JF FAMILY THIS SETS THE INFORMATION
     * CRASHES WITHOUT THIS FUNCTION
     * part of the new csd binary
     */
    private void setWbAmr(int state) {
        if (state == 1) {
            Rlog.d(RILJ_LOG_TAG, "setWbAmr(): setting audio parameter - wb_amr=on");
            mAudioManager.setParameters("wide_voice_enable=true");
        }else if (state == 0) {
            Rlog.d(RILJ_LOG_TAG, "setWbAmr(): setting audio parameter - wb_amr=off");
            mAudioManager.setParameters("wide_voice_enable=false");
        }
        //prevent race conditions when the two meeets
        if (passedCheck)
            passedCheck=false;
    }

    // Workaround for Samsung CDMA "ring of death" bug:
    //
    // Symptom: As soon as the phone receives notice of an incoming call, an
    // audible "old fashioned ring" is emitted through the earpiece and
    // persists through the duration of the call, or until reboot if the call
    // isn't answered.
    //
    // Background: The CDMA telephony stack implements a number of "signal info
    // tones" that are locally generated by ToneGenerator and mixed into the
    // voice call path in response to radio RIL_UNSOL_CDMA_INFO_REC requests.
    // One of these tones, IS95_CONST_IR_SIG_IS54B_L, is requested by the
    // radio just prior to notice of an incoming call when the voice call
    // path is muted. CallNotifier is responsible for stopping all signal
    // tones (by "playing" the TONE_CDMA_SIGNAL_OFF tone) upon receipt of a
    // "new ringing connection", prior to unmuting the voice call path.
    //
    // Problem: CallNotifier's incoming call path is designed to minimize
    // latency to notify users of incoming calls ASAP. Thus,
    // SignalInfoTonePlayer requests are handled asynchronously by spawning a
    // one-shot thread for each. Unfortunately the ToneGenerator API does
    // not provide a mechanism to specify an ordering on requests, and thus,
    // unexpected thread interleaving may result in ToneGenerator processing
    // them in the opposite order that CallNotifier intended. In this case,
    // playing the "signal off" tone first, followed by playing the "old
    // fashioned ring" indefinitely.
    //
    // Solution: An API change to ToneGenerator is required to enable
    // SignalInfoTonePlayer to impose an ordering on requests (i.e., drop any
    // request that's older than the most recent observed). Such a change,
    // or another appropriate fix should be implemented in AOSP first.
    //
    // Workaround: Intercept RIL_UNSOL_CDMA_INFO_REC requests from the radio,
    // check for a signal info record matching IS95_CONST_IR_SIG_IS54B_L, and
    // drop it so it's never seen by CallNotifier. If other signal tones are
    // observed to cause this problem, they should be dropped here as well.
    @Override
    protected void notifyRegistrantsCdmaInfoRec(CdmaInformationRecords infoRec) {
        final int response = RIL_UNSOL_CDMA_INFO_REC;

        if (infoRec.record instanceof CdmaSignalInfoRec) {
            CdmaSignalInfoRec sir = (CdmaSignalInfoRec) infoRec.record;
            if (sir != null
                    && sir.isPresent
                    && sir.signalType == SignalToneUtil.IS95_CONST_IR_SIGNAL_IS54B
                    && sir.alertPitch == SignalToneUtil.IS95_CONST_IR_ALERT_MED
                    && sir.signal == SignalToneUtil.IS95_CONST_IR_SIG_IS54B_L) {

                Rlog.d(RILJ_LOG_TAG, "Dropping \"" + responseToString(response) + " "
                        + retToString(response, sir)
                        + "\" to prevent \"ring of death\" bug.");
                return;
            }
        }

        super.notifyRegistrantsCdmaInfoRec(infoRec);
    }

    private void
    handleNitzTimeReceived(Parcel p) {
        String nitz = (String)responseString(p);
        //if (RILJ_LOGD) unsljLogRet(RIL_UNSOL_NITZ_TIME_RECEIVED, nitz);

        // has bonus long containing milliseconds since boot that the NITZ
        // time was received
        long nitzReceiveTime = p.readLong();

        Object[] result = new Object[2];

        String fixedNitz = nitz;
        String[] nitzParts = nitz.split(",");
        if (nitzParts.length == 4) {
            // 0=date, 1=time+zone, 2=dst, 3=garbage that confuses GsmServiceStateTracker (so remove it)
            fixedNitz = nitzParts[0]+","+nitzParts[1]+","+nitzParts[2]+",";
        }

        result[0] = fixedNitz;
        result[1] = Long.valueOf(nitzReceiveTime);

        boolean ignoreNitz = SystemProperties.getBoolean(
                        TelephonyProperties.PROPERTY_IGNORE_NITZ, false);

        if (ignoreNitz) {
            if (RILJ_LOGD) riljLog("ignoring UNSOL_NITZ_TIME_RECEIVED");
        } else {
            if (mNITZTimeRegistrant != null) {
                mNITZTimeRegistrant
                .notifyRegistrant(new AsyncResult (null, result, null));
            } else {
                // in case NITZ time registrant isnt registered yet
                mLastNITZTimeInfo = result;
            }
        }
    }

    @Override
    protected Object
    responseSMS(Parcel p) {
        // Notify that sendSMS() can send the next SMS
        synchronized (mSMSLock) {
            mIsSendingSMS = false;
            mSMSLock.notify();
        }

        return super.responseSMS(p);
    }
    @Override
    public void
    dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        if(!dialCode){
            super.dial(address, clirMode, uusInfo, result);
            return;
        }
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DIAL, result);

        rr.mParcel.writeString(address);
        rr.mParcel.writeInt(clirMode);
        rr.mParcel.writeInt(0);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeString("");

        if (uusInfo == null) {
            rr.mParcel.writeInt(0); // UUS information is absent
        } else {
            rr.mParcel.writeInt(1); // UUS information is present
            rr.mParcel.writeInt(uusInfo.getType());
            rr.mParcel.writeInt(uusInfo.getDcs());
            rr.mParcel.writeByteArray(uusInfo.getUserData());
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }
}
