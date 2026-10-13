package org.altbeacon.beacon.service;

import android.content.Context;

import org.altbeacon.beacon.AltBeaconParser;
import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.logging.LogManager;
import org.altbeacon.beacon.logging.Loggers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ScanHelperTest {
    private static final String MANUFACTURER_LAYOUT = "m:2-3=1234,d:4-6,d:7-8";
    private static final String SERVICE_DATA_LAYOUT = "s:0-1=abcd,i:2-21v,p:--59";

    private Context context;

    @Before
    public void before() {
        org.robolectric.shadows.ShadowLog.stream = System.err;
        LogManager.setLogger(Loggers.verboseLogger());
        LogManager.setVerboseLoggingEnabled(true);
        BeaconManager.setsManifestCheckingDisabled(true);
        context = RuntimeEnvironment.getApplication();
    }

    @After
    public void after() {
        List<BeaconParser> parsers = BeaconManager.getInstanceForApplication(context).getBeaconParsers();
        parsers.clear();
        parsers.add(new AltBeaconParser());
    }

    Beacon getServiceUuidBeacon() {
        return new Beacon.Builder().setId1("1")
                .setBluetoothAddress("01:02:03:04:05:06")
                .setServiceUuid(0xabcd)
                .build();
    }

    Beacon getManufacturerBeacon() {
        return new Beacon.Builder().setId1("1")
                .setBluetoothAddress("01:02:03:04:05:06")
                .build();
    }

    Beacon getMultiFrameBeacon() {
        return new Beacon.Builder().setId1("1")
                .setBluetoothAddress("01:02:03:04:05:06")
                .setServiceUuid(0xabcd)
                .setMultiFrameBeacon(true)
                .build();
    }

    Set<BeaconParser> getSingleFrameParsers() {
        return new HashSet<>(Arrays.asList(
                new BeaconParser("manufacturer").setBeaconLayout(MANUFACTURER_LAYOUT),
                new BeaconParser("service").setBeaconLayout(SERVICE_DATA_LAYOUT)));
    }

    Set<BeaconParser> getExtraFrameParsers() {
        return new HashSet<>(Arrays.asList(
                new BeaconParser("uid").setBeaconLayout(BeaconParser.EDDYSTONE_UID_LAYOUT),
                new BeaconParser("tlm").setBeaconLayout(BeaconParser.EDDYSTONE_TLM_LAYOUT)));
    }

    /** As ScanState keeps them: the extra data parser only inside its main parser. */
    Set<BeaconParser> getParsersWithExtraDataParser() {
        BeaconParser uidParser = new BeaconParser("uid").setBeaconLayout(BeaconParser.EDDYSTONE_UID_LAYOUT);
        uidParser.addExtraDataParser(new BeaconParser("tlm").setBeaconLayout(BeaconParser.EDDYSTONE_TLM_LAYOUT));
        return new HashSet<>(Arrays.asList(uidParser));
    }

    ScanHelper getScanHelperReloadedWith(Set<BeaconParser> parsers) {
        List<BeaconParser> registered = BeaconManager.getInstanceForApplication(context).getBeaconParsers();
        registered.clear();
        registered.addAll(parsers);
        ScanHelper scanHelper = new ScanHelper(context);
        scanHelper.reloadParsers();
        return scanHelper;
    }

    @Test
    public void serviceUuidBeaconSkipsTrackerWithoutMultiFrameParsers() {
        ScanHelper scanHelper = new ScanHelper(context);
        scanHelper.setBeaconParsers(getSingleFrameParsers());
        assertFalse("a service uuid beacon should skip the tracker with no multi-frame parser",
                scanHelper.needsExtraDataTracking(getServiceUuidBeacon()));
        assertFalse("a manufacturer beacon should skip the tracker with no multi-frame parser",
                scanHelper.needsExtraDataTracking(getManufacturerBeacon()));
    }

    @Test
    public void serviceUuidBeaconUsesTrackerWithExtraFrameParser() {
        ScanHelper scanHelper = new ScanHelper(context);
        scanHelper.setBeaconParsers(getExtraFrameParsers());
        assertTrue("a service uuid beacon should use the tracker when an extra frame parser is registered",
                scanHelper.needsExtraDataTracking(getServiceUuidBeacon()));
    }

    @Test
    public void serviceUuidBeaconUsesTrackerWithExtraDataParser() {
        ScanHelper scanHelper = new ScanHelper(context);
        scanHelper.setBeaconParsers(getParsersWithExtraDataParser());
        assertTrue("a service uuid beacon should use the tracker when a parser has an extra data parser",
                scanHelper.needsExtraDataTracking(getServiceUuidBeacon()));
    }

    @Test
    public void multiFrameBeaconUsesTrackerWithoutMultiFrameParsers() {
        ScanHelper scanHelper = new ScanHelper(context);
        scanHelper.setBeaconParsers(getSingleFrameParsers());
        assertTrue("a beacon declared multi-frame should always use the tracker",
                scanHelper.needsExtraDataTracking(getMultiFrameBeacon()));
    }

    @Test
    public void reloadedServiceUuidBeaconSkipsTrackerWithoutMultiFrameParsers() {
        ScanHelper scanHelper = getScanHelperReloadedWith(getSingleFrameParsers());
        assertFalse("a service uuid beacon should skip the tracker with no multi-frame parser",
                scanHelper.needsExtraDataTracking(getServiceUuidBeacon()));
    }

    @Test
    public void reloadedServiceUuidBeaconUsesTrackerWithExtraFrameParser() {
        ScanHelper scanHelper = getScanHelperReloadedWith(getExtraFrameParsers());
        assertTrue("a service uuid beacon should use the tracker when an extra frame parser is registered",
                scanHelper.needsExtraDataTracking(getServiceUuidBeacon()));
    }

    @Test
    public void reloadedServiceUuidBeaconUsesTrackerWithExtraDataParser() {
        ScanHelper scanHelper = getScanHelperReloadedWith(getParsersWithExtraDataParser());
        assertTrue("a service uuid beacon should use the tracker when a parser has an extra data parser",
                scanHelper.needsExtraDataTracking(getServiceUuidBeacon()));
    }

    @Test
    public void trackerDecisionFollowsParserSetChanges() {
        ScanHelper scanHelper = new ScanHelper(context);
        scanHelper.setBeaconParsers(getExtraFrameParsers());
        assertTrue("should use the tracker with an extra frame parser",
                scanHelper.needsExtraDataTracking(getServiceUuidBeacon()));
        scanHelper.setBeaconParsers(getSingleFrameParsers());
        assertFalse("should skip the tracker once no multi-frame parser is left",
                scanHelper.needsExtraDataTracking(getServiceUuidBeacon()));
        scanHelper.setBeaconParsers(getExtraFrameParsers());
        assertTrue("should use the tracker again when an extra frame parser comes back",
                scanHelper.needsExtraDataTracking(getServiceUuidBeacon()));
    }
}
