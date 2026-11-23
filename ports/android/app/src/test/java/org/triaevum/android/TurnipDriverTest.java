package org.triaevum.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TurnipDriverTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void replacesTheDriverOnlyAfterSuccessfulValidation() throws Exception {
        Path folder = temporary.newFolder().toPath();
        byte[] driver = arm64Library();
        importPackage(folder, Map.of("driver/vulkan.adreno.so", driver));
        assertArrayEquals(driver, installedDriver(folder));

        byte[] invalid = driver.clone();
        invalid[18] = 62; // x86-64
        assertThrows(IOException.class, () -> importPackage(folder, Map.of("vulkan.so", invalid)));
        assertArrayEquals(driver, installedDriver(folder));

        driver[64] = 1;
        importPackage(folder, Map.of("replacement.so", driver));
        assertArrayEquals(driver, installedDriver(folder));
    }

    @Test public void rejectsAmbiguousPackagesWithoutInstallingAnything() throws Exception {
        Path folder = temporary.newFolder().toPath();
        assertThrows(IOException.class, () -> importPackage(folder,
            Map.of("vulkan1.so", arm64Library(), "vulkan2.so", arm64Library())));
        assertFalse(Files.exists(folder.resolve("libturnip.so")));
    }

    private static byte[] installedDriver(Path folder) throws IOException {
        return Files.readAllBytes(folder.resolve("libturnip.so"));
    }

    private static byte[] arm64Library() {
        byte[] driver = new byte[65];
        driver[0] = 0x7f;
        driver[1] = 'E';
        driver[2] = 'L';
        driver[3] = 'F';
        driver[4] = 2;
        driver[5] = 1;
        driver[16] = 3;
        driver[18] = (byte) 183;
        return driver;
    }

    private static void importPackage(Path folder, Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        TurnipDriver.importZip(folder.toFile(), new ByteArrayInputStream(bytes.toByteArray()));
    }
}
