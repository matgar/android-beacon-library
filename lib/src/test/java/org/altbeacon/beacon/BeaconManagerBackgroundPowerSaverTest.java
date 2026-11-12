package org.altbeacon.beacon;

import android.content.Context;
import android.os.Looper;

import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ProcessLifecycleOwner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

/**
 * The ranging and monitoring methods create a background power saver, which registers a
 * ProcessLifecycleOwner observer from the main thread. An app that manages the scan mode itself
 * can turn that off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class BeaconManagerBackgroundPowerSaverTest {

    private Context context;
    private boolean manifestCheckingWasDisabled;

    @Before
    public void before() {
        context = RuntimeEnvironment.getApplication();
        manifestCheckingWasDisabled = BeaconManager.getManifestCheckingDisabled();
        BeaconManager.setManifestCheckingDisabled(true);
    }

    @After
    public void after() {
        BeaconManager.setAutomaticBackgroundPowerSaverEnabled(true);
        BeaconManager.setManifestCheckingDisabled(manifestCheckingWasDisabled);
    }

    @Test
    public void createsTheBackgroundPowerSaverByDefault() {
        BeaconManager beaconManager = new BeaconManager(context);
        int observers = lifecycleObserverCount();

        beaconManager.startRangingBeacons(new Region("all", null, null, null));
        shadowOf(Looper.getMainLooper()).idle();

        assertNotNull("a power saver is created", beaconManager.mInternalBackgroundPowerSaver);
        assertEquals("and observes the process lifecycle", observers + 1, lifecycleObserverCount());
    }

    @Test
    public void createsNoBackgroundPowerSaverWhenTurnedOff() {
        BeaconManager.setAutomaticBackgroundPowerSaverEnabled(false);
        BeaconManager beaconManager = new BeaconManager(context);
        int observers = lifecycleObserverCount();

        beaconManager.startRangingBeacons(new Region("all", null, null, null));
        shadowOf(Looper.getMainLooper()).idle();

        assertNull("no power saver is created", beaconManager.mInternalBackgroundPowerSaver);
        assertEquals("nothing observes the process lifecycle", observers, lifecycleObserverCount());
    }

    private static int lifecycleObserverCount() {
        return ((LifecycleRegistry) ProcessLifecycleOwner.get().getLifecycle()).getObserverCount();
    }
}
