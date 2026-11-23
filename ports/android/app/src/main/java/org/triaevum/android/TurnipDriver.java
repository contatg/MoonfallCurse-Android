package org.triaevum.android;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** An optional, user-imported ARM64 Vulkan driver, shared by the launcher and game processes. */
public final class TurnipDriver {
    private static final String DRIVER = "libturnip.so";
    private static final long MAX_BYTES = 128L * 1024 * 1024;

    private TurnipDriver() {}

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "turnip-driver");
    }

    public static boolean available(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libtriaevum_vulkan.so").isFile();
    }

    public static boolean imported(Context context) {
        return new File(directory(context), DRIVER).isFile();
    }

    public static boolean useSystem(Context context) {
        File driver = new File(directory(context), DRIVER);
        return driver.delete() || !driver.exists();
    }

    public static boolean select(Context context) {
        if (!available(context) || !imported(context)) return false;
        try {
            System.loadLibrary("triaevum_vulkan");
            return selectNative(context.getApplicationInfo().nativeLibraryDir,
                directory(context).getAbsolutePath());
        } catch (UnsatisfiedLinkError error) {
            Log.w("TriAevum", "Custom Vulkan loader unavailable", error);
            return false;
        }
    }

    private static native boolean selectNative(String nativeDir, String driverDir);

    public static void importZip(Context context, InputStream source) throws IOException, JSONException {
        importZip(directory(context), source);
    }

    static void importZip(File root, InputStream source) throws IOException, JSONException {
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IOException("Cannot create the private driver directory");
        }
        File archive = File.createTempFile("package-", ".zip", root);
        try {
            try (OutputStream output = new FileOutputStream(archive)) {
                copyLimited(source, output, MAX_BYTES);
            }
            try (ZipFile zip = new ZipFile(archive)) {
                ZipEntry entry = findLibrary(zip);
                install(root, zip, entry);
            }
        } finally {
            Files.deleteIfExists(archive.toPath());
        }
    }

    private static ZipEntry findLibrary(ZipFile zip) throws IOException, JSONException {
        if (zip.size() > 256) throw new IOException("Too many files in driver ZIP");
        ZipEntry metadata = null;
        ZipEntry singleLibrary = null;
        int libraries = 0;
        for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory()) continue;
            if (name.equals("meta.json") || name.endsWith("/meta.json")) {
                if (metadata != null) throw new IOException("Ambiguous driver metadata");
                metadata = entry;
            } else if (name.endsWith(".so")) {
                singleLibrary = entry;
                libraries++;
            }
        }
        if (metadata == null) {
            if (libraries == 1) return singleLibrary;
            throw new IOException("Expected one .so or meta.json with libraryName");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = zip.getInputStream(metadata)) {
            copyLimited(input, bytes, 64 * 1024);
        }
        String name = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getString("libraryName");
        if (!name.matches("[A-Za-z0-9_.+-]+\\.so")) throw new IOException("Invalid Vulkan library name");
        String path = metadata.getName();
        String wanted = path.substring(0, path.lastIndexOf('/') + 1) + name;
        ZipEntry library = null;
        for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().equals(wanted)) {
                if (library != null) throw new IOException("Duplicate Vulkan library");
                library = entry;
            }
        }
        if (library == null) throw new IOException("Vulkan library missing from driver ZIP");
        return library;
    }

    private static void install(File root, ZipFile zip, ZipEntry entry) throws IOException {
        File temporary = File.createTempFile("driver-", ".tmp", root);
        try {
            try (DataInputStream input = new DataInputStream(zip.getInputStream(entry));
                 FileOutputStream output = new FileOutputStream(temporary)) {
                // Keep the loadable file read-only starting before the first write.
                if (!temporary.setReadOnly()) throw new IOException("Cannot protect Vulkan driver file");
                byte[] header = new byte[64];
                input.readFully(header);
                if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' ||
                    header[4] != 2 || header[5] != 1 || header[16] != 3 || header[17] != 0 ||
                    (header[18] & 0xff) != 183 || header[19] != 0) {
                    throw new IOException("Expected an ARM64 ELF shared library");
                }
                output.write(header);
                copyLimited(input, output, MAX_BYTES - header.length);
                output.getFD().sync();
            }
            // A malformed replacement cannot change the previously installed driver.
            Files.move(temporary.toPath(), new File(root, DRIVER).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static void copyLimited(InputStream input, OutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        for (int count; (count = input.read(buffer)) != -1;) {
            remaining -= count;
            if (remaining < 0) throw new IOException("Driver ZIP exceeds size limit");
            output.write(buffer, 0, count);
        }
    }
}
