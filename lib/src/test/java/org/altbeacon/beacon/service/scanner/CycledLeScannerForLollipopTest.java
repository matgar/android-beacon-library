package org.altbeacon.beacon.service.scanner;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
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
