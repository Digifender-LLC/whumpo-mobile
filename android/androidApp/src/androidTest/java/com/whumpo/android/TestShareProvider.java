package com.whumpo.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import java.io.IOException;

/**
 * Serves a synthetic "shared photo" to the app through a real content:// URI, on every API level. The bytes travel in the URI
 * itself (content://TEST_PACKAGE.testshares/b/BASE64URL), so no state is shared between processes and nothing is written to
 * disk or to MediaStore. MediaStore was used first and failed on API 28, where inserting needs WRITE_EXTERNAL_STORAGE (scoped
 * storage starts at API 29). Test APK only; never part of the app.
 *
 * Plain Java on purpose: a provider declared in the test APK runs in the test package's own process, which has no Kotlin runtime
 * (the Kotlin version crashed there with NoClassDefFoundError: kotlin.jvm.internal.Intrinsics).
 */
public class TestShareProvider extends ContentProvider {
    private static final int FLAGS = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;

    public static Uri uriFor(String testPackage, byte[] bytes) {
        return Uri.parse("content://" + testPackage + ".testshares/b/" + Base64.encodeToString(bytes, FLAGS));
    }

    private static byte[] bytes(Uri uri) {
        return Base64.decode(uri.getLastPathSegment(), FLAGS);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        final byte[] data = bytes(uri);
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    out.write(data);
                } catch (IOException ignored) {
                    // The reader gave up (a timeout or a refused file); nothing to do.
                }
            });
            writer.setDaemon(true);
            writer.start();
            return pipe[0];
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {"synthetic-share.jpg", bytes(uri).length});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
