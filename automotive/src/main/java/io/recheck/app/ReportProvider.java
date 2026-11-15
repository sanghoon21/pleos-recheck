package io.recheck.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Exposes only the reviewed UTF-8 report through an explicit read-only URI grant. */
public final class ReportProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/report.txt".equals(uri.getPath()) || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(new File(getContext().getCacheDir(), "recheck-report.txt"), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
        File file = new File(getContext().getCacheDir(), "recheck-report.txt");
        cursor.addRow(new Object[] { "ReCheck_observation.txt", file.length() });
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
}
