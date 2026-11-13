/**
 * Radius Networks, Inc.
 * http://www.radiusnetworks.com
 *
 * @author David G. Young
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.altbeacon.beacon.service;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.logging.LogManager;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class RangeState implements Serializable {
    // Fixed: this class is saved with ScanState. See there before changing it.
    private static final long serialVersionUID = 1L;
    private static final String TAG = "RangeState";
    private Callback mCallback;
    private Map<Beacon,RangedBeacon> mRangedBeacons = new HashMap<Beacon,RangedBeacon>();
    private static boolean sUseTrackingCache = false;

    public RangeState(Callback c) {
        mCallback = c;
    }

    public Callback getCallback() {
        return mCallback;
    }

    public void addBeacon(Beacon beacon) {
        RangedBeacon rangedBeacon = mRangedBeacons.get(beacon);
        if (rangedBeacon != null) {
            if (LogManager.isVerboseLoggingEnabled()) {
                LogManager.d(TAG, "adding %s to existing range for: %s", beacon, rangedBeacon);
            }
            rangedBeacon.updateBeacon(beacon);
        }
        else {
            if (LogManager.isVerboseLoggingEnabled()) {
                LogManager.d(TAG, "adding %s to new rangedBeacon", beacon);
            }
            mRangedBeacons.put(beacon, new RangedBeacon(beacon));
        }
    }

    public int count() {
        synchronized (mRangedBeacons) {
            return mRangedBeacons.size();
        }
    }

    // returns a list of beacons that are tracked, and then removes any from the list that should not
    // be there for the next cycle
    //
    // Iterates entries rather than looking each key up again, because Beacon.hashCode() is not
    // stable for the lifetime of an entry: it includes the bluetooth address only while
    // Beacon.setHardwareEqualityEnforced(true) is in effect, and that is a static setting an app
    // may turn on at any point. A lookup made after it changed hashes the key into a different
    // bucket and returns null for every entry, which silently dropped the whole cycle's ranging
    // data and then replaced the map with an empty one, discarding the measurements as well. An
    // app that enables hardware equality while ranging is already under way -- a process woken by
    // the scan PendingIntent, where the ScanJob decodes a batch before the app has configured the
    // library -- lost that batch entirely.
    public synchronized Collection<Beacon> finalizeBeacons() {
        Map<Beacon,RangedBeacon> newRangedBeacons = new HashMap<Beacon,RangedBeacon>();
        ArrayList<Beacon> finalizedBeacons = new ArrayList<Beacon>();

        synchronized (mRangedBeacons) {
            for (Map.Entry<Beacon,RangedBeacon> entry : mRangedBeacons.entrySet()) {
                Beacon beacon = entry.getKey();
                RangedBeacon rangedBeacon = entry.getValue();
                if (rangedBeacon != null) {
                    if (rangedBeacon.isTracked()) {
                        rangedBeacon.commitMeasurements(); // calculates accuracy
                        if (!rangedBeacon.noMeasurementsAvailable()) {
                            finalizedBeacons.add(rangedBeacon.getBeacon());
                        }
                    }
                    // If we still have useful measurements, keep it around but mark it as not
                    // tracked anymore so we don't pass it on as visible unless it is seen again.
                    //
                    // The filter only expires its samples when it calculates, which happens in
                    // commitMeasurements above, for tracked beacons only. Once untracked, a beacon
                    // that is never seen again keeps its last samples forever, so judge an
                    // untracked one by when it was last seen, against the same expiration the
                    // filter uses. With the default filter that is the same test, made without
                    // relying on the filter to have expired anything; with a filter that never
                    // reports running out of measurements, it is the only one. A negative age
                    // comes from a range state restored from an earlier boot, where
                    // elapsedRealtime started over, so it is just as stale.
                    long trackingAge = rangedBeacon.getTrackingAge();
                    if (!rangedBeacon.noMeasurementsAvailable()
                            && trackingAge >= 0
                            && trackingAge < RunningAverageRssiFilter.getSampleExpirationMilliseconds()) {
                        //if TrackingCache is enabled, allow beacon to not receive
                        //measurements for a certain amount of time
                        if (!sUseTrackingCache || rangedBeacon.isExpired())
                            rangedBeacon.setTracked(false);
                        newRangedBeacons.put(beacon, rangedBeacon);
                    }
                    else {
                        LogManager.d(TAG, "Dumping beacon from RangeState because it has no recent measurements.");
                    }
                }
            }
            mRangedBeacons = newRangedBeacons;
        }

        return finalizedBeacons;
    }

    public static void setUseTrackingCache(boolean useTrackingCache) {
        RangeState.sUseTrackingCache = useTrackingCache;
    }

    public static boolean getUseTrackingCache() {
        return sUseTrackingCache;
    }

}
