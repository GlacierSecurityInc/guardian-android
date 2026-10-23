package com.glaciersecurity.glaciermessenger;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * NOT_EXPORTED receivers only hear root, system_server (uid 1000) and the app itself. Bluetooth
 * broadcasts are sent by com.android.bluetooth (uid 1002), so a NOT_EXPORTED receiver for them
 * registers fine and then never fires -- no crash, no log line from the app. A blanket lint sweep
 * in July 2026 did exactly that to both call screens, which dropped G2G calls off the headset.
 *
 * <p>Neither call screen can be launched under Robolectric, and Robolectric ignores export flags
 * anyway, so this reads the sources: a file that adds a Bluetooth action to an IntentFilter must
 * not register anything NOT_EXPORTED. A file holding an unrelated NOT_EXPORTED receiver as well
 * would trip it; split that receiver out rather than weakening the check.
 */
public class BluetoothReceiverExportTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern BLUETOOTH_ACTION =
            Pattern.compile("addAction\\(\\s*Bluetooth(Adapter|Device|Headset|A2dp)\\.ACTION_");

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    public void bluetoothReceiversAreNotRegisteredNotExported() throws IOException {
        final List<Path> sources = javaSources();
        assertThat(sources).isNotEmpty();

        int bluetoothFiles = 0;
        for (final Path source : sources) {
            final String code = Files.readString(source);
            if (!BLUETOOTH_ACTION.matcher(code).find()) {
                continue;
            }
            bluetoothFiles++;
            assertWithMessage(source + " registers Bluetooth actions NOT_EXPORTED")
                    .that(code.contains("RECEIVER_NOT_EXPORTED"))
                    .isFalse();
        }
        // guards the scan itself: AppRTCBluetoothManager and ExternalCallActivity both qualify
        assertThat(bluetoothFiles).isAtLeast(2);
    }
}
