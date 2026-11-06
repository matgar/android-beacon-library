package org.altbeacon.beacon.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Region
import org.altbeacon.beacon.logging.LogManager
import java.util.*
import java.util.concurrent.Executors

class IntentScanStrategyCoordinator(val context: Context) {
    private lateinit var scanHelper: ScanHelper
    private lateinit var scanState: ScanState
    @Volatile private var initialized = false
    private val initializationLock = Any()
    private var started = false
    private var longScanForcingEnabled = false
    // Cycle bookkeeping is kept in SystemClock.elapsedRealtime(), which neither jumps when the
    // wall clock is adjusted nor stops while the CPU sleeps.
    private var lastCycleEnd: Long = 0
    private var lastArrival: Long = 0
    var strategyFailureDetectionCount = 0
    var lastStrategyFailureDetectionCount = 0
    var disableOnFailure = false
    val executor = Executors.newFixedThreadPool(1)
    private val handler = Handler(Looper.getMainLooper())

    // Dedicated single-thread executor for BLE scan control (start/stop/restart).  Kept separate
    // from `executor`, which the backup scan can occupy for up to ~35s (sleep + detection loop).
    // Single-thread => FIFO ordering, so start/stop never reorder and the scan ends in the
    // requested state.  INVARIANT: every scanHelper.start/stopAndroidOBackgroundScan call inside
    // this class MUST go through submitStartScan/submitStopScan, or that ordering guarantee breaks.
    private val scanCommandExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var bleScanRunning = false
    // SystemClock.elapsedRealtime() of the last scan start, or -1 before the first one.
    @Volatile private var lastScanStartMillis = -1L

