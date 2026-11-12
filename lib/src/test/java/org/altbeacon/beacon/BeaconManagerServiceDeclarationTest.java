package org.altbeacon.beacon;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;

import org.altbeacon.beacon.service.BeaconService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

/**
 * The BeaconManager constructor checks that the BeaconService is declared. The library's merged
 * manifest declares it, so in the field an empty answer means the package manager failed for a
 * moment, in a process the system woke for a scan job or a scan broadcast, and throwing crashed
 * the app.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BeaconManagerServiceDeclarationTest {

    private Context context;
    private int applicationFlags;
    private boolean manifestCheckingWasDisabled;

    @Before
    public void before() {
        context = RuntimeEnvironment.getApplication();
        applicationFlags = context.getApplicationInfo().flags;
        manifestCheckingWasDisabled = BeaconManager.getManifestCheckingDisabled();
        BeaconManager.setManifestCheckingDisabled(false);
    }

    @After
    public void after() {
        context.getApplicationInfo().flags = applicationFlags;
        BeaconManager.setManifestCheckingDisabled(manifestCheckingWasDisabled);
    }

    @Test(expected = BeaconManager.ServiceNotDeclaredException.class)
    public void throwsWhenTheServiceIsMissingInADebuggableApp() {
        setDebuggable(true);
        removeBeaconService();

        new BeaconManager(context);
    }

    @Test
    public void continuesWhenTheServiceIsMissingInAReleaseApp() {
        setDebuggable(false);
        removeBeaconService();

        assertNotNull(new BeaconManager(context));
    }

    @Test
    public void continuesWhenTheServiceIsDeclared() {
        shadowOf(context.getPackageManager()).addServiceIfNotPresent(beaconService());

        setDebuggable(true);
        assertNotNull(new BeaconManager(context));
        setDebuggable(false);
        assertNotNull(new BeaconManager(context));
    }

    private void setDebuggable(boolean debuggable) {
        ApplicationInfo info = context.getApplicationInfo();
        if (debuggable) {
            info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        } else {
            info.flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        }
    }

    private void removeBeaconService() {
        shadowOf(context.getPackageManager()).removeService(beaconService());
    }

    private ComponentName beaconService() {
        return new ComponentName(context, BeaconService.class);
    }
}
