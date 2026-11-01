package org.altbeacon.beacon.service.scanner;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

/**
 * Starting a cycled scan with Bluetooth not fully on. With Bluetooth off and "Bluetooth scanning"
 * on, the adapter is in BLE_ON and getState() reports STATE_OFF, as Robolectric's adapter does
 * after setState(STATE_OFF) while still handing out a BluetoothLeScanner.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CycledLeScannerForLollipopTest {
    private BluetoothAdapter adapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private CycledLeScannerForLollipop cycledScanner;

    @Before
    public void before() {
        ShadowLog.stream = System.err;
        Context context = RuntimeEnvironment.getApplication();
        adapter = BluetoothAdapter.getDefaultAdapter();
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);
        bluetoothLeScanner = adapter.getBluetoothLeScanner();
        cycledScanner = new CycledLeScannerForLollipop(context, 1100, 0, false,
                mock(CycledLeScanCallback.class), null);
    }

    @After
    public void after() {
        cycledScanner.destroy();
    }

    @Test
    public void scanStartsWithBluetoothOn() throws Exception {
        cycledScanner.startScan();
        drainScanThread();
        assertEquals("a callback scan should be registered",
                1, shadowOf(bluetoothLeScanner).getScanCallbacks().size());
    }

    @Test
    public void scanDoesNotStartInBleOnlyMode() throws Exception {
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF);
        Looper scanLooper = cycledScanner.mScanHandler.getLooper();
        shadowOf(scanLooper).pause();
        cycledScanner.startScan();
        assertTrue("nothing should be posted to the scan thread", shadowOf(scanLooper).isIdle());
        shadowOf(scanLooper).unPause();
        drainScanThread();
        assertTrue("no callback scan should be registered",
                shadowOf(bluetoothLeScanner).getScanCallbacks().isEmpty());
    }

    @Test
    public void scanDoesNotStartIfBluetoothGoesOffBeforeTheScanThreadRuns() throws Exception {
        Looper scanLooper = cycledScanner.mScanHandler.getLooper();
        shadowOf(scanLooper).pause();
        cycledScanner.startScan(); // Bluetooth on: posts the start to the scan thread
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF);
        shadowOf(scanLooper).unPause();
        drainScanThread();
        assertTrue("no callback scan should be registered",
                shadowOf(bluetoothLeScanner).getScanCallbacks().isEmpty());
    }

    @Test
    public void registrationFailureHoldsOffScanStarts() throws Exception {
        startAndFailRegistration();
        advance(CycledLeScannerForLollipop.REGISTRATION_FAILURE_INITIAL_HOLD_OFF_MILLIS - 1000);
        assertFalse("no scan should start during the hold-off", startScanRegisters());
        advance(1000);
        assertTrue("the scan should start once the hold-off is over", startScanRegisters());
    }

    @Test
    public void holdOffDoublesWithConsecutiveFailures() throws Exception {
        startAndFailRegistration();
        advance(10000);
        startAndFailRegistration();
        advance(15000);
        assertFalse("the second hold-off should last 20 s", startScanRegisters());
        advance(5000);
        assertTrue("the scan should start after 20 s", startScanRegisters());
    }

    @Test
    public void holdOffIsCappedAtFiveMinutes() throws Exception {
        startAndFailRegistration();
        for (int i = 0; i < 9; i++) {
            failRegistration();
        }
        advance(CycledLeScannerForLollipop.REGISTRATION_FAILURE_MAX_HOLD_OFF_MILLIS - 1000);
        assertFalse("no scan should start before the cap", startScanRegisters());
        advance(1000);
        assertTrue("the scan should start at the cap", startScanRegisters());
    }

    @Test
    public void scanResultClearsTheFailures() throws Exception {
        startAndFailRegistration();
        advance(10000);
        assertTrue(startScanRegisters());
        registeredCallback().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult());
        failRegistration();
        advance(10000);
        assertTrue("after a result, the hold-off should start over at 10 s", startScanRegisters());
    }

    @Test
    public void otherFailuresDoNotHoldOff() throws Exception {
        cycledScanner.startScan();
        drainScanThread();
        ScanCallback callback = registeredCallback();
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        bluetoothLeScanner.stopScan(callback);
        assertTrue("the scan should start again right away", startScanRegisters());
    }

    /** Starts a scan and fails its registration, as the stack does when no scanner slot is free. */
    private void startAndFailRegistration() throws Exception {
        cycledScanner.startScan();
        drainScanThread();
        failRegistration();
    }

    private void failRegistration() {
        ScanCallback callback = registeredCallback();
        callback.onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED);
        // A failed registration leaves no scan running.
        bluetoothLeScanner.stopScan(callback);
    }

    /** Whether startScan() registers a scan; the scan is stopped again before returning. */
    private boolean startScanRegisters() throws Exception {
        cycledScanner.startScan();
        drainScanThread();
        boolean registered = !shadowOf(bluetoothLeScanner).getScanCallbacks().isEmpty();
        if (registered) {
            lastStarted = registeredCallback();
            bluetoothLeScanner.stopScan(lastStarted);
        }
        return registered;
    }

    private ScanCallback lastStarted;

    /**
     * The scanner's callback: the one registered now or, once stopped, the last one seen. The
     * scanner keeps a single callback object for all its scans.
     */
    private ScanCallback registeredCallback() {
        if (!shadowOf(bluetoothLeScanner).getScanCallbacks().isEmpty()) {
            lastStarted = shadowOf(bluetoothLeScanner).getScanCallbacks().iterator().next();
        }
        return lastStarted;
    }

    private static void advance(long millis) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis));
    }

    private static ScanResult scanResult() throws Exception {
        // ScanRecord.parseFromBytes is hidden from the SDK stubs but present at runtime.
        Method parse = ScanRecord.class.getDeclaredMethod("parseFromBytes", byte[].class);
        ScanRecord record = (ScanRecord) parse.invoke(null, (Object) new byte[] {2, 1, 6});
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55");
        return new ScanResult(device, record, -60, SystemClock.elapsedRealtimeNanos());
    }

    /** Waits until everything posted to the scan thread so far has run. */
    private void drainScanThread() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        cycledScanner.mScanHandler.post(new Runnable() {
            @Override
            public void run() {
                done.countDown();
            }
        });
        assertTrue("the scan thread should be idle", done.await(5, TimeUnit.SECONDS));
    }
}