    // A scan that could not start, or that died, while Bluetooth was not on is started again as
    // soon as Bluetooth is on, instead of waiting for the next periodic job.
    private var bluetoothStateReceiverRegistered = false
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                LogManager.d(TAG, "Bluetooth is on: restarting background scan")
                submitStartScanForRegisteredSetup()
            }
        }
    }

    // Runs the blocking startScan IPC off the calling thread.  On Android 13+ the Bluetooth stack
    // is a Mainline/APEX module and startScan blocks the caller on a synchronous IPC; doing it on
    // the main thread (e.g. from Application.onCreate via bindInternal) causes startup ANRs.
    private fun submitStartScan(parsers: Set<BeaconParser>, regions: List<Region>?) {
        scanCommandExecutor.execute {
            try {
                // Neither stop nor start unless Bluetooth is fully on. A scan already running keeps
                // delivering results with Bluetooth off (#1148), while a new one cannot start until
                // it is on again: stopping first would leave no scan at all.
                if (!isBluetoothOn()) {
                    LogManager.i(TAG, "Not starting background scan because bluetooth is not on")
                    return@execute
                }
                // Nor while a new scan would receive nothing: stopping first would replace a scan
                // that still delivers with one that does not.
                if (!canReceiveScanResultsNow()) {
                    LogManager.i(TAG, "Not starting background scan because a scan started now would get no results: location access is missing, or allowed only while the app is in the foreground")
                    return@execute
                }
                if (bleScanRunning) {
                    scanHelper.stopAndroidOBackgroundScan()
                    bleScanRunning = false
                }
                if (regions != null) {
                    scanHelper.startAndroidOBackgroundScan(parsers, regions)
                } else {
                    scanHelper.startAndroidOBackgroundScan(parsers)
                }
                bleScanRunning = true
                lastScanStartMillis = SystemClock.elapsedRealtime()
            } catch (t: Throwable) {
                LogManager.e(TAG, "Failed to start background scan off the calling thread", t)
            }
        }
    }

    /**
     * Whether a scan registered now would receive results. Since Android 10 an app needs
     * ACCESS_FINE_LOCATION to receive Bluetooth LE scan results, unless it declares BLUETOOTH_SCAN
     * with neverForLocation. Without ACCESS_BACKGROUND_LOCATION that access only holds while the
     * app is in the foreground, and the Bluetooth stack checks it when the scan is registered: a
     * scan registered from the background stays registered but never delivers a result, while one
     * registered in the foreground keeps delivering after the app leaves it. Seen on a Galaxy A15
     * (Android 15) with location allowed only while in use: the periodic job's restart logged
     * "Permission denial: Need ACCESS_FINE_LOCATION permission to get scan results" and the scan
     * got nothing for 8 minutes with a beacon in range.
     */
    private fun canReceiveScanResultsNow(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }
        if (scanPermissionDisavowsLocation) {
            return true
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        return isAppInForeground()
    }

    // Read once: the manifest does not change while the app is installed, and reading it is an IPC.
    private val scanPermissionDisavowsLocation by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && readScanPermissionDisavowsLocation()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun readScanPermissionDisavowsLocation(): Boolean {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            val index = info.requestedPermissions?.indexOf(Manifest.permission.BLUETOOTH_SCAN) ?: -1
            index >= 0 && (info.requestedPermissionsFlags[index] and PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isAppInForeground(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager? ?: return false
        val pid = android.os.Process.myPid()
        val process = activityManager.runningAppProcesses?.firstOrNull { it.pid == pid } ?: return false
        return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun isBluetoothOn(): Boolean {
        return try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
            manager?.adapter?.state == BluetoothAdapter.STATE_ON
        } catch (e: SecurityException) {
            LogManager.w(TAG, "SecurityException checking if bluetooth is on")
            false
        }
    }

    private fun submitStopScan() {
        scanCommandExecutor.execute {
            try {
                scanHelper.stopAndroidOBackgroundScan()
                bleScanRunning = false
            } catch (t: Throwable) {
                LogManager.e(TAG, "Failed to stop background scan off the calling thread", t)
            }
        }
    }

    private val scanPeriod: Long
        get() = with(BeaconManager.getInstanceForApplication(context)) {
            if (backgroundMode) backgroundScanPeriod else foregroundScanPeriod
        }

    private val betweenScanPeriod: Long
        get() = with(BeaconManager.getInstanceForApplication(context)) {
            if (backgroundMode) backgroundBetweenScanPeriod else foregroundBetweenScanPeriod
        }

    private val cyclePeriod
        get() = scanPeriod + betweenScanPeriod

    // The library enters this class from the main thread (scan broadcasts, foreground transitions),
    // from the scan job's own thread, and from whatever thread the app starts ranging on. An entry
    // made while another thread is still initializing must wait for it, not carry on with a half
    // initialized coordinator: that crashed with a null monitoring status in runBackupScan, or
    // with scanHelper or scanState not yet initialized. So initialized only turns true once
    // everything is assigned, and is volatile so that seeing it true also means seeing them.
    fun ensureInitialized() {
        if (initialized) {
            return
        }
        synchronized(initializationLock) {
            if (!initialized) {
                scanHelper = ScanHelper(context)
                loadScanState()
                initialized = true
            }
        }
    }
    fun reinitialize() {
        if (!initialized) {
            ensureInitialized() // this loads the scan state
            return
        }
        loadScanState()
    }

    private fun loadScanState() {
        var newScanState = ScanState.restore(context)
        if (newScanState == null) {
            newScanState = ScanState(context)
        }
        scanState = newScanState
        scanState.setLastScanStartTimeMillis(System.currentTimeMillis())

        scanHelper.monitoringStatus = scanState.getMonitoringStatus()
        scanHelper.rangedRegionState = scanState.getRangedRegionState()
        scanHelper.setBeaconParsers(scanState.getBeaconParsers())
        scanHelper.setExtraDataBeaconTracker(scanState.getExtraBeaconDataTracker())

        var longScanForcingEnabled = BeaconManager.getInstanceForApplication(context).getActiveSettings().longScanForcingEnabled

        // Legacy code to pull value from manifest if not set in settings.  TODO: Remove this block in 3.0
        val longScanForcingEnabledString =  getManifestMetadataValue("longScanForcingEnabled")
        if (longScanForcingEnabledString != null && longScanForcingEnabledString == "true") {
            org.altbeacon.beacon.logging.LogManager.w(
                BeaconService.TAG,
                "Setting longScanForcingEnabled in the AndroidManifest.xml is deprecated for AndoridBeaconLibrary.  Please set this value using the Settings API."
            )
            longScanForcingEnabled = true
        }

        if (longScanForcingEnabled) {
            LogManager.i(
                BeaconService.TAG,
                "longScanForcingEnabled to keep scans going on Android N for > 30 minutes"
            )
        }
        this.longScanForcingEnabled = longScanForcingEnabled
    }

    fun applySettings() {
        ensureInitialized()
        scanState.applyChanges(BeaconManager.getInstanceForApplication(context))
        reinitialize()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            restartBackgroundScan()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun start() {
        started = true
        ensureInitialized()
        val beaconManager =
            BeaconManager.getInstanceForApplication(context)
        scanHelper.setExtraDataBeaconTracker(ExtraDataBeaconTracker())
        beaconManager.setScannerInSameProcess(true)
        scanHelper.reloadParsers()
        LogManager.d(TAG, "starting background scan")
        submitStartScanForRegisteredSetup()
        if (!bluetoothStateReceiverRegistered) {
            try {
                // A system broadcast, so no RECEIVER_EXPORTED flag is needed on Android 14+. Should
                // a device disagree, the periodic job still restarts the scan.
                context.applicationContext.registerReceiver(bluetoothStateReceiver,
                        IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
                bluetoothStateReceiverRegistered = true
            } catch (e: SecurityException) {
                LogManager.w(e, TAG, "Cannot listen for bluetooth state changes")
            }
        }
//        lastCycleEnd = System.currentTimeMillis()
        ScanJobScheduler.getInstance().scheduleForIntentScanStrategy(context)
    }

    /**
     * Starts the PendingIntent scan, stopping a running one first, for the parsers and regions
     * registered with the BeaconManager.
     */
    private fun submitStartScanForRegisteredSetup() {
        val beaconManager = BeaconManager.getInstanceForApplication(context)
        // Filters come from the registered parsers rather than from scanState's, which are empty
        // after a failed restore and stale until applySettings() saves the registered ones. The
        // copy also keeps the set iterated on the executor thread apart from the caller's.
        submitStartScan(HashSet(beaconManager.beaconParsers),
                regionsForScanFilters(beaconManager.rangedRegions, beaconManager.monitoredRegions))
    }

    private fun getManifestMetadataValue(key: String): String? {
        val value: String? = null
        try {
            val info: PackageItemInfo = context.getPackageManager().getServiceInfo(
                ComponentName(
                    context,
                    BeaconService::class.java
                ), PackageManager.GET_META_DATA
            )
            if (info != null && info.metaData != null) {
                return info.metaData[key].toString()
            }
        } catch (e: PackageManager.NameNotFoundException) {
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun stop() {
        ensureInitialized()
        LogManager.d(TAG, "stopping background scan")
        if (bluetoothStateReceiverRegistered) {
            try {
                context.applicationContext.unregisterReceiver(bluetoothStateReceiver)
            } catch (e: IllegalArgumentException) {
                // not registered after all
            }
            bluetoothStateReceiverRegistered = false
        }
        submitStopScan()
        ScanJobScheduler.getInstance().cancelSchedule(context)
        started = false
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun restartBackgroundScan() {
        ensureInitialized()
        LogManager.d(TAG, "restarting background scan")
        // submitStartScan does stop-before-start via bleScanRunning, serialized on the same
        // single-thread executor, so the restart cannot reorder against an in-flight start.
        submitStartScan(HashSet(scanState.getBeaconParsers()), null)
    }
    @RequiresApi(Build.VERSION_CODES.O)
    fun processScanResults(scanResults: ArrayList<ScanResult?>) {
        ensureInitialized()
        // The parser set restored from ScanState is whatever was persisted last: empty if the
        // restore failed, or older than the set the app registers at runtime. Reconcile with the
        // registered set so decoding does not depend on which entry point initialized this
        // coordinator. Only the parsers are replaced; the extra data beacon tracker is kept.
        scanHelper.reconcileParsers()
        val now = SystemClock.elapsedRealtime()
        LogManager.d(TAG, "Processing scan results. lastArrival: $lastArrival. Setting to $now")
        lastArrival = now

        val cycleOverdue = now - lastCycleEnd > cyclePeriod

        for (scanResult in scanResults) {
            if (scanResult != null) {
                //LogManager.d(TAG, "Got scan result: "+scanResult)
                scanHelper.processScanResult(scanResult.device, scanResult.rssi, scanResult.scanRecord?.bytes, scanResult.timestampNanos/1000)
            }
        }

        // Close an overdue cycle now, with this batch in it, rather than leaving it to the handler.
        // The handler counts uptime, which stops while the CPU sleeps, and nothing holds the CPU
        // awake once the broadcast receiver or the scan job returns. With the screen off and
        // broadcasts further apart than a cycle, the CPU sleeps between them, so a deferred cycle
        // end never ran: results kept arriving and no ranging callback fired for as long as the
        // CPU slept. This also lets the periodic scan job close the cycle before it finishes.
        //
        // processScanResult only hands each result to the processing threads, so wait for them
        // first, or the cycle closes before this batch reaches the range state: the batch would
        // then be reported a broadcast late, or never if the process is killed before the next
        // cycle end. Waiting inside the receiver is what keeps the process running meanwhile.
        if (cycleOverdue) {
            if (!scanHelper.awaitScanProcessing(SCAN_PROCESSING_TIMEOUT_MILLIS)) {
                LogManager.w(TAG, "Closing an overdue scan cycle before all of its results were processed")
            }
            endCycle(now)
        }
        scheduleCycleEnd()
    }

    private fun endCycle(now: Long) {
        LogManager.d(TAG, "End of scan cycle")
        lastCycleEnd = now
        scanHelper.getCycledLeScanCallback().onCycleEnd()
    }

    private fun scheduleCycleEnd() {
        LogManager.d(TAG, "scheduleCycleEnd")
        val now = SystemClock.elapsedRealtime()

        val msProcessingInterval = lastArrival + scanPeriod - now
        val msNotificationInterval = lastCycleEnd + cyclePeriod - now

        if (msNotificationInterval <= 0) {
            endCycle(now)
        }

        if (msProcessingInterval > 0 || msNotificationInterval > 0) {
            val interval = lastCycleEnd + cyclePeriod - now
            LogManager.d(TAG, "Rescheduling scan cycle end check. interval: $interval")
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                scheduleCycleEnd()
            }, if (interval > 1000) 1000 else interval)
        }
    }

    fun performPeriodicProcessing(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            processScanResults(ArrayList<ScanResult?>())
            if (started) {
                // Nothing reports whether the PendingIntent scan is alive: it may have failed to
                // start (no free scanner slot, a permission granted only later) or died with
                // Bluetooth, and only start() and applySettings() would start it again, which a
                // process in the background may not see for hours. Restart it on every periodic
                // job, so that it is back within one job interval at worst.
                //
                // Skip it when the scan was started moments ago. This also runs on every move to
                // the foreground, right where applySettings() restarts the scan as well, and
                // Android stops honoring scan starts from an app that starts more than five in
                // 30 seconds.
                val msSinceLastStart = SystemClock.elapsedRealtime() - lastScanStartMillis
                if (lastScanStartMillis < 0 || msSinceLastStart >= MIN_PERIODIC_RESTART_INTERVAL_MILLIS) {
                    LogManager.d(TAG, "restarting background scan from the periodic job")
                    submitStartScanForRegisteredSetup()
                } else {
                    LogManager.d(TAG, "Not restarting background scan from the periodic job: started $msSinceLastStart ms ago")
                }
            }
            // Use this opportunity to run a brief scan without a filter to see if we are able to
            // detect something without a filter.  This would be an indication that the filtered
            // scan used in this strategy won't work
            runBackupScan(context)
        }

    }

    @RequiresApi(21)
    @SuppressLint("MissingPermission")
    fun runBackupScan(context: Context) {
        if (!started) {
            LogManager.i(TAG, "Not doing backup scan because we are not started")
            return
        }
        val anythingDetectedWithIntentScan = scanHelper.anyBeaconsDetectedThisCycle()
        if (anythingDetectedWithIntentScan) {
            LogManager.d(TAG, "We have detected beacons with the intent scan.  No need to do a backup scan.")
            strategyFailureDetectionCount = 0
            lastStrategyFailureDetectionCount = 0
            return
        }
        LogManager.i(TAG, "Starting background thread to do backup scan")
        executor.execute {

            // give the intent scan 5 seconds to detect beacons before starting the backup scan
            try {
                Thread.sleep(5000L)
            } catch (e: InterruptedException) { /* do nothing */
            }

            val anythingDetectedWithIntentScan = scanHelper.anyBeaconsDetectedThisCycle()
            if (anythingDetectedWithIntentScan) {
                LogManager.i(TAG, "We have belatedly detected beacons with the intent scan.  No need to do a backup scan.")
                strategyFailureDetectionCount = 0
                lastStrategyFailureDetectionCount = 0
            }
            else {
                LogManager.i(TAG, "Starting backup scan")
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = manager.adapter
                var beaconDetected = false
                val scanStartTime = System.currentTimeMillis()

                if (adapter != null) {
                    val scanner: BluetoothLeScanner? = adapter.getBluetoothLeScanner()
                    // Checked after the sleep, right before starting. In BLE_ON (Bluetooth off,
                    // "Bluetooth scanning" on) getState() reports STATE_OFF, and a scan started
                    // there leaves an orphaned scanner registration that outlives the process and,
                    // with enough of them, blocks scanning for every app on the device.
                    if (adapter.state != BluetoothAdapter.STATE_ON) {
                        LogManager.d(TAG, "Bluetooth is not on.  Cannot run backup scan")
                    } else if (scanner != null) {
                        val callback: ScanCallback = @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
                        object : ScanCallback() {
                            override fun onScanResult(callbackType: Int, result: ScanResult) {
                                super.onScanResult(callbackType, result)
                                scanHelper.processScanResult(
                                    result.device,
                                    result.rssi,
                                    result.scanRecord?.bytes,
                                    result.timestampNanos
                                )
                                beaconDetected = true
                                try {
                                    scanner.stopScan(this)
                                } catch (e: IllegalStateException) { /* do nothing */
                                } // caught if bluetooth is off here
                            }

                            override fun onBatchScanResults(results: List<ScanResult>) {
                                super.onBatchScanResults(results)
                            }

                            override fun onScanFailed(errorCode: Int) {
                                super.onScanFailed(errorCode)
                                LogManager.d(TAG, "Sending onScanFailed event")
                            }
                        }

                        try {
                            scanner.startScan(callback)
                            while (!beaconDetected) {
                                LogManager.d(TAG, "Waiting for beacon detection...")
                                try {
                                    Thread.sleep(1000L)
                                } catch (e: InterruptedException) { /* do nothing */
                                }
                                if (System.currentTimeMillis() - scanStartTime > 30000L) {
                                    LogManager.d(TAG, "Timeout running backup scan to look for beacons")
                                    break
                                }
                            }
                            scanner.stopScan(callback)
                            if (beaconDetected) {
                                // We have detected beacons with the backup scan but we failed to do so with the regular scan
                                // this indicates a failure in the intent scanning technique.
                                if (strategyFailureDetectionCount == lastStrategyFailureDetectionCount) {
                                    LogManager.e(
                                        TAG,
                                        "We have detected a beacon with the backup scan without a filter.  We never detected one with the intent scan with a filter.  This technique will not work."
                                    )
                                }
                                lastStrategyFailureDetectionCount = strategyFailureDetectionCount
                                strategyFailureDetectionCount++
                            }

                        } catch (e: IllegalStateException) {
                            LogManager.d(TAG, "Bluetooth is off.  Cannot run backup scan")
                        } catch (e: NullPointerException) {
                            // Needed to stop a crash caused by internal NPE thrown by Android.  See issue #636
                            LogManager.e(TAG, "NullPointerException. Cannot run backup scan", e)
                        } catch (e: SecurityException) {
                            // Maybe permissions are not granted?
                            LogManager.e(TAG, "SecurityException. Cannot run backup scan", e)
                        }
                    } else {
                        LogManager.d(TAG, "Cannot get scanner")
                    }
                }
                LogManager.d(TAG, "backup scan complete")
                if (disableOnFailure && strategyFailureDetectionCount > 0) {
                    BeaconManager.getInstanceForApplication(context).handleStategyFailover()
                }
                // Call this a  second time to clear out beacons detected in the log 5-25 minute region
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    this@IntentScanStrategyCoordinator.processScanResults(ArrayList<ScanResult?>())
                }
            }
        }
    }

    companion object {
        val TAG = "IntentScanCoord"

        /** The periodic job does not restart a scan started more recently than this. */
        @VisibleForTesting
        const val MIN_PERIODIC_RESTART_INTERVAL_MILLIS = 60000L

        /**
         * The regions to build scan filters for, or null to build them from the beacon parsers
         * alone. An empty list must never be returned: ScanFilterUtils builds filters per region,
         * so it would yield no filters at all and an unfiltered scan, which Android blocks while
         * the screen is off.
         */
        @JvmStatic
        @VisibleForTesting
        fun regionsForScanFilters(rangedRegions: Collection<Region>, monitoredRegions: Collection<Region>): List<Region>? {
            var regions = HashSet<Region>()
            val wildcardRegions = HashSet<Region>()
            for (region in rangedRegions + monitoredRegions) {
                if (region.identifiers.size == 0) {
                    wildcardRegions.add(region)
                }
                else {
                    regions.add(region)
                }
            }
            if (wildcardRegions.size > 0) {
                if (regions.size > 0) {
                    LogManager.w(TAG, "Wildcard regions are being used for beacon ranging or monitoring.  The wildcard regions are ignored with intent scan strategy active.")
                }
                else {
                    regions = wildcardRegions
                }
            }
            return if (regions.isEmpty()) null else ArrayList(regions)
        }

        /**
         * How long a broadcast that closes an overdue cycle waits for its batch to be processed.
         * Well within the time Android allows a broadcast receiver.
         */
        private const val SCAN_PROCESSING_TIMEOUT_MILLIS = 1000L
    }
}