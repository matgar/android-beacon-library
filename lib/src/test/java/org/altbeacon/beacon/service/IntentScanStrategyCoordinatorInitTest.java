package org.altbeacon.beacon.service;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * Initialization of the intent scan strategy coordinator from several threads at once. The library
 * enters it from the main thread (scan broadcasts, foreground transitions), from the scan job's own
 * thread, and from whatever thread the app starts ranging on.
 *
 * The scan state restore runs inside {@code synchronized (ScanState.class)}, so holding that
 * monitor keeps one thread in the middle of initialization while another enters.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class IntentScanStrategyCoordinatorInitTest {
    private static final long TIMEOUT_MILLIS = 10000;

    private Context context;
    private IntentScanStrategyCoordinator coordinator;

    @Before
    public void before() {
        ShadowLog.stream = System.err;
        context = RuntimeEnvironment.getApplication();
        coordinator = new IntentScanStrategyCoordinator(context);
    }

    @After
    public void after() {
        coordinator.stop();
    }

    @Test
    public void periodicProcessingWaitsForAStartStillRestoringState() throws Exception {
        assertSecondEntryWaits(
                new Runnable() {
                    @Override
                    public void run() {
                        coordinator.start();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        coordinator.performPeriodicProcessing(context);
                    }
                });
    }

    @Test
    public void startAndApplySettingsWaitForPeriodicProcessingStillRestoringState() throws Exception {
        assertSecondEntryWaits(
                new Runnable() {
                    @Override
                    public void run() {
                        coordinator.performPeriodicProcessing(context);
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        coordinator.start();
                        coordinator.applySettings();
                    }
                });
    }

    @Test
    public void applySettingsInitializesACoordinatorNothingElseHas() {
        coordinator.applySettings();
    }

    /**
     * Holds the first entry inside the scan state restore, runs the second one on another thread,
     * and requires the second to wait for initialization to finish: neither run on a half
     * initialized coordinator nor initialize it a second time.
     */
    private void assertSecondEntryWaits(Runnable first, Runnable second) throws Exception {
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread firstThread = newThread("first", first, firstFailure);
        Thread secondThread = newThread("second", second, secondFailure);
        boolean secondRestoredAgain;

        synchronized (ScanState.class) {
            firstThread.start();
            awaitBlockedIn(firstThread, "restore");
            secondThread.start();
            // Fixed: the second thread blocks until initialization finishes. Broken: it runs on
            // the half initialized coordinator and fails, or finishes, right away, or it starts a
            // second initialization and blocks in the restore too.
            awaitBlockedOrTerminated(secondThread);
            secondRestoredAgain = isBlockedIn(secondThread, "restore");
        }
        firstThread.join(TIMEOUT_MILLIS);
        secondThread.join(TIMEOUT_MILLIS);

        assertNoFailure("the entry that initialized the coordinator failed", firstFailure);
        assertNoFailure("the entry made while the coordinator was initializing failed", secondFailure);
        assertFalse("the entry made while the coordinator was initializing initialized it again",
                secondRestoredAgain);
    }

    private static void assertNoFailure(String message, AtomicReference<Throwable> failure) {
        if (failure.get() != null) {
            throw new AssertionError(message + ": " + failure.get(), failure.get());
        }
    }

    private static Thread newThread(String name, final Runnable body,
                                    final AtomicReference<Throwable> failure) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    body.run();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void awaitBlockedIn(Thread thread, String methodName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (isBlockedIn(thread, methodName)) {
                return;
            }
            Thread.sleep(5);
        }
        fail(thread.getName() + " never blocked in ScanState." + methodName);
    }

    private static boolean isBlockedIn(Thread thread, String scanStateMethodName) {
        if (thread.getState() != Thread.State.BLOCKED) {
            return false;
        }
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals(ScanState.class.getName())
                    && frame.getMethodName().equals(scanStateMethodName)) {
                return true;
            }
        }
        return false;
    }

    private static void awaitBlockedOrTerminated(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.TERMINATED) {
                return;
            }
            Thread.sleep(5);
        }
        fail(thread.getName() + " neither blocked nor finished");
    }
}
