package org.altbeacon.beacon.service;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.Region;
import org.altbeacon.beacon.service.scanner.CycledLeScanCallback;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class IntentScanStrategyCoordinatorTest {

    @Before
    public void before() {
        org.robolectric.shadows.ShadowLog.stream = System.err;
    }

    /** A region with no identifiers at all, which start() treats as a wildcard. */
    Region getWildcardRegion(String id) {
        return new Region(id, Collections.<Identifier>emptyList());
    }

    Region getSpecificRegion(String id) {
        return new Region(id, Identifier.parse("2f234454-cf6d-4a0f-adf2-f4911ba9ffa6"), null, null);
    }

    @Test
    public void noRegionsGiveNullSoFiltersComeFromParsers() {
        assertNull("no regions should give null, never an empty list",
                IntentScanStrategyCoordinator.regionsForScanFilters(
                        Collections.<Region>emptyList(), Collections.<Region>emptyList()));
    }

    @Test
    public void wildcardRegionsAreUsedWhenNoSpecificRegionExists() {
        Region ranged = getWildcardRegion("ranged");
        Region monitored = getWildcardRegion("monitored");
        List<Region> regions = IntentScanStrategyCoordinator.regionsForScanFilters(
                Collections.singletonList(ranged), Collections.singletonList(monitored));
        assertEquals("both wildcard regions should be used", new HashSet<>(Arrays.asList(ranged, monitored)),
                new HashSet<>(regions));
    }

    @Test
    public void wildcardRegionsAreIgnoredWhenSpecificRegionsExist() {
        Region specific = getSpecificRegion("specific");
        List<Region> regions = IntentScanStrategyCoordinator.regionsForScanFilters(
                Arrays.asList(getWildcardRegion("wildcard"), specific), Collections.<Region>emptyList());
        assertEquals("only the specific region should be used", Collections.singletonList(specific), regions);
    }

    @Test
    public void backupScanDoesNotStartInBleOnlyMode() throws Exception {
        // In BLE_ON (Bluetooth off, "Bluetooth scanning" on) getState() reports STATE_OFF.
        Context context = RuntimeEnvironment.getApplication();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF);
        ScanHelper scanHelper = mock(ScanHelper.class);
        when(scanHelper.anyBeaconsDetectedThisCycle()).thenReturn(false);
        when(scanHelper.getCycledLeScanCallback()).thenReturn(mock(CycledLeScanCallback.class));
        IntentScanStrategyCoordinator coordinator = new IntentScanStrategyCoordinator(context);
        setField(coordinator, "scanHelper", scanHelper);
        setField(coordinator, "initialized", true);
        setField(coordinator, "started", true);

        coordinator.runBackupScan(context);
        // The backup scan waits 5 s before starting. Robolectric's clocks stand still, so with a
        // scan started its 30 s timeout would never pass: give up waiting after 10 s.
        coordinator.getExecutor().shutdown();
        coordinator.getExecutor().awaitTermination(10, TimeUnit.SECONDS);

        assertTrue("no callback scan should be registered",
                shadowOf(adapter.getBluetoothLeScanner()).getScanCallbacks().isEmpty());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
