/*
 * Copyright (C) 2014 The OmniROM Project <http://www.omnirom.org>
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
import android.os.AsyncResult;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccController;

public class MediaTekRIL extends RIL implements CommandsInterface {

    // MediaTek Custom States
    static final int RIL_REQUEST_GET_CELL_BROADCAST_CONFIG = 10002;

    static final int RIL_REQUEST_SEND_ENCODED_USSD = 10005;
    static final int RIL_REQUEST_SET_PDA_MEMORY_STATUS = 10006;
    static final int RIL_REQUEST_GET_PHONEBOOK_STORAGE_INFO = 10007;

    static final int RIL_UNSOL_RELEASE_COMPLETE_MESSAGE = 11001;
    static final int RIL_UNSOL_DUN_CALL_STATUS = 11004;

    // TODO: Support multiSIM
    // Sim IDs are 0 / 1
    int mSimId = 0;


    public MediaTekRIL(Context context, int networkMode, int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription);
    }

    public static byte[] hexStringToBytes(String s) {
        byte[] ret;

        if (s == null) return null;

        int len = s.length();
        ret = new byte[len/2];

        for (int i=0 ; i <len ; i+=2) {
            ret[i/2] = (byte) ((hexCharToInt(s.charAt(i)) << 4)
                                | hexCharToInt(s.charAt(i+1)));
        }

        return ret;
    }

    static int hexCharToInt(char c) {
         if (c >= '0' && c <= '9') return (c - '0');
         if (c >= 'A' && c <= 'F') return (c - 'A' + 10);
         if (c >= 'a' && c <= 'f') return (c - 'a' + 10);

         throw new RuntimeException ("invalid hex char '" + c + "'");
    }

    protected Object
    responseOperatorInfos(Parcel p) {
        String strings[] = (String [])responseStrings(p);
        ArrayList<OperatorInfo> ret;

        if (strings.length % 5 != 0) {
            throw new RuntimeException(
                "RIL_REQUEST_QUERY_AVAILABLE_NETWORKS: invalid response. Got "
                + strings.length + " strings, expected multible of 5");
        }

        String lacStr = SystemProperties.get("gsm.cops.lac");
        boolean lacValid = false;
        int lacIndex=0;

        Rlog.d(LOG_TAG, "lacStr = " + lacStr+" lacStr.length="+lacStr.length()+" strings.length="+strings.length);
        if((lacStr.length() > 0) && (lacStr.length()%4 == 0) && ((lacStr.length()/4) == (strings.length/5 ))){
            Rlog.d(LOG_TAG, "lacValid set to true");
            lacValid = true;
        }

        SystemProperties.set("gsm.cops.lac","");

        ret = new ArrayList<OperatorInfo>(strings.length / 5);

        for (int i = 0 ; i < strings.length ; i += 5) {
            if((strings[i+0] != null) && (strings[i+0].startsWith("uCs2") == true)) {        
                riljLog("responseOperatorInfos handling UCS2 format name");

                try {
                    strings[i+0] = new String(hexStringToBytes(strings[i+0].substring(4)), "UTF-16");
                } catch(UnsupportedEncodingException ex) {
                    riljLog("responseOperatorInfos UnsupportedEncodingException");
                }
            }

            if ((lacValid == true) && (strings[i] != null)) {
                UiccController uiccController = UiccController.getInstance();
                IccRecords iccRecords = uiccController.getIccRecords(UiccController.APP_FAM_3GPP);
                int lacValue = -1;
                String sEons = null;
                String lac = lacStr.substring(lacIndex,lacIndex+4);
                Rlog.d(LOG_TAG, "lacIndex="+lacIndex+" lacValue="+lacValue+" lac="+lac+" plmn numeric="+strings[i+2]+" plmn name"+strings[i+0]);

                if(lac != "") {
                    lacValue = Integer.parseInt(lac, 16);
                    lacIndex += 4;
                    if(lacValue != 0xfffe) {
                        /*sEons = iccRecords.getEonsIfExist(strings[i+2],lacValue,true);
                        if(sEons != null) {
                            strings[i] = sEons;           
                            Rlog.d(LOG_TAG, "plmn name update to Eons: "+strings[i]);
                        }*/
                    } else {
                        Rlog.d(LOG_TAG, "invalid lac ignored");
                    }
                }
            }

            if (strings[i] != null && (strings[i].equals("") || strings[i].equals(strings[i+2]))) {
                riljLog("lookup RIL responseOperatorInfos()");
                // Operator MCC/MNC is given here for the current simcard. Too lazy to convert for now.
                strings[i] = strings[i+2]; // long name
                strings[i+1] = strings[i+2]; // short name
            }

            // 1, 2 = 2G
            // > 2 = 3G
            String property_name = "gsm.baseband.capability";
            if(mSimId > 0) {
                property_name = property_name + (mSimId+1);
            }

            int basebandCapability = SystemProperties.getInt(property_name, 3);
            Rlog.d(LOG_TAG, "property_name="+property_name+", basebandCapability=" + basebandCapability);
            if (3 < basebandCapability) {
                strings[i+0] = strings[i+0].concat(" " + strings[i+4]);
                strings[i+1] = strings[i+1].concat(" " + strings[i+4]);
            }

            ret.add(
                new OperatorInfo(
                    strings[i+0],
                    strings[i+1],
                    strings[i+2],
                    strings[i+3]));
        }

        return ret;
    }

}
