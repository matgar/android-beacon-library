package org.altbeacon.beacon.service;

import android.app.job.JobParameters;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.SystemClock;

import org.altbeacon.beacon.AltBeaconParser;
import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.Region;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowBluetoothLeScanner;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * A ScanJob in a process woken from cold while the app runs the intent scan strategy: the
 * coordinator does not exist yet, and the app has not configured the library. The main looper is
 * paused and never idled, so the job's own scan never reaches its end, as when the process is
 * killed while the job runs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ScanJobColdIntentStrategyTest {
    private static final String ALTBEACON =
            "02011a1bff1801beac2f234454cf6d4a0fadf2f4911ba9ffa600010002c509";

    private Context context;
    private BeaconManager beaconManager;
    private BluetoothLeScanner scanner;
    private Region region;
    private ShadowBluetoothLeScanner.ScanParams coordinatorScan;

    @Before
    public void before() throws Exception {
        ShadowLog.stream = System.err;
        context = RuntimeEnvironment.getApplication();
        beaconManager = BeaconManager.getInstanceForApplication(context);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        shadowOf(adapter).setEnabled(true);
        scanner = adapter.getBluetoothLeScanner();
        region = new Region("all", Collections.<Identifier>emptyList());

        // What a previous process left behind: the app enabled the intent scan strategy, ranged a
        // region, and the coordinator registered the scan PendingIntent.
        beaconManager.setIntentScanningStrategyEnabled(true);
        ScanState scanState = new ScanState(context);
        scanState.setMonitoringStatus(MonitoringStatus.getInstanceForApplication(context));
        scanState.applyChanges(beaconManager);
        scanState.getRangedRegionState().put(region, new RangeState(new Callback(context.getPackageName())));
        scanState.save();
        new ScanHelper(context).startAndroidOBackgroundScan(scanState.getBeaconParsers());
        coordinatorScan = onlyPendingIntentScan();

        // This process is cold: the library has its defaults and no coordinator.
        beaconManager.setIntentScanningStrategyEnabled(false);
        ArrayList<ScanResult> queued = new ArrayList<>();
        queued.add(scanResult(ALTBEACON));
        ScanJobScheduler.getInstance().scheduleAfterBackgroundWakeup(context, queued);
    }

    @After
    public void after() {
        beaconManager.setIntentScanningStrategyEnabled(false);
        ScanJobScheduler.getInstance().dumpBackgroundScanResultQueue();
    }

    @Test
    public void coldJobLeavesThePendingIntentScanAlone() throws Exception {
        ScanJob job = Robolectric.setupService(ScanJob.class);
        runToCompletion(job);

        // The same registration, not a new one: a stop and restart leaves a window in which a
        // process death leaves the device without the scan.
        assertSame("the scan PendingIntent should never have been cancelled",
                coordinatorScan, onlyPendingIntentScan());
        assertTrue("the job should not run a scan of its own",
                shadowOf(scanner).getScanCallbacks().isEmpty());
    }

    @Test
    public void coldJobKeepsTheQueuedResults() throws Exception {
        // A parser slow enough that the state would be saved before the beacon is processed,
        // unless the job waits for the processing threads.
        ScanState scanState = ScanState.restore(context);
        HashSet<BeaconParser> parsers = new HashSet<>();
        parsers.add(new SlowAltBeaconParser());
        scanState.setBeaconParsers(parsers);
        scanState.save();

        ScanJob job = Robolectric.setupService(ScanJob.class);
        runToCompletion(job);

        RangeState rangeState = ScanState.restore(context).getRangedRegionState().get(region);
        assertEquals("the queued beacon should be in the saved state", 1, rangeState.count());
    }

    @Test
    public void stoppingTheColdJobLeavesThePendingIntentScanAlone() throws Exception {
        ScanJob job = Robolectric.setupService(ScanJob.class);
        JobParameters params = runToCompletion(job);

        job.onStopJob(params);

        assertSame("the scan PendingIntent should never have been cancelled",
                coordinatorScan, onlyPendingIntentScan());
    }

    private JobParameters runToCompletion(ScanJob job) throws InterruptedException {
        JobParameters params = mock(JobParameters.class);
        when(params.getJobId()).thenReturn(ScanJob.getImmediateScanJobId(context));
        job.onStartJob(params);
        // onStartJob does its work on a thread of its own. Robolectric's clocks stand still here,
        // so the wait is bounded by a count of real sleeps: up to 5 s.
        for (int i = 0; i < 500 && !shadowOf(job).getIsJobFinished(); i++) {
            Thread.sleep(10);
        }
        return params;
    }

    private ShadowBluetoothLeScanner.ScanParams onlyPendingIntentScan() {
        ArrayList<ShadowBluetoothLeScanner.ScanParams> scans = new ArrayList<>();
        for (ShadowBluetoothLeScanner.ScanParams params : shadowOf(scanner).getActiveScans()) {
            if (params.pendingIntent() != null) {
                scans.add(params);
            }
        }
        assertEquals("exactly one scan PendingIntent should be registered", 1, scans.size());
        return scans.get(0);
    }

    static class SlowAltBeaconParser extends AltBeaconParser {
        @Override
        public Beacon fromScanData(byte[] scanData, int rssi, BluetoothDevice device, long timestampMs) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.fromScanData(scanData, rssi, device, timestampMs);
        }
    }

    private static ScanResult scanResult(String hex) throws Exception {
        // ScanRecord.parseFromBytes is hidden from the SDK stubs but present at runtime.
        Method parse = ScanRecord.class.getDeclaredMethod("parseFromBytes", byte[].class);
        ScanRecord record = (ScanRecord) parse.invoke(null, (Object) hexStringToByteArray(hex));
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55");
        return new ScanResult(device, record, -60, SystemClock.elapsedRealtimeNanos());
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
