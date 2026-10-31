package org.altbeacon.beacon.service;

/**
 * Created by dyoung on 7/30/17.
 */

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.logging.LogManager;
import org.altbeacon.beacon.logging.Loggers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Created by dyoung on 7/1/15.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ScanStateTest {

    @Before
    public void before() {
        org.robolectric.shadows.ShadowLog.stream = System.err;
        LogManager.setLogger(Loggers.verboseLogger());
        LogManager.setVerboseLoggingEnabled(true);
        BeaconManager.setsManifestCheckingDisabled(true);
    }

    @After
    public void after() {
        BeaconManager.getInstanceForApplication(RuntimeEnvironment.getApplication())
                .setIntentScanningStrategyEnabled(false);
    }

    @Test
    public void serializationTest() throws Exception {
        Context context = RuntimeEnvironment.application;
        ScanState scanState = new ScanState(context);
        MonitoringStatus monitoringStatus = new MonitoringStatus(context);
        scanState.setMonitoringStatus(monitoringStatus);
        scanState.setLastScanStartTimeMillis(1234);
        scanState.save();
        ScanState scanState2 = ScanState.restore(context);
        assertEquals("Scan start time should be restored",
                scanState.getLastScanStartTimeMillis(), scanState2.getLastScanStartTimeMillis());
    }

    @Test
    public void intentScanStrategyIsPersisted() {
        Context context = RuntimeEnvironment.getApplication();
        BeaconManager beaconManager = BeaconManager.getInstanceForApplication(context);
        ScanState scanState = new ScanState(context);
        scanState.setMonitoringStatus(new MonitoringStatus(context));

        beaconManager.setIntentScanningStrategyEnabled(true);
        scanState.applyChanges(beaconManager); // saves
        assertTrue("the intent scan strategy should be restored",
                ScanState.restore(context).getIntentScanStrategyEnabled());

        beaconManager.setIntentScanningStrategyEnabled(false);
        scanState.applyChanges(beaconManager);
        assertFalse("the scan job strategy should be restored",
                ScanState.restore(context).getIntentScanStrategyEnabled());
    }
}