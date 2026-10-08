package net.fstab.tachiai.platform.network;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

// Runs standalone in the test APK process, which does not inherit the target's Kotlin runtime.
public class ConnectionFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor result = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
        result.addRow(new Object[] { "fixture.conf", bytes(uri).length });
        return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        String path = uri.getLastPathSegment();
        if ("slice".equals(path) || "truncated".equals(path)) {
            if (!"r".equals(mode)) throw new java.io.FileNotFoundException();
            byte[] data = bytes(Uri.parse("content://net.fstab.fixture.connections/proxy"));
            byte[] prefix = "ignored prefix".getBytes(StandardCharsets.UTF_8);
            boolean truncated = "truncated".equals(path);
            File file = new File(getContext().getCacheDir(), "connection-" + path + ".fixture");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(prefix); output.write(data);
                if (!truncated) output.write("ignored suffix".getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) { throw new java.io.FileNotFoundException("Fixture write failed"); }
            return new AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                prefix.length, data.length + (truncated ? 1 : 0));
        }
        return new AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        if (!"r".equals(mode)) throw new java.io.FileNotFoundException();
        final byte[] data = bytes(uri);
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException error) { throw new java.io.FileNotFoundException("Fixture pipe failed"); }
        new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                if ("slow".equals(uri.getLastPathSegment())) Thread.sleep(1000);
                output.write(data);
            } catch (Exception ignored) { /* Timed/cancelled reader may close the pipe. */ }
        }).start();
        return pipe[0];
    }
    private byte[] bytes(Uri uri) {
        String path = uri.getLastPathSegment();
        if ("oversized".equals(path)) { byte[] data = new byte[8193]; Arrays.fill(data, (byte) 65); return data; }
        if ("invalid-utf8".equals(path)) return new byte[] { (byte) 0xC0, (byte) 0xAF };
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) keyBytes[i] = (byte) (i + 1);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String text;
        if ("wireguard".equals(path)) text = "[Interface]\nPrivateKey = " + key + "\nAddress = 10.2.0.2/32\nDNS = 10.2.0.1\n[Peer]\nPublicKey = " + key + "\nAllowedIPs = 0.0.0.0/0\nEndpoint = vpn.example.test:51820";
        else if ("proxy".equals(path)) text = "http://fixture:synthetic-password@proxy.example.test:3128";
        else text = "not a configuration";
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
