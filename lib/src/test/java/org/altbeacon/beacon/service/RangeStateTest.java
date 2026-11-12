package org.altbeacon.beacon.service;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.logging.LogManager;
import org.altbeacon.beacon.logging.Loggers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.altbeacon.beacon.BeaconManager;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class RangeStateTest {

    private boolean hardwareEqualityWasEnforced;
    private Class<?> rssiFilterClass;

    @Before
    public void before() {
        ShadowLog.stream = System.err;
        LogManager.setLogger(Loggers.verboseLogger());
        LogManager.setVerboseLoggingEnabled(true);
        hardwareEqualityWasEnforced = Beacon.getHardwareEqualityEnforced();
        rssiFilterClass = BeaconManager.getRssiFilterImplClass();
    }

    @After
    public void after() {
        // Static and global, so leaving it flipped would change how every later test hashes beacons.
        Beacon.setHardwareEqualityEnforced(hardwareEqualityWasEnforced);
        BeaconManager.setRssiFilterImplClass(rssiFilterClass);
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

    /**
     * Once a beacon stops being seen, its entry is no longer tracked and the filter is never asked
     * to calculate again, which is the only place it expires samples. Until the expiration passes
     * the entry is legitimately kept (it is what a beacon seen again resumes from), but not reported.
     */
    @Test
    public void untrackedBeaconIsKeptUntilItsSamplesExpire() {
        Beacon.setHardwareEqualityEnforced(false);
        RangeState rangeState = new RangeState(null);
        rangeState.addBeacon(getBeacon());
        assertEquals("the beacon is ranged when seen", 1, rangeState.finalizeBeacons().size());

        advanceBy(RunningAverageRssiFilter.getSampleExpirationMilliseconds() - 1);

        assertEquals("an unseen beacon is not ranged", 0, rangeState.finalizeBeacons().size());
        assertEquals("but its samples are kept until they expire", 1, rangeState.count());
    }

    /**
     * The map used to keep every beacon ever seen, so each cycle end cost more the longer the
     * process lived, and anyBeaconsDetectedThisCycle() never turned false again.
     */
    @Test
    public void untrackedBeaconIsDroppedOnceItsSamplesExpire() {
        Beacon.setHardwareEqualityEnforced(false);
        RangeState rangeState = new RangeState(null);
        rangeState.addBeacon(getBeacon());
        rangeState.finalizeBeacons();

        advanceBy(RunningAverageRssiFilter.getSampleExpirationMilliseconds());
        rangeState.finalizeBeacons();

        assertEquals("a beacon unseen for the sample expiration is dropped", 0, rangeState.count());
    }

    @Test
    public void beaconSeenAgainAfterBeingDroppedIsRangedAgain() {
        Beacon.setHardwareEqualityEnforced(false);
        RangeState rangeState = new RangeState(null);
        rangeState.addBeacon(getBeacon());
        rangeState.finalizeBeacons();
        advanceBy(RunningAverageRssiFilter.getSampleExpirationMilliseconds());
        rangeState.finalizeBeacons();

        rangeState.addBeacon(getBeacon());

        assertEquals("the beacon is ranged again", 1, rangeState.finalizeBeacons().size());
    }

    /**
     * elapsedRealtime starts over on boot, so a range state restored from an earlier boot can
     * carry a last seen time ahead of the clock. The default filter comes back from disk empty and
     * drops such an entry anyway; a filter that never reports running out of measurements does
     * not, so the age alone has to.
     */
    @Test
    public void untrackedBeaconFromAnEarlierBootIsDropped() throws Exception {
        Beacon.setHardwareEqualityEnforced(false);
        BeaconManager.setRssiFilterImplClass(ArmaRssiFilter.class);
        RangeState rangeState = new RangeState(null);
        rangeState.addBeacon(getBeacon());
        rangeState.finalizeBeacons();

        for (RangedBeacon rangedBeacon : rangedBeacons(rangeState).values()) {
            rangedBeacon.lastTrackedTimeMillis = android.os.SystemClock.elapsedRealtime() + 60000;
        }
        rangeState.finalizeBeacons();

        assertEquals("an entry last seen ahead of the clock is dropped", 0, rangeState.count());
    }

    private static void advanceBy(long millis) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis));
    }

    @SuppressWarnings("unchecked")
    private static Map<Beacon, RangedBeacon> rangedBeacons(RangeState rangeState) throws Exception {
        Field field = RangeState.class.getDeclaredField("mRangedBeacons");
        field.setAccessible(true);
        return (Map<Beacon, RangedBeacon>) field.get(rangeState);
    }
}
