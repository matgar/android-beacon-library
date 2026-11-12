package org.altbeacon.beacon.service;

import android.content.Context;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.Region;
import org.altbeacon.beacon.logging.LogManager;
import org.altbeacon.beacon.logging.Loggers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.time.Duration;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Created by dyoung on 7/1/16.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitoringStatusTest {
    private static final String TAG = MonitoringStatusTest.class.getSimpleName();
    @Before
    public void before() {
        org.robolectric.shadows.ShadowLog.stream = System.err;
        LogManager.setLogger(Loggers.verboseLogger());
        LogManager.setVerboseLoggingEnabled(true);
        BeaconManager.setManifestCheckingDisabled(true);
        Context context = RuntimeEnvironment.application;
        new MonitoringStatus(context).clear();
    }

    @Test
    public void savesStatusOfUpTo50RegionsTest() throws Exception {
        Context context = RuntimeEnvironment.application;
        MonitoringStatus monitoringStatus = new MonitoringStatus(context);
        for (int i = 0; i < 50; i++) {
            Region region = new Region(""+i, null, null, null);
            monitoringStatus.addRegion(region, null);
        }
        monitoringStatus.saveMonitoringStatusIfOn();
        MonitoringStatus monitoringStatus2 = new MonitoringStatus(context);
        assertEquals("restored regions should be same number as saved", 50, monitoringStatus2.regions().size());
    }

    @Test
    public void clearsStatusOfOver50RegionsTest() throws Exception {
        Context context = RuntimeEnvironment.application;
        MonitoringStatus monitoringStatus = new MonitoringStatus(context);
        for (int i = 0; i < 51; i++) {
            Region region = new Region(""+i, null, null, null);
            monitoringStatus.addRegion(region, null);
        }
        monitoringStatus.saveMonitoringStatusIfOn();
        MonitoringStatus monitoringStatus2 = new MonitoringStatus(context);
        assertEquals("restored regions should be none", 0, monitoringStatus2.regions().size());
    }

    @Test
    public void refusesToRestoreRegionsIfTooMuchTimeHasPassedSinceSavingTest() throws Exception {
        Context context = RuntimeEnvironment.application;
        MonitoringStatus monitoringStatus = new MonitoringStatus(context);
        for (int i = 0; i < 50; i++) {
            Region region = new Region(""+i, null, null, null);
            monitoringStatus.addRegion(region, null);
        }
        monitoringStatus.saveMonitoringStatusIfOn();
        // Set update time to one hour ago
        monitoringStatus.updateMonitoringStatusTime(System.currentTimeMillis() - 1000*3600l);
        MonitoringStatus monitoringStatus2 = new MonitoringStatus(context);
        assertEquals("restored regions should be none", 0, monitoringStatus2.regions().size());
    }

    @Test
    public void allowsAccessToRegionsAfterRestore() throws Exception {
        Context context = RuntimeEnvironment.application;
        BeaconManager beaconManager = BeaconManager.getInstanceForApplication(context);
        MonitoringStatus monitoringStatus = MonitoringStatus.getInstanceForApplication(context);
        for (int i = 0; i < 50; i++) {
            Region region = new Region(""+i, null, null, null);
            monitoringStatus.addRegion(region, null);
        }
        monitoringStatus.saveMonitoringStatusIfOn();
        monitoringStatus.restoreMonitoringStatus();
        Collection<Region> restoredRegions = monitoringStatus.regions();
        assertEquals("tracked regions should be restored", 50, restoredRegions.size());
        Collection<Region> regions = beaconManager.getMonitoredRegions();
        assertEquals("beaconManager should not return regions it did not register", 0, regions.size());
    }



    private static final long STATUS_TIME_REFRESH_INTERVAL_MILLIS = 60 * 1000;
    // A whole second, so file systems with second precision keep it exactly.
    private static final long AGED_STATUS_TIME = 1000000000000L;

    /**
     * The timestamp was refreshed with a File.setLastModified call at every cycle end. The first
     * refresh in a process still writes, so the saved state is no older than the restore window
     * thinks, and later ones wait for the interval.
     */
    @Test
    public void cycleEndRefreshesTheStatusTimeAtMostOncePerInterval() {
        Context context = RuntimeEnvironment.application;
        MonitoringStatus monitoringStatus = new MonitoringStatus(context);
        monitoringStatus.saveMonitoringStatusIfOn();
        File file = statusFile(context);

        ageStatusTime(file);
        monitoringStatus.updateNewlyOutside();
        assertNotEquals("the first refresh writes", AGED_STATUS_TIME, file.lastModified());

        ageStatusTime(file);
        monitoringStatus.updateNewlyOutside();
        assertEquals("a refresh right after does not", AGED_STATUS_TIME, file.lastModified());

        advanceBy(STATUS_TIME_REFRESH_INTERVAL_MILLIS - 1);
        monitoringStatus.updateNewlyOutside();
        assertEquals("nor one within the interval", AGED_STATUS_TIME, file.lastModified());

        advanceBy(1);
        monitoringStatus.updateNewlyOutside();
        assertNotEquals("one at the interval writes again", AGED_STATUS_TIME, file.lastModified());
    }

    /**
     * Every beacon processed refreshed it too, through updateNewlyInsideInRegionsContaining.
     */
    @Test
    public void beaconDetectionRefreshesTheStatusTimeOnTheSameInterval() {
        Context context = RuntimeEnvironment.application;
        MonitoringStatus monitoringStatus = new MonitoringStatus(context);
        monitoringStatus.saveMonitoringStatusIfOn();
        File file = statusFile(context);
        Beacon beacon = new Beacon.Builder().setId1("1").setRssi(-50).build();

        ageStatusTime(file);
        monitoringStatus.updateNewlyInsideInRegionsContaining(beacon);
        assertNotEquals("the first refresh writes", AGED_STATUS_TIME, file.lastModified());

        ageStatusTime(file);
        monitoringStatus.updateNewlyInsideInRegionsContaining(beacon);
        monitoringStatus.updateNewlyOutside();
        assertEquals("refreshes right after do not, from either caller",
                AGED_STATUS_TIME, file.lastModified());
    }

    private static File statusFile(Context context) {
        File file = context.getFileStreamPath(MonitoringStatus.STATUS_PRESERVATION_FILE_NAME);
        assertEquals("the status file exists", true, file.exists());
        return file;
    }

    private static void ageStatusTime(File file) {
        assertEquals(true, file.setLastModified(AGED_STATUS_TIME));
        assertEquals(AGED_STATUS_TIME, file.lastModified());
    }

    private static void advanceBy(long millis) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis));
    }
}
