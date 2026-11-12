package org.altbeacon.beacon;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

/**
 * BluetoothDevice.getName() is a call to the Bluetooth service, made for every packet a parser
 * decodes. A parser whose beacons do not need the name can skip it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BeaconParserBluetoothNameTest {

    private static final String ADDRESS = "00:11:22:33:44:55";
    private static final String NAME = "beacon-name";
    private static final byte[] ALTBEACON_PACKET = AltBeaconParserTest.hexStringToByteArray(
            "02011a1bff1801beac2f234454cf6d4a0fadf2f4911ba9ffa600010002c50900");

    private BluetoothDevice device;

    @Before
    public void before() {
        device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(ADDRESS);
        shadowOf(device).setName(NAME);
    }

    @Test
    public void readsTheBluetoothNameByDefault() {
        Beacon beacon = new AltBeaconParser().fromScanData(ALTBEACON_PACKET, -55, device, 0L);

        assertNotNull("the packet is decoded", beacon);
        assertEquals("the name is read", NAME, beacon.getBluetoothName());
    }

    @Test
    public void skipsTheBluetoothNameOnlyForAParserThatTurnedItOff() {
        BeaconParser withName = new AltBeaconParser();
        BeaconParser withoutName = new AltBeaconParser();
        withoutName.setBluetoothNameReadingEnabled(false);

        Beacon named = withName.fromScanData(ALTBEACON_PACKET, -55, device, 0L);
        Beacon unnamed = withoutName.fromScanData(ALTBEACON_PACKET, -55, device, 0L);

        assertEquals("the other parser still reads the name", NAME, named.getBluetoothName());
        assertNull("this parser does not", unnamed.getBluetoothName());
        assertEquals("the address is still read", ADDRESS, unnamed.getBluetoothAddress());
        assertEquals("the identifiers are still decoded",
                named.getIdentifiers(), unnamed.getIdentifiers());
    }

    /**
     * ScanState saves the registered parsers, and a process woken before the app configures the
     * library decodes with the ones it restores.
     */
    @Test
    public void keepsTheSettingThroughSerialization() throws Exception {
        BeaconParser parser = new AltBeaconParser();
        parser.setBluetoothNameReadingEnabled(false);

        BeaconParser restored = roundTrip(parser);

        assertFalse("the restored parser still skips the name",
                restored.isBluetoothNameReadingEnabled());
        assertNull(restored.fromScanData(ALTBEACON_PACKET, -55, device, 0L).getBluetoothName());
        assertEquals("and one left at the default still reads it",
                NAME, roundTrip(new AltBeaconParser())
                        .fromScanData(ALTBEACON_PACKET, -55, device, 0L).getBluetoothName());
    }

    private static BeaconParser roundTrip(BeaconParser parser) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bytes);
        out.writeObject(parser);
        out.close();
        ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        return (BeaconParser) in.readObject();
    }
}
