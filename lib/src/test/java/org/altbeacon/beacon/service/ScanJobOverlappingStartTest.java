package org.altbeacon.beacon.service;

import android.Manifest;
import android.app.Application;
import android.app.job.JobParameters;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;

import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.Region;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * The scan job strategy, with the job started again while its previous run is still active, as
 * when the same ScanJob instance is reused. The main looper is paused, so neither run reaches the
 * end of its scan period on its own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ScanJobOverlappingStartTest {
    private Context context;
    private BluetoothLeScanner scanner;

    @Before
    public void before() {
        ShadowLog.stream = System.err;
        context = RuntimeEnvironment.getApplication();
        shadowOf((Application) context).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);
        scanner = adapter.getBluetoothLeScanner();

        ScanState scanState = new ScanState(context);
        scanState.setMonitoringStatus(MonitoringStatus.getInstanceForApplication(context));
        scanState.applyChanges(BeaconManager.getInstanceForApplication(context));
        scanState.getRangedRegionState().put(new Region("all", Collections.<Identifier>emptyList()),
                new RangeState(new Callback(context.getPackageName())));
        scanState.save();
    }

    @Test
    public void stoppingTheJobStopsTheScanOfEveryRun() throws Exception {
        ScanJob job = Robolectric.setupService(ScanJob.class);
        JobParameters params = mock(JobParameters.class);
        when(params.getJobId()).thenReturn(ScanJob.getImmediateScanJobId(context));

        job.onStartJob(params);
        awaitCallbackScans(1);
        job.onStartJob(params);
        // With the first run's scanner stopped, one scan is running; left behind, two would be.
        Thread.sleep(500);
        assertEquals("only the latest run should be scanning", 1, callbackScans());

        job.onStopJob(params);
        awaitCallbackScans(0);
        assertEquals("no callback scan should be left running", 0, callbackScans());
    }

    private int callbackScans() {
        return shadowOf(scanner).getScanCallbacks().size();
    }

    /** onStartJob works on a thread of its own, and scans start and stop on the scan thread. */
    private void awaitCallbackScans(int expected) throws InterruptedException {
        for (int i = 0; i < 300 && callbackScans() != expected; i++) {
            Thread.sleep(10);
        }
    }
}
