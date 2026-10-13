package org.altbeacon.beacon.service;

import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.Region;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
