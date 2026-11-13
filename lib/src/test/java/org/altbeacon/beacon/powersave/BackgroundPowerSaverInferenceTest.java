package org.altbeacon.beacon.powersave;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import org.altbeacon.beacon.BeaconManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * The first ranging or monitoring call infers whether the app starts in the background. On the
 * main thread it reads the process lifecycle instead of walking the call stack, which was the top
 * frame of ANRs when called from Application.onCreate on slow devices.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class BackgroundPowerSaverInferenceTest {

    private BeaconManager beaconManager;
    private BackgroundPowerSaverInternal powerSaver;
    private boolean wasUninitialized;
    private boolean wasBackground;

    @Before
    public void before() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        shadowOf(context.getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true);
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIsInteractive(true);
        beaconManager = BeaconManager.getInstanceForApplication(context);
        wasUninitialized = getField("mBackgroundModeUninitialized");
        wasBackground = getField("mBackgroundMode");
        setField("mBackgroundModeUninitialized", true);
        setField("mBackgroundMode", false);
        powerSaver = new BackgroundPowerSaverInternal(context);
    }

    @After
    public void after() throws Exception {
        setField("mBackgroundModeUninitialized", wasUninitialized);
        setField("mBackgroundMode", wasBackground);
    }

    @Test
    public void infersBackgroundOnTheMainThreadBeforeAnyActivityStarted() {
        powerSaver.enableDefaultBackgroundStateInference(processIn(Lifecycle.State.CREATED));

        assertTrue("background is inferred", beaconManager.getBackgroundMode());
        assertFalse(beaconManager.isBackgroundModeUninitialized());
    }

    @Test
    public void infersNothingOnTheMainThreadOnceAnActivityStarted() {
        powerSaver.enableDefaultBackgroundStateInference(processIn(Lifecycle.State.STARTED));

        assertFalse("the mode stays at the foreground default", beaconManager.getBackgroundMode());
        assertTrue(beaconManager.isBackgroundModeUninitialized());
    }

    /**
     * Application.onCreate only runs on the main thread, so off it the call stack never held it
     * and the result is the one the walk gave: nothing inferred with the screen on.
     */
    @Test
    public void readsNoLifecycleAndInfersNothingOffTheMainThread() throws Exception {
        LifecycleOwner process = processIn(Lifecycle.State.CREATED);
        Lifecycle lifecycle = process.getLifecycle();

        Thread worker = new Thread(() -> powerSaver.enableDefaultBackgroundStateInference(process));
        worker.start();
        worker.join();

        assertFalse("nothing is inferred", beaconManager.getBackgroundMode());
        assertTrue(beaconManager.isBackgroundModeUninitialized());
        verifyZeroInteractions(lifecycle);
    }

    /**
     * A lifecycle left INITIALIZED means the app removed androidx.startup's initializer, so the
     * state says nothing and the call stack is still what tells Application.onCreate apart.
     */
    @Test
    public void fallsBackToTheCallStackWhenTheLifecycleWasNeverInitialized() {
        LifecycleOwner process = processIn(Lifecycle.State.INITIALIZED);

        powerSaver.enableDefaultBackgroundStateInference(process);
        assertTrue("outside Application.onCreate nothing is inferred",
                beaconManager.isBackgroundModeUninitialized());

        new Application() {
            @Override
            public void onCreate() {
                powerSaver.enableDefaultBackgroundStateInference(process);
            }
        }.onCreate();
        assertTrue("inside it, background is inferred", beaconManager.getBackgroundMode());
    }

    private static LifecycleOwner processIn(Lifecycle.State state) {
        Lifecycle lifecycle = mock(Lifecycle.class);
        when(lifecycle.getCurrentState()).thenReturn(state);
        return () -> lifecycle;
    }

    private boolean getField(String name) throws Exception {
        Field field = BeaconManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(beaconManager);
    }

    private void setField(String name, boolean value) throws Exception {
        Field field = BeaconManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(beaconManager, value);
    }
}
