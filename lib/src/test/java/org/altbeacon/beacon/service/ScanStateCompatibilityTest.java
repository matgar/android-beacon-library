package org.altbeacon.beacon.service;

import android.content.Context;

import org.altbeacon.beacon.AltBeaconParser;
import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.Region;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The scan state and the monitoring status are saved with Java serialization and read back after
 * the app is updated, so a later build must read what an earlier one wrote. The resources next to
 * this test were written by the build that fixed the serialVersionUIDs.
 *
 * <p>If this test fails, the serialized form of a saved class changed. A serialVersionUID changed
 * on purpose makes the state unreadable: regenerate the resources with {@link #writeResources} and
 * say in the commit message that the saved state is discarded once on upgrade. A field removed,
 * renamed or retyped without changing the serialVersionUID reads back as null or zero: change the
 * serialVersionUID of that class instead.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ScanStateCompatibilityTest {
    private static final String SCAN_STATE_RESOURCE = "scan-state-1.ser";
    private static final String MONITORING_STATUS_RESOURCE = "monitoring-status-1.ser";
    private static final String SCAN_STATE_FILE = "android-beacon-library-scan-state";
    private static final String IBEACON_LAYOUT = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24";
    private static final String UUID = "2f234454-cf6d-4a0f-adf2-f4911ba9ffa6";
    private static final String ADDRESS = "00:11:22:33:44:55";

    private Context context;

    @Before
    public void before() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void readsAScanStateWrittenByAnEarlierBuild() throws Exception {
        copyResource(SCAN_STATE_RESOURCE, new File(context.getFilesDir(), SCAN_STATE_FILE));

        ScanState state = ScanState.restore(context);

        assertEquals("the saved state must be read, not discarded as unreadable: see this class's comment",
                2, state.getBeaconParsers().size());
        assertTrue("the intent strategy flag", state.getIntentScanStrategyEnabled());
        assertEquals(Long.valueOf(1100), state.getForegroundScanPeriod());
        assertEquals(Long.valueOf(2200), state.getForegroundBetweenScanPeriod());
        assertEquals(Long.valueOf(3300), state.getBackgroundScanPeriod());
        assertEquals(Long.valueOf(4400), state.getBackgroundBetweenScanPeriod());
        assertTrue(state.getBackgroundMode());
        assertEquals(123456789L, state.getLastScanStartTimeMillis());

        BeaconParser ibeacon = null;
        BeaconParser altbeacon = null;
        for (BeaconParser parser : state.getBeaconParsers()) {
            if ("ibeacon".equals(parser.getIdentifier())) ibeacon = parser;
            if (parser instanceof AltBeaconParser) altbeacon = parser;
        }
        assertNotNull("the custom parser", ibeacon);
        assertEquals(IBEACON_LAYOUT, ibeacon.getLayout());
        assertEquals(0x0215L, (long) ibeacon.getMatchingBeaconTypeCode());
        assertFalse("its Bluetooth name setting", ibeacon.isBluetoothNameReadingEnabled());
        assertNotNull("the AltBeacon parser", altbeacon);
        assertTrue(altbeacon.isBluetoothNameReadingEnabled());

        assertEquals(1, state.getRangedRegionState().size());
        Map.Entry<Region, RangeState> entry =
                state.getRangedRegionState().entrySet().iterator().next();
        assertEquals(region(), entry.getKey());
        assertEquals(ADDRESS, entry.getKey().getBluetoothAddress());
        RangeState rangeState = entry.getValue();
        assertNotNull("the range state callback", rangeState.getCallback());
        Map<Beacon, RangedBeacon> rangedBeacons = field(rangeState, "mRangedBeacons");
        assertEquals(1, rangedBeacons.size());
        RangedBeacon rangedBeacon = rangedBeacons.values().iterator().next();
        assertTrue(rangedBeacon.isTracked());
        assertEquals(987654321L, rangedBeacon.lastTrackedTimeMillis);
        assertBeacon(rangedBeacon.getBeacon());

        Map<String, HashMap<Integer, Beacon>> tracked =
                field(state.getExtraBeaconDataTracker(), "mBeaconsByKey");
        assertEquals(1, tracked.size());
        assertBeacon(tracked.values().iterator().next().values().iterator().next());
    }

    @Test
    public void readsAMonitoringStatusWrittenByAnEarlierBuild() throws Exception {
        Map<Region, RegionMonitoringState> regions;
        try (ObjectInputStream in = new ObjectInputStream(resource(MONITORING_STATUS_RESOURCE))) {
            //noinspection unchecked
            regions = (Map<Region, RegionMonitoringState>) in.readObject();
        }

        assertEquals(1, regions.size());
        assertEquals(region(), regions.keySet().iterator().next());
        RegionMonitoringState regionState = regions.values().iterator().next();
        assertTrue("inside", regionState.getInside());
        assertEquals(555L, (long) this.<Long>field(regionState, "lastSeenTime"));
        assertNotNull(regionState.getCallback());
    }

    /**
     * Writes the resources. Run it by hand, only when a serialVersionUID changed on purpose:
     * remove the @Ignore, run this test alone, put the @Ignore back and bump the file names.
     * Gradle runs unit tests from the module directory.
     */
    @Ignore("writes the test resources")
    @Test
    public void writeResources() throws Exception {
        ScanState state = new ScanState(context);
        state.setForegroundScanPeriod(1100L);
        state.setForegroundBetweenScanPeriod(2200L);
        state.setBackgroundScanPeriod(3300L);
        state.setBackgroundBetweenScanPeriod(4400L);
        state.setBackgroundMode(true);
        state.setLastScanStartTimeMillis(123456789L);
        setField(state, "mIntentScanStrategyEnabled", true);

        BeaconParser ibeacon = new BeaconParser("ibeacon").setBeaconLayout(IBEACON_LAYOUT);
        ibeacon.setBluetoothNameReadingEnabled(false);
        Set<BeaconParser> parsers = new HashSet<>(Arrays.asList(new AltBeaconParser(), ibeacon));
        state.setBeaconParsers(parsers);

        RangeState rangeState = new RangeState(new Callback(context.getPackageName()));
        rangeState.addBeacon(beacon());
        Map<Beacon, RangedBeacon> rangedBeacons = field(rangeState, "mRangedBeacons");
        rangedBeacons.values().iterator().next().lastTrackedTimeMillis = 987654321L;
        Map<Region, RangeState> rangedRegions = new HashMap<>();
        rangedRegions.put(region(), rangeState);
        state.setRangedRegionState(rangedRegions);

        ExtraDataBeaconTracker tracker = new ExtraDataBeaconTracker();
        tracker.track(beacon());
        state.setExtraBeaconDataTracker(tracker);

        File dir = new File("src/test/resources/org/altbeacon/beacon/service");
        try (ObjectOutputStream out = new ObjectOutputStream(
                new FileOutputStream(new File(dir, SCAN_STATE_RESOURCE)))) {
            out.writeObject(state);
        }

        RegionMonitoringState regionState =
                new RegionMonitoringState(new Callback(context.getPackageName()));
        regionState.markInside();
        setField(regionState, "lastSeenTime", 555L);
        HashMap<Region, RegionMonitoringState> regions = new HashMap<>();
        regions.put(region(), regionState);
        try (ObjectOutputStream out = new ObjectOutputStream(
                new FileOutputStream(new File(dir, MONITORING_STATUS_RESOURCE)))) {
            out.writeObject(regions);
        }
    }

    private static Region region() {
        return new Region("compatibility",
                Arrays.asList(Identifier.parse(UUID), Identifier.fromInt(1)), ADDRESS);
    }

    private static Beacon beacon() {
        return new Beacon.Builder()
                .setIdentifiers(Arrays.asList(
                        Identifier.parse(UUID), Identifier.fromInt(1), Identifier.fromInt(2)))
                .setRssi(-60)
                .setTxPower(-59)
                .setBeaconTypeCode(0xbeac)
                .setServiceUuid(0xfeaa)
                .setManufacturer(0x0118)
                .setBluetoothAddress(ADDRESS)
                .setBluetoothName("beacon-name")
                .setParserIdentifier("ibeacon")
                .setDataFields(Arrays.asList(5L, 6L))
                .setExtraDataFields(Arrays.asList(7L))
                .build();
    }

    private static void assertBeacon(Beacon beacon) {
        assertEquals(beacon(), beacon);
        assertEquals(Arrays.asList(
                Identifier.parse(UUID), Identifier.fromInt(1), Identifier.fromInt(2)),
                beacon.getIdentifiers());
        assertEquals(-60, beacon.getRssi());
        assertEquals(-59, beacon.getTxPower());
        assertEquals(0xbeac, beacon.getBeaconTypeCode());
        assertEquals(0xfeaa, beacon.getServiceUuid());
        assertEquals(0x0118, beacon.getManufacturer());
        assertEquals(ADDRESS, beacon.getBluetoothAddress());
        assertEquals("beacon-name", beacon.getBluetoothName());
        assertEquals("ibeacon", beacon.getParserIdentifier());
        assertEquals(Arrays.asList(5L, 6L), beacon.getDataFields());
        assertEquals(Arrays.asList(7L), beacon.getExtraDataFields());
    }

    private InputStream resource(String name) {
        InputStream in = getClass().getResourceAsStream(name);
        assertNotNull("missing test resource " + name, in);
        return in;
    }

    private void copyResource(String name, File target) throws Exception {
        try (InputStream in = resource(name); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
