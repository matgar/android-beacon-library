package org.altbeacon.beacon.distance;

import android.content.Context;

import org.altbeacon.beacon.Settings;
import org.altbeacon.beacon.logging.LogManager;
import org.altbeacon.beacon.logging.Loggers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Settings.Defaults.distanceModelUpdateUrl is "" and the comment next to it says "disabled", but
 * loadModelMap() used to guard the remote fetch with a null check alone. An empty string is not
 * null, so the fetch ran anyway: on the caller's thread, reached from applyChangesToServices while
 * ranging starts. It showed up as 32 ANR reports in a host app's Play vitals, with
 * requestModelMapFromWeb -> AsyncTask.executeOnExecutor -> Thread.nativeCreate on the main thread.
 */
@Config(sdk = 28)
@RunWith(RobolectricTestRunner.class)
public class ModelSpecificDistanceCalculatorRemoteUpdateTest {

    private static final String TAG = "ModelSpecificDistanceCalculator";

    private Context context;

    @Before
    public void setUp() {
        ShadowLog.stream = System.err;
        ShadowLog.clear();
        LogManager.setLogger(Loggers.verboseLogger());
        LogManager.setVerboseLoggingEnabled(true);
        context = RuntimeEnvironment.application;
        // No stored model map: a fresh install, which is when the fetch would be attempted.
        context.getSharedPreferences("org.altbeacon.beacon", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void skipsRemoteFetchWithTheDefaultUrl() {
        new ModelSpecificDistanceCalculator(context, Settings.Defaults.distanceModelUpdateUrl);

        assertFalse("the default URL is documented as disabled and must not fetch",
                reachedRemoteFetch());
    }

    @Test
    public void skipsRemoteFetchWhenUrlIsNull() {
        new ModelSpecificDistanceCalculator(context, null);

        assertFalse("a null URL must not reach the remote fetch", reachedRemoteFetch());
    }

    @Test
    public void stillAttemptsRemoteFetchWhenAUrlIsConfigured() {
        new ModelSpecificDistanceCalculator(context, "https://example.com/models.json");

        assertTrue("callers that configure a URL must still get the update behaviour",
                reachedRemoteFetch());
    }

    @Test
    public void producesAWorkingCalculatorWithTheDefaultUrl() {
        ModelSpecificDistanceCalculator calculator =
                new ModelSpecificDistanceCalculator(context, Settings.Defaults.distanceModelUpdateUrl);

        // The default model map covers this case, which is why skipping the fetch costs nothing.
        assertTrue(calculator.calculateDistance(-59, -59) > 0);
    }

    /**
     * requestModelMapFromWeb() logs either the missing-INTERNET warning or, once the async request
     * settles, the download outcome. Any of them means the method was entered.
     */
    private boolean reachedRemoteFetch() {
        List<ShadowLog.LogItem> logs = ShadowLog.getLogsForTag(TAG);

        for (ShadowLog.LogItem log : logs) {
            if (log.msg == null) continue;
            if (log.msg.contains("android.permission.INTERNET")) return true;
            if (log.msg.contains("Cannot updated distance models from online database")) return true;
            if (log.msg.contains("Successfully downloaded distance models")) return true;
        }

        return false;
    }
}
