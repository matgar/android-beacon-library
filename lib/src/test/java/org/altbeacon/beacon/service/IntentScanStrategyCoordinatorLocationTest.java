package org.altbeacon.beacon.service;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.content.pm.PackageInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBluetoothLeScanner;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Restarts of the intent scan strategy's PendingIntent scan when the app has location access only
 * while in use. The Bluetooth stack decides whether a scan may receive results when it is
 * registered, so a scan registered from the background gets nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class IntentScanStrategyCoordinatorLocationTest {
    private Application context;
    private BluetoothLeScanner scanner;
    private IntentScanStrategyCoordinator coordinator;

    @Before
    public void before() {
        ShadowLog.stream = System.err;
        context = RuntimeEnvironment.getApplication();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);
        scanner = adapter.getBluetoothLeScanner();
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT);
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        coordinator = new IntentScanStrategyCoordinator(context);
    }

    @After
    public void after() throws Exception {
        coordinator.stop();
        drainScanCommands();
    }

    @Test
    public void restartFromTheBackgroundKeepsTheRunningScan() throws Exception {
        ShadowBluetoothLeScanner.ScanParams running = startInTheForeground();

        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        coordinator.restartBackgroundScan();
        drainScanCommands();

        assertSame("the scan should be neither stopped nor replaced by one that gets no results",
                running, onlyPendingIntentScan());
    }

    @Test
    public void restartInTheForegroundReplacesTheScan() throws Exception {
        ShadowBluetoothLeScanner.ScanParams running = startInTheForeground();

        coordinator.restartBackgroundScan();
        drainScanCommands();

        assertNotSame("the scan should have been registered again", running, onlyPendingIntentScan());
    }

    @Test
    public void restartFromTheBackgroundWithBackgroundLocationReplacesTheScan() throws Exception {
        ShadowBluetoothLeScanner.ScanParams running = startInTheForeground();

        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        coordinator.restartBackgroundScan();
        drainScanCommands();

        assertNotSame("the scan should have been registered again", running, onlyPendingIntentScan());
    }

    @Test
    public void restartFromTheBackgroundReplacesTheScanWhenScanningDisavowsLocation() throws Exception {
        // The manifest is read once, so it must say this before the first scan starts.
        PackageInfo info = shadowOf(context.getPackageManager())
                .getInternalMutablePackageInfo(context.getPackageName());
        info.requestedPermissions = new String[] {Manifest.permission.BLUETOOTH_SCAN};
        info.requestedPermissionsFlags = new int[] {PackageInfo.REQUESTED_PERMISSION_GRANTED
                | PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION};
        ShadowBluetoothLeScanner.ScanParams running = startInTheForeground();

        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        coordinator.restartBackgroundScan();
        drainScanCommands();

        assertNotSame("the scan should have been registered again", running, onlyPendingIntentScan());
    }

    @Test
    public void restartWithoutLocationKeepsTheRunningScan() throws Exception {
        // Location revoked after the scan was registered: no new scan would get results, even in
        // the foreground.
        ShadowBluetoothLeScanner.ScanParams running = startInTheForeground();

        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        coordinator.restartBackgroundScan();
        drainScanCommands();

        assertSame("the scan should be neither stopped nor replaced by one that gets no results",
                running, onlyPendingIntentScan());
    }

    @Test
    public void startFromTheBackgroundRegistersNoScan() throws Exception {
        // A process woken from cold, before the app was ever in the foreground.
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        coordinator.start();
        drainScanCommands();

        assertTrue("no scan PendingIntent should be registered", pendingIntentScans().isEmpty());
    }

    private ShadowBluetoothLeScanner.ScanParams startInTheForeground() throws Exception {
        coordinator.start();
        drainScanCommands();
        return onlyPendingIntentScan();
    }

    private void setImportance(int importance) {
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo(
                context.getPackageName(), android.os.Process.myPid(), null);
        process.importance = importance;
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        shadowOf(activityManager).setProcesses(Collections.singletonList(process));
    }

    /** Waits for the scan start and stop commands submitted so far to run. */
    private void drainScanCommands() throws Exception {
        Field field = IntentScanStrategyCoordinator.class.getDeclaredField("scanCommandExecutor");
        field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(coordinator);
        executor.submit(new Runnable() {
            @Override
            public void run() {
            }
        }).get(5, TimeUnit.SECONDS);
    }

    private ShadowBluetoothLeScanner.ScanParams onlyPendingIntentScan() {
        ArrayList<ShadowBluetoothLeScanner.ScanParams> scans = pendingIntentScans();
        assertEquals("exactly one scan PendingIntent should be registered", 1, scans.size());
        return scans.get(0);
    }

    private ArrayList<ShadowBluetoothLeScanner.ScanParams> pendingIntentScans() {
        ArrayList<ShadowBluetoothLeScanner.ScanParams> scans = new ArrayList<>();
        for (ShadowBluetoothLeScanner.ScanParams params : shadowOf(scanner).getActiveScans()) {
            if (params.pendingIntent() != null) {
                scans.add(params);
            }
        }
        return scans;
    }
}
