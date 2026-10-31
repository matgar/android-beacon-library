package org.altbeacon.beacon.service;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

/**
 * Restarts of the intent scan strategy's PendingIntent scan. With Bluetooth off and "Bluetooth
 * scanning" on, the adapter is in BLE_ON and getState() reports STATE_OFF, as Robolectric's adapter
 * does after setState(STATE_OFF).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class IntentScanStrategyCoordinatorRestartTest {
    private Context context;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private IntentScanStrategyCoordinator coordinator;

    @Before
    public void before() {
        ShadowLog.stream = System.err;
        context = RuntimeEnvironment.getApplication();
        adapter = BluetoothAdapter.getDefaultAdapter();
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);
        scanner = adapter.getBluetoothLeScanner();
        coordinator = new IntentScanStrategyCoordinator(context);
    }

    @After
    public void after() throws Exception {
        coordinator.stop();
        drainScanCommands();
    }

    @Test
    public void restartLeavesARunningScanAloneWhenBluetoothIsNotOn() throws Exception {
        coordinator.start();
        drainScanCommands();
        ShadowBluetoothLeScanner.ScanParams running = onlyPendingIntentScan();

        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF);
        coordinator.restartBackgroundScan();
        drainScanCommands();

        assertSame("the running scan should be neither stopped nor replaced",
                running, onlyPendingIntentScan());
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
