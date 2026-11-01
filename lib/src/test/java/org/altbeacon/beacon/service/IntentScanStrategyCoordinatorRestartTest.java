package org.altbeacon.beacon.service;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBluetoothLeScanner;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
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

    @Test
    public void periodicJobRestartsTheScanWithFilters() throws Exception {
        coordinator.start();
        drainScanCommands();
        ShadowBluetoothLeScanner.ScanParams first = onlyPendingIntentScan();

        advance(IntentScanStrategyCoordinator.MIN_PERIODIC_RESTART_INTERVAL_MILLIS);
        coordinator.performPeriodicProcessing(context);
        drainScanCommands();
        // The backup scan that the periodic job also starts waits 5 s: let it find Bluetooth off,
        // or it would wait for a detection forever with Robolectric's clocks standing still.
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF);

        ShadowBluetoothLeScanner.ScanParams restarted = onlyPendingIntentScan();
        assertNotSame("the scan should have been registered again", first, restarted);
        assertFalse("the restarted scan should be filtered", restarted.scanFilters().isEmpty());
        coordinator.getExecutor().shutdown();
        coordinator.getExecutor().awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    public void periodicJobLeavesAScanStartedMomentsAgo() throws Exception {
        // A move to the foreground runs the periodic processing right next to the restart that
        // applySettings() does: restarting again would only spend one of the few scan starts
        // Android allows in 30 s.
        coordinator.start();
        drainScanCommands();
        ShadowBluetoothLeScanner.ScanParams running = onlyPendingIntentScan();

        advance(IntentScanStrategyCoordinator.MIN_PERIODIC_RESTART_INTERVAL_MILLIS - 1000);
        coordinator.performPeriodicProcessing(context);
        drainScanCommands();
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF); // let the backup scan end

        assertSame("the scan should not have been restarted", running, onlyPendingIntentScan());
        coordinator.getExecutor().shutdown();
        coordinator.getExecutor().awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    public void periodicJobDoesNotStartTheScanBeforeStart() throws Exception {
        coordinator.performPeriodicProcessing(context);
        drainScanCommands();
        assertTrue("no scan PendingIntent should be registered", pendingIntentScans().isEmpty());
    }

    @Test
    public void bluetoothTurningOnStartsTheScan() throws Exception {
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF);
        coordinator.start();
        coordinator.start(); // a second consumer binding: still one receiver
        drainScanCommands();
        assertTrue("no scan should start with Bluetooth not on", pendingIntentScans().isEmpty());
        assertEquals("one Bluetooth state receiver should be registered", 1, stateReceivers());

        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);
        sendBluetoothState(BluetoothAdapter.STATE_ON);
        drainScanCommands();

        onlyPendingIntentScan();
    }

    @Test
    public void otherBluetoothStatesDoNotRestartTheScan() throws Exception {
        coordinator.start();
        drainScanCommands();
        ShadowBluetoothLeScanner.ScanParams running = onlyPendingIntentScan();

        sendBluetoothState(BluetoothAdapter.STATE_TURNING_ON);
        drainScanCommands();

        assertSame("the scan should not have been restarted", running, onlyPendingIntentScan());
    }

    @Test
    public void bluetoothTurningOnAfterStopDoesNothing() throws Exception {
        coordinator.start();
        coordinator.stop();
        drainScanCommands();
        assertEquals("no Bluetooth state receiver should be left", 0, stateReceivers());

        sendBluetoothState(BluetoothAdapter.STATE_ON);
        drainScanCommands();

        assertTrue("no scan should be registered after stop", pendingIntentScans().isEmpty());
    }

    private void sendBluetoothState(int state) {
        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, state);
        context.sendBroadcast(intent);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private int stateReceivers() {
        return shadowOf((Application) context)
                .getReceiversForIntent(new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)).size();
    }

    private static void advance(long millis) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis));
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
