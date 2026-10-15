package org.altbeacon.beacon.service;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.logging.LogManager;
import org.altbeacon.beacon.logging.Loggers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RangeStateTest {

    private boolean hardwareEqualityWasEnforced;

    @Before
    public void before() {
        ShadowLog.stream = System.err;
        LogManager.setLogger(Loggers.verboseLogger());
        LogManager.setVerboseLoggingEnabled(true);
        hardwareEqualityWasEnforced = Beacon.getHardwareEqualityEnforced();
    }

    @After
    public void after() {
        // Static and global, so leaving it flipped would change how every later test hashes beacons.
        Beacon.setHardwareEqualityEnforced(hardwareEqualityWasEnforced);
    }

    private Beacon getBeacon() {
        return new Beacon.Builder().setId1("1")
                .setBluetoothAddress("01:02:03:04:05:06")
                .setRssi(-50)
                .build();
    }

    @Test
    public void finalizesARangedBeacon() {
        Beacon.setHardwareEqualityEnforced(false);
        RangeState rangeState = new RangeState(null);
        rangeState.addBeacon(getBeacon());

        assertEquals("the beacon is ranged", 1, rangeState.finalizeBeacons().size());
    }

    /**
     * Beacon.hashCode() includes the bluetooth address only while hardware equality is enforced,
     * and that is a static setting an app may turn on at any point -- including after a beacon has
     * already been added to this cycle, which is what happens in a process woken by the scan
     * PendingIntent. Finalizing must not depend on hashing a key that was stored under the other
     * definition.
     */
    @Test
    public void finalizesABeaconRangedBeforeHardwareEqualityWasEnforced() {
        Beacon.setHardwareEqualityEnforced(false);
        RangeState rangeState = new RangeState(null);
        rangeState.addBeacon(getBeacon());

        Beacon.setHardwareEqualityEnforced(true);

        assertEquals("the beacon is still ranged", 1, rangeState.finalizeBeacons().size());
    }

    /**
     * The same flip used to empty the map as well, because every entry failed the lookup and none
     * was carried into the replacement map. That cost the measurements, not just one delivery: the
     * next cycle had nothing left to report either.
     */
    @Test
    public void keepsMeasurementsOfABeaconRangedBeforeHardwareEqualityWasEnforced() {
        Beacon.setHardwareEqualityEnforced(false);
        RangeState rangeState = new RangeState(null);
        rangeState.addBeacon(getBeacon());

        Beacon.setHardwareEqualityEnforced(true);
        rangeState.finalizeBeacons();

        assertEquals("the measurements are kept for the next cycle", 1, rangeState.count());
    }
}
