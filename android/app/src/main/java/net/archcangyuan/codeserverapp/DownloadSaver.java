package net.archcangyuan.codeserverapp;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Saves files downloaded from a remote desktop to the device's Downloads. */
final class DownloadSaver {
    private static final int MAX_NAME_LENGTH = 120;

    private DownloadSaver() {}

    /** Returns where the file went, for display. */
    static String save(Context context, String name, InputStream content, long length)
        throws IOException {
        String fileName = safeName(name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveToMediaStore(context, fileName, content);
        }
        // Android 8 and 9 would need the storage permission for the public
        // Downloads folder; use the app's own one there.
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            throw new IOException("No storage available");
        }
        File target = uniqueFile(directory, fileName);
        try (OutputStream output = new FileOutputStream(target)) {
            copy(content, output);
        }
        return target.getAbsolutePath();
    }

    private static String saveToMediaStore(Context context, String fileName, InputStream content)
        throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType(fileName));
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Could not create the download");
        }
        try (OutputStream output = resolver.openOutputStream(uri)) {
            if (output == null) {
                throw new IOException("Could not open the download");
            }
            copy(content, output);
        } catch (IOException | RuntimeException exception) {
            resolver.delete(uri, null, null);
            throw exception;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        // MediaStore may rename duplicates ("name (1).ext"): report the real name.
        String savedName = fileName;
        try (android.database.Cursor cursor = resolver.query(
            uri,
            new String[] { MediaStore.Downloads.DISPLAY_NAME },
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                savedName = cursor.getString(0);
            }
        }
        return "Downloads/" + savedName;
    }

    /** Keeps the base name only and drops characters file systems reject. */
    static String safeName(String name) {
        String base = name == null ? "" : name;
        int separator = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        base = base.substring(separator + 1)
            .replaceAll("[\\x00-\\x1f\\x7f:*?\"<>|]", "_")
            .trim();
        while (base.startsWith(".")) {
            base = base.substring(1);
        }
        if (base.isEmpty()) {
            base = "download";
        }
        if (base.length() > MAX_NAME_LENGTH) {
            int dot = base.lastIndexOf('.');
            String extension = dot > 0 && base.length() - dot <= 16 ? base.substring(dot) : "";
            base = base.substring(0, MAX_NAME_LENGTH - extension.length()) + extension;
        }
        return base;
    }

    private static String mimeType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.US) : "";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? "application/octet-stream" : type;
    }

    private static File uniqueFile(File directory, String fileName) {
        File candidate = new File(directory, fileName);
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 1; candidate.exists(); index++) {
            candidate = new File(directory, stem + " (" + index + ")" + extension);
        }
        return candidate;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
            output.write(buffer, 0, count);
        }
        output.flush();
    }
}
