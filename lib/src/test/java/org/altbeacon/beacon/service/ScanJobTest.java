package org.altbeacon.beacon.service;

import org.altbeacon.beacon.service.scanner.CycledLeScanner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ScanJobTest {

    @Before
    public void before() {
        org.robolectric.shadows.ShadowLog.stream = System.err;
    }

    @Test
    public void stopCycledScanningStopsOnlyTheCycledScan() {
        ScanHelper scanHelper = mock(ScanHelper.class);
        CycledLeScanner cycledScanner = mock(CycledLeScanner.class);
        when(scanHelper.getCycledScanner()).thenReturn(cycledScanner);

        ScanJob.stopCycledScanning(scanHelper);

        verify(cycledScanner).stop();
        verify(cycledScanner).destroy();
        // The Android O background scan shares its PendingIntent with the intent scan strategy
        verify(scanHelper, never()).stopAndroidOBackgroundScan();
    }

    @Test
    public void stopCycledScanningToleratesHelperWithoutCycledScanner() {
        ScanHelper scanHelper = mock(ScanHelper.class);
        when(scanHelper.getCycledScanner()).thenReturn(null);

        ScanJob.stopCycledScanning(scanHelper);

        verify(scanHelper, never()).stopAndroidOBackgroundScan();
    }

    @Test
    public void stopCycledScanningToleratesMissingHelper() {
        // A job that ran for the intent scan strategy never creates a scan helper
        ScanJob.stopCycledScanning(null);
    }
}
