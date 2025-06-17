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

package com.android.internal.telephony.satellite;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.TestCase.assertFalse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.content.Context;
import android.testing.AndroidTestingRunner;

import androidx.test.InstrumentationRegistry;

import com.android.internal.telephony.TelephonyTest;

import com.google.protobuf.ByteString;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidTestingRunner.class)
public class SatelliteConfigParserTest extends TelephonyTest {
    private byte[] mBytesProtoBuffer;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);
        logd(TAG + " Setup!");

        SatelliteConfigData.TelephonyConfigProto.Builder telephonyConfigBuilder =
                SatelliteConfigData.TelephonyConfigProto.newBuilder();
        SatelliteConfigData.SatelliteConfigProto.Builder satelliteConfigBuilder =
                SatelliteConfigData.SatelliteConfigProto.newBuilder();

        // version
        satelliteConfigBuilder.setVersion(4);

        // carriersupportedservices
        SatelliteConfigData.CarrierSupportedSatelliteServicesProto.Builder
                carrierSupportedSatelliteServiceBuilder =
                        SatelliteConfigData.CarrierSupportedSatelliteServicesProto.newBuilder();

        // carriersupportedservices#carrier_id
        carrierSupportedSatelliteServiceBuilder.setCarrierId(1);

        // carriersupportedservices#providercapability
        SatelliteConfigData.SatelliteProviderCapabilityProto.Builder
                satelliteProviderCapabilityBuilder =
                        SatelliteConfigData.SatelliteProviderCapabilityProto.newBuilder();
        satelliteProviderCapabilityBuilder.setCarrierPlmn("310160");
        satelliteProviderCapabilityBuilder.addAllowedServices(1);
        satelliteProviderCapabilityBuilder.addAllowedServices(2);
        satelliteProviderCapabilityBuilder.addAllowedServices(3);
        carrierSupportedSatelliteServiceBuilder.addSupportedSatelliteProviderCapabilities(
                satelliteProviderCapabilityBuilder);
        satelliteProviderCapabilityBuilder.clear();

        satelliteProviderCapabilityBuilder.setCarrierPlmn("310220");
        satelliteProviderCapabilityBuilder.addAllowedServices(3);
        carrierSupportedSatelliteServiceBuilder.addSupportedSatelliteProviderCapabilities(
                satelliteProviderCapabilityBuilder);
        satelliteProviderCapabilityBuilder.clear();

        satelliteConfigBuilder.addCarrierSupportedSatelliteServices(
                carrierSupportedSatelliteServiceBuilder);

        // satelliteregion
        SatelliteConfigData.SatelliteRegionProto.Builder satelliteRegionBuilder =
                SatelliteConfigData.SatelliteRegionProto.newBuilder();
        String testS2Content = "0123456789", testSatelliteAccessConfigContent = "sac";
        satelliteRegionBuilder.setS2CellFile(ByteString.copyFrom(testS2Content.getBytes()));
        satelliteRegionBuilder.setSatelliteAccessConfigFile(
                ByteString.copyFrom(testSatelliteAccessConfigContent.getBytes()));
        satelliteRegionBuilder.addCountryCodes("US");
        satelliteRegionBuilder.setIsAllowed(true);
        satelliteConfigBuilder.setDeviceSatelliteRegion(satelliteRegionBuilder);

        telephonyConfigBuilder.setSatellite(satelliteConfigBuilder);

        SatelliteConfigData.TelephonyConfigProto telephonyConfigData =
                telephonyConfigBuilder.build();
        mBytesProtoBuffer = telephonyConfigData.toByteArray();
    }

    @After
    public void tearDown() throws Exception {
        logd(TAG + " tearDown");
        super.tearDown();
    }

    @Test
    public void testGetAllSatellitePlmnsForCarrier() {
        List<String> compareList_cid1 = new ArrayList<>();
        compareList_cid1.add("310160");
        compareList_cid1.add("310220");
        List<String> compareList_cid_placeholder = new ArrayList<>();
        compareList_cid_placeholder.add("310260");
        compareList_cid_placeholder.add("45060");


        SatelliteConfigParser satelliteConfigParserNull = new SatelliteConfigParser((byte[]) null);
        assertNotNull(satelliteConfigParserNull);
        assertNull(satelliteConfigParserNull.getConfig());

        SatelliteConfigParser satelliteConfigParserPlaceHolder =
                new SatelliteConfigParser((byte[]) null);
        assertNotNull(satelliteConfigParserPlaceHolder);
        assertNull(satelliteConfigParserPlaceHolder.getConfig());

        SatelliteConfigParser satelliteConfigParser = new SatelliteConfigParser(mBytesProtoBuffer);

        List<String> parsedList1 = satelliteConfigParser.getConfig()
                .getAllSatellitePlmnsForCarrier(1);
        Collections.sort(compareList_cid1);
        Collections.sort(compareList_cid_placeholder);
        Collections.sort(parsedList1);

        assertEquals(compareList_cid1, parsedList1);
        assertNotEquals(compareList_cid_placeholder, parsedList1);

        List<String> parsedList2 = satelliteConfigParser.getConfig()
                .getAllSatellitePlmnsForCarrier(0);
        assertEquals(0, parsedList2.size());
    }

    @Test
    public void testGetSupportedSatelliteServices() {
        Map<String, Set<Integer>> compareMapCarrierId1 = new HashMap<>();
        Set<Integer> compareSet310160 = new HashSet<>();
        compareSet310160.add(1);
        compareSet310160.add(2);
        compareSet310160.add(3);
        compareMapCarrierId1.put("310160", compareSet310160);

        Set<Integer> compareSet310220 = new HashSet<>();
        compareSet310220.add(3);
        compareMapCarrierId1.put("310220", compareSet310220);

        SatelliteConfigParser satelliteConfigParserNull = new SatelliteConfigParser((byte[]) null);
        assertNotNull(satelliteConfigParserNull);
        assertNull(satelliteConfigParserNull.getConfig());

        SatelliteConfigParser satelliteConfigParserPlaceholder =
                new SatelliteConfigParser("test".getBytes());
        assertNotNull(satelliteConfigParserPlaceholder);
        assertNull(satelliteConfigParserPlaceholder.getConfig());

        SatelliteConfigParser satelliteConfigParser = new SatelliteConfigParser(mBytesProtoBuffer);
        Map<String, Set<Integer>> parsedMap1 = satelliteConfigParser.getConfig()
                .getSupportedSatelliteServices(0);
        Map<String, Set<Integer>> parsedMap2 = satelliteConfigParser.getConfig()
                .getSupportedSatelliteServices(1);
        assertEquals(0, parsedMap1.size());
        assertEquals(2, parsedMap2.size());
        assertEquals(compareMapCarrierId1, parsedMap2);
    }

    @Test
    public void testGetDeviceSatelliteCountryCodes() {
        List<String> compareList_countryCodes = new ArrayList<>();
        compareList_countryCodes.add("US");
        Collections.sort(compareList_countryCodes);

        List<String> compareList_countryCodes_placeholder = new ArrayList<>();
        compareList_countryCodes_placeholder.add("US");
        compareList_countryCodes_placeholder.add("IN");
        Collections.sort(compareList_countryCodes_placeholder);

        SatelliteConfigParser satelliteConfigParserNull = new SatelliteConfigParser((byte[]) null);
        assertNotNull(satelliteConfigParserNull);
        assertNull(satelliteConfigParserNull.getConfig());

        SatelliteConfigParser satelliteConfigParser = new SatelliteConfigParser(mBytesProtoBuffer);
        List<String> tempList = satelliteConfigParser.getConfig().getDeviceSatelliteCountryCodes();
        List<String> parsedList = new ArrayList<>(tempList);
        Collections.sort(parsedList);

        assertEquals(compareList_countryCodes, parsedList);
        assertNotEquals(compareList_countryCodes_placeholder, parsedList);
    }

    @Test
    public void testGetSatelliteS2CellFile() {
        SatelliteConfigParser spySatelliteConfigParserNull = spy(
                new SatelliteConfigParser((byte[]) null));
        assertNotNull(spySatelliteConfigParserNull);
        assertNull(spySatelliteConfigParserNull.getConfig());

        SatelliteConfigParser spySatelliteConfigParserPlaceholder =
                spy(new SatelliteConfigParser("test".getBytes()));
        assertNotNull(spySatelliteConfigParserPlaceholder);
        assertNull(spySatelliteConfigParserPlaceholder.getConfig());

        SatelliteConfigParser spySatelliteConfigParser =
                spy(new SatelliteConfigParser(mBytesProtoBuffer));
        assertNotNull(spySatelliteConfigParser.getConfig());

        SatelliteConfig mockedSatelliteConfig = mock(SatelliteConfig.class);
        File mMockSatS2File = mock(File.class);
        doReturn(mMockSatS2File).when(mockedSatelliteConfig).getSatelliteS2CellFile(any());
        doReturn(mockedSatelliteConfig).when(spySatelliteConfigParser).getConfig();
        assertEquals(
                mMockSatS2File,
                spySatelliteConfigParser.getConfig().getSatelliteS2CellFile(mContext));
    }

    @Test
    public void testSatelliteS2FileParsing() {
        SatelliteConfigParser satelliteConfigParser = new SatelliteConfigParser(mBytesProtoBuffer);
        assertNotNull(satelliteConfigParser);
        assertNotNull(satelliteConfigParser.getConfig());

        Context instrumentationContext = InstrumentationRegistry.getContext();
        File actualS2File =
                satelliteConfigParser.getConfig().getSatelliteS2CellFile(instrumentationContext);
        logd("actualS2File's path: " + actualS2File.getAbsolutePath());
        assertNotNull(actualS2File);
        assertTrue(actualS2File.exists());

        // Verify the content of actualS2File
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(actualS2File);
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            String actualS2FileContent = new String(buffer);
            assertNotNull(actualS2FileContent);
            assertFalse(actualS2FileContent.isEmpty());
            assertEquals("0123456789", actualS2FileContent);
        } catch (IOException e) {
            fail("Failed to read file content: " + e.getMessage());
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                fail("Failed to close input stream: " + e.getMessage());
            }
        }
    }

    @Test
    public void testGetSatelliteAccessAllow() {
        SatelliteConfigParser satelliteConfigParserNull = new SatelliteConfigParser((byte[]) null);
        assertNotNull(satelliteConfigParserNull);
        assertNull(satelliteConfigParserNull.getConfig());

        SatelliteConfigParser satelliteConfigParserPlaceholder =
                new SatelliteConfigParser("test".getBytes());
        assertNotNull(satelliteConfigParserPlaceholder);
        assertNull(satelliteConfigParserPlaceholder.getConfig());

        SatelliteConfigParser satelliteConfigParser = new SatelliteConfigParser(mBytesProtoBuffer);
        assertNotNull(satelliteConfigParser);
        assertNotNull(satelliteConfigParser.getConfig());
        assertTrue(satelliteConfigParser.getConfig().isSatelliteDataForAllowedRegion());
    }

    @Test
    public void testGetSatelliteAccessConfigJsonFile() {
        SatelliteConfigParser spySatelliteConfigParserNull =
                spy(new SatelliteConfigParser((byte[]) null));
        assertNotNull(spySatelliteConfigParserNull);
        assertNull(spySatelliteConfigParserNull.getConfig());

        SatelliteConfigParser spySatelliteConfigParserPlaceholder =
                spy(new SatelliteConfigParser("test".getBytes()));
        assertNotNull(spySatelliteConfigParserPlaceholder);
        assertNull(spySatelliteConfigParserPlaceholder.getConfig());

        SatelliteConfigParser spySatelliteConfigParser =
                spy(new SatelliteConfigParser(mBytesProtoBuffer));
        assertNotNull(spySatelliteConfigParser.getConfig());

        SatelliteConfig mockedSatelliteConfig = mock(SatelliteConfig.class);
        File mMockSatelliteAccessConfigFile = mock(File.class);
        doReturn(mMockSatelliteAccessConfigFile)
                .when(mockedSatelliteConfig)
                .getSatelliteAccessConfigJsonFile(any());
        doReturn(mockedSatelliteConfig).when(spySatelliteConfigParser).getConfig();
        assertEquals(
                mMockSatelliteAccessConfigFile,
                spySatelliteConfigParser.getConfig().getSatelliteAccessConfigJsonFile(mContext));
    }

    @Test
    public void testSatelliteAccessConfigJsonFileParsing() {
        SatelliteConfigParser satelliteConfigParser = new SatelliteConfigParser(mBytesProtoBuffer);
        assertNotNull(satelliteConfigParser);
        assertNotNull(satelliteConfigParser.getConfig());

        Context instrumentationContext = InstrumentationRegistry.getContext();
        File actualSatelliteAccessConfigFile =
                satelliteConfigParser
                        .getConfig()
                        .getSatelliteAccessConfigJsonFile(instrumentationContext);
        logd(
                "actualSatelliteAccessConfigFile's path: "
                        + actualSatelliteAccessConfigFile.getAbsolutePath());
        assertNotNull(actualSatelliteAccessConfigFile);
        assertTrue(actualSatelliteAccessConfigFile.exists());

        // Verify the content of actualSatelliteAccessConfigFile
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(actualSatelliteAccessConfigFile);
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            String actualSatelliteAccessConfigFileContent = new String(buffer);
            assertNotNull(actualSatelliteAccessConfigFileContent);
            assertFalse(actualSatelliteAccessConfigFileContent.isEmpty());
            assertEquals("sac", actualSatelliteAccessConfigFileContent);
        } catch (IOException e) {
            fail("Failed to read file content: " + e.getMessage());
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                fail("Failed to close input stream: " + e.getMessage());
            }
        }
    }

    @Test
    public void testGetSatelliteConfigVersion() {
        SatelliteConfigParser satelliteConfigParserNull = new SatelliteConfigParser((byte[]) null);
        assertNotNull(satelliteConfigParserNull);
        assertNull(satelliteConfigParserNull.getConfig());

        SatelliteConfigParser satelliteConfigParserPlaceholder =
                new SatelliteConfigParser("test".getBytes());
        assertNotNull(satelliteConfigParserPlaceholder);
        assertNull(satelliteConfigParserPlaceholder.getConfig());

        SatelliteConfigParser satelliteConfigParser = new SatelliteConfigParser(mBytesProtoBuffer);
        assertNotNull(satelliteConfigParser);
        assertNotNull(satelliteConfigParser.getConfig());
        assertEquals(4, satelliteConfigParser.getConfig().getSatelliteConfigDataVersion());
    }
}
