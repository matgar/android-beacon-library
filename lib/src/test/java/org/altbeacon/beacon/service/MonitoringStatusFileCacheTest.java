package org.altbeacon.beacon.service;

import android.content.Context;
import android.content.ContextWrapper;

import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.logging.LogManager;
import org.altbeacon.beacon.logging.Loggers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Context.getFileStreamPath() goes through ContextImpl.getFilesDir(), which synchronizes on a lock
 * shared by the whole process. updateMonitoringStatusTime() runs once per scan cycle — about once
 * a second on the main thread with the intent scan strategy — so it used to take that lock at 1 Hz
 * for as long as scanning was on. In a host app's Play vitals that was 88 ANR reports, every one
 * of them with the main thread Blocked in getFilesDir while a React Native thread held the lock.
 *
 * The path cannot change while the process lives, so it is resolved once.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MonitoringStatusFileCacheTest {

    /** Counts how often the status file path is resolved through the Context. */
    private static class CountingContext extends ContextWrapper {
        final AtomicInteger fileStreamPathCalls = new AtomicInteger();

        CountingContext(Context base) {
            super(base);
        }

        @Override
        public File getFileStreamPath(String name) {
            fileStreamPathCalls.incrementAndGet();
            return super.getFileStreamPath(name);
        }
    }

    private CountingContext context;

    @Before
    public void before() {
        org.robolectric.shadows.ShadowLog.stream = System.err;
        LogManager.setLogger(Loggers.verboseLogger());
        LogManager.setVerboseLoggingEnabled(true);
        BeaconManager.setManifestCheckingDisabled(true);
        context = new CountingContext(RuntimeEnvironment.application);
    }

    @Test
    public void resolvesTheStatusFilePathOnlyOnce() {
        MonitoringStatus status = new MonitoringStatus(context);

        for (int i = 0; i < 100; i++) {
            status.updateMonitoringStatusTime(System.currentTimeMillis());
        }

        assertEquals("the path must be resolved once, not once per scan cycle",
                1, context.fileStreamPathCalls.get());
    }

    @Test
    public void sharesTheResolvedPathBetweenReadsAndWrites() {
        MonitoringStatus status = new MonitoringStatus(context);

        status.updateMonitoringStatusTime(System.currentTimeMillis());
        status.getLastMonitoringStatusUpdateTime();

        assertEquals(1, context.fileStreamPathCalls.get());
    }

    @Test
    public void resolvesTheSameFileTheContextWouldHaveGiven() throws Exception {
        MonitoringStatus status = new MonitoringStatus(context);
        File expected = context.getFileStreamPath(MonitoringStatus.STATUS_PRESERVATION_FILE_NAME);
        context.fileStreamPathCalls.set(0);

        writeStatusFile();
        long when = secondsPrecision(System.currentTimeMillis() - 60_000);
        status.updateMonitoringStatusTime(when);

        assertEquals("the cached path must point at the same file as before",
                when, secondsPrecision(expected.lastModified()));
    }

    @Test
    public void stillReportsTheTimeItWrote() throws Exception {
        MonitoringStatus status = new MonitoringStatus(context);
        writeStatusFile();
        long when = secondsPrecision(System.currentTimeMillis() - 120_000);

        status.updateMonitoringStatusTime(when);

        assertEquals(when, secondsPrecision(status.getLastMonitoringStatusUpdateTime()));
    }

    @Test
    public void resolvesOncePerInstanceEvenUnderConcurrentUse() throws Exception {
        final MonitoringStatus status = new MonitoringStatus(context);
        final int threads = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int j = 0; j < 50; j++) {
                            status.updateMonitoringStatusTime(System.currentTimeMillis());
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals("the double checked lock must not resolve the path more than once",
                1, context.fileStreamPathCalls.get());
    }

    @Test
    public void keepsTheCachePerInstance() {
        MonitoringStatus first = new MonitoringStatus(context);
        MonitoringStatus second = new MonitoringStatus(context);

        first.updateMonitoringStatusTime(System.currentTimeMillis());
        second.updateMonitoringStatusTime(System.currentTimeMillis());

        // Nothing is shared statically: a second instance resolves its own path.
        assertEquals(2, context.fileStreamPathCalls.get());
        assertSame(context, context);
    }

    /** setLastModified() is a no-op on a file that does not exist. */
    private void writeStatusFile() throws Exception {
        FileOutputStream out =
                context.openFileOutput(MonitoringStatus.STATUS_PRESERVATION_FILE_NAME, Context.MODE_PRIVATE);
        out.write(new byte[]{1});
        out.close();
    }

    /** Some filesystems store modification times with second granularity. */
    private long secondsPrecision(long millis) {
        return millis / 1000L * 1000L;
    }
}
