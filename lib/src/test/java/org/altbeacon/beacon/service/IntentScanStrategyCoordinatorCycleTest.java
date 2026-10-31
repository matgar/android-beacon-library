package org.altbeacon.beacon.service;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanRecord;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

import org.altbeacon.beacon.AltBeaconParser;
import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.Region;
import org.altbeacon.beacon.service.scanner.CycledLeScanCallback;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * Cycle ends of the intent scan strategy while the CPU sleeps between scan broadcasts. The main
 * looper is paused and never idled, so the handler that defers a cycle end never runs, as on a
 * device whose CPU goes back to sleep as soon as the broadcast receiver returns.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class IntentScanStrategyCoordinatorCycleTest {
    private static final long SCAN_PERIOD = 3000;
    private static final long BETWEEN_SCAN_PERIOD = 5000;
    private static final String ALTBEACON =
            "02011a1bff1801beac2f234454cf6d4a0fadf2f4911ba9ffa600010002c509";

    private BeaconManager beaconManager;
    private long savedForegroundScanPeriod;
    private long savedForegroundBetweenScanPeriod;
    private long savedBackgroundScanPeriod;
    private long savedBackgroundBetweenScanPeriod;
    private List<BeaconParser> savedParsers;

    private ScanHelper scanHelper;
    private CycledLeScanCallback cycleCallback;
    private IntentScanStrategyCoordinator coordinator;
    private BluetoothDevice device;

    @Before
    public void before() throws Exception {
        ShadowLog.stream = System.err;
        Context context = RuntimeEnvironment.getApplication();
        beaconManager = BeaconManager.getInstanceForApplication(context);
        savedForegroundScanPeriod = beaconManager.getForegroundScanPeriod();
        savedForegroundBetweenScanPeriod = beaconManager.getForegroundBetweenScanPeriod();
        savedBackgroundScanPeriod = beaconManager.getBackgroundScanPeriod();
        savedBackgroundBetweenScanPeriod = beaconManager.getBackgroundBetweenScanPeriod();
        savedParsers = new ArrayList<>(beaconManager.getBeaconParsers());
        // The SDK's settings: an 8 s cycle, whichever mode the library is in.
        beaconManager.setForegroundScanPeriod(SCAN_PERIOD);
        beaconManager.setForegroundBetweenScanPeriod(BETWEEN_SCAN_PERIOD);
        beaconManager.setBackgroundScanPeriod(SCAN_PERIOD);
        beaconManager.setBackgroundBetweenScanPeriod(BETWEEN_SCAN_PERIOD);

        scanHelper = mock(ScanHelper.class);
        cycleCallback = mock(CycledLeScanCallback.class);
        when(scanHelper.getCycledLeScanCallback()).thenReturn(cycleCallback);
        when(scanHelper.awaitScanProcessing(anyLong())).thenReturn(true);
        coordinator = new IntentScanStrategyCoordinator(context);
        setField(coordinator, "scanHelper", scanHelper);
        setField(coordinator, "initialized", true);

        device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55");
        // Start well past any cycle end, as on a device that has been up for a while.
        advance(60000);
    }

    @After
    public void after() {
        beaconManager.setForegroundScanPeriod(savedForegroundScanPeriod);
        beaconManager.setForegroundBetweenScanPeriod(savedForegroundBetweenScanPeriod);
        beaconManager.setBackgroundScanPeriod(savedBackgroundScanPeriod);
        beaconManager.setBackgroundBetweenScanPeriod(savedBackgroundBetweenScanPeriod);
        beaconManager.getBeaconParsers().clear();
        beaconManager.getBeaconParsers().addAll(savedParsers);
    }

    @Test
    public void sparseBatchesEachCloseACycleWithTheirResults() {
        InOrder order = inOrder(scanHelper, cycleCallback);
        for (int i = 0; i < 4; i++) {
            coordinator.processScanResults(batch());
            order.verify(scanHelper).processScanResult(any(BluetoothDevice.class), anyInt(), any(), anyLong());
            order.verify(cycleCallback).onCycleEnd();
            advance(20000);
        }
        verify(cycleCallback, times(4)).onCycleEnd();
        // The deferred check is still queued: only the synchronous path closed these cycles.
        assertHandlerPending();
    }

    @Test
    public void anOverdueCycleReportsTheBatchThatClosedIt() throws Exception {
        // A real ScanHelper, which decodes results on threads of its own. The parser is slow, so
        // the cycle would close before the beacon reaches the range state unless the coordinator
        // waits for the decoding.
        Context context = RuntimeEnvironment.getApplication();
        beaconManager.getBeaconParsers().clear();
        beaconManager.getBeaconParsers().add(new SlowAltBeaconParser());
        ScanHelper realScanHelper = new ScanHelper(context);
        realScanHelper.setMonitoringStatus(MonitoringStatus.getInstanceForApplication(context));
        realScanHelper.setBeaconParsers(new HashSet<>(beaconManager.getBeaconParsers()));
        RecordingCallback callback = new RecordingCallback(context.getPackageName());
        Map<Region, RangeState> rangedRegions = new HashMap<>();
        rangedRegions.put(new Region("all", Collections.<Identifier>emptyList()), new RangeState(callback));
        realScanHelper.setRangedRegionState(rangedRegions);
        setField(coordinator, "scanHelper", realScanHelper);

        ArrayList<ScanResult> batch = new ArrayList<>();
        batch.add(altBeaconResult());
        coordinator.processScanResults(batch);

        assertEquals("one cycle should have been reported", 1, callback.beaconCounts.size());
        assertEquals("the cycle should carry the beacon of the batch that closed it",
                1, (int) callback.beaconCounts.get(0));
    }

    @Test
    public void denseBatchesStillAggregateIntoOneCycle() {
        // Batches 2 s apart: the first one, after a silence, closes a cycle; the next three fall
        // inside the 8 s cycle that follows; the fifth, 9 s later, finds that cycle due.
        coordinator.processScanResults(batch());
        for (int i = 0; i < 3; i++) {
            advance(2000);
            coordinator.processScanResults(batch());
        }
        verify(cycleCallback, times(1)).onCycleEnd();
        advance(3000);
        coordinator.processScanResults(batch());
        verify(cycleCallback, times(2)).onCycleEnd();
        verify(scanHelper, times(5)).processScanResult(any(BluetoothDevice.class), anyInt(), any(), anyLong());
    }

    @Test
    public void periodicProcessingClosesAnOverdueCycle() {
        coordinator.processScanResults(batch());
        verify(cycleCallback, times(1)).onCycleEnd();
        advance(20000);
        // What the periodic scan job runs; it returns long before a deferred cycle end would fire.
        coordinator.performPeriodicProcessing(RuntimeEnvironment.getApplication());
        verify(cycleCallback, times(2)).onCycleEnd();
        verify(scanHelper, times(1)).processScanResult(any(BluetoothDevice.class), anyInt(), any(), anyLong());
    }

    @Test
    public void emptyBatchInsideTheCycleDoesNotCloseIt() {
        coordinator.processScanResults(batch());
        advance(2000);
        coordinator.performPeriodicProcessing(RuntimeEnvironment.getApplication());
        verify(cycleCallback, times(1)).onCycleEnd();
    }

    private ArrayList<ScanResult> batch() {
        ArrayList<ScanResult> results = new ArrayList<>();
        results.add(new ScanResult(device, null, -60, SystemClock.elapsedRealtimeNanos()));
        return results;
    }

    /** Takes long enough to decode that a cycle closed without waiting would miss the beacon. */
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

    /** Records how many beacons each ranging callback carried. */
    static class RecordingCallback extends Callback {
        final List<Integer> beaconCounts = new ArrayList<>();

        RecordingCallback(String packageName) {
            super(packageName);
        }

        @Override
        public boolean call(Context context, String dataName, Bundle data) {
            beaconCounts.add(RangingData.fromBundle(data).getBeacons().size());
            return true;
        }
    }

    private ScanResult altBeaconResult() throws Exception {
        // ScanRecord.parseFromBytes is hidden from the SDK stubs but present at runtime.
        Method parse = ScanRecord.class.getDeclaredMethod("parseFromBytes", byte[].class);
        byte[] bytes = new byte[ALTBEACON.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(ALTBEACON.substring(2 * i, 2 * i + 2), 16);
        }
        ScanRecord record = (ScanRecord) parse.invoke(null, (Object) bytes);
        return new ScanResult(device, record, -60, SystemClock.elapsedRealtimeNanos());
    }

    private static void advance(long millis) {
        // Moves both clocks without running anything queued on the main looper.
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis));
    }

    private static void assertHandlerPending() {
        assertFalse("the deferred cycle end check should never have run",
                shadowOf(Looper.getMainLooper()).isIdle());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
