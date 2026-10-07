package com.gtav.rpfmanager;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Test APK only; every document is a synthetic file in this provider's cache. */
public final class TestDocumentsProvider extends DocumentsProvider {
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};
    @Override public boolean onCreate() { return true; }
    private File root() {
        File root = new File(getContext().getCacheDir(), "saf-fixtures");
        root.mkdirs();
        return root;
    }
    private File file(String id) throws FileNotFoundException {
        if (id.equals("root")) return root();
        if (!id.startsWith("root/")) throw new FileNotFoundException("Invalid document identity");
        for (String part : id.substring(5).split("/")) checkedName(part);
        return new File(root(), id.substring(5));
    }
    private String id(File file) { return "root" + file.getAbsolutePath().substring(root().getAbsolutePath().length()); }
    private String checkedName(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))
            throw new IllegalArgumentException("Invalid document name");
        return name;
    }
    private void row(MatrixCursor cursor, File file) throws FileNotFoundException {
        if (!file.exists()) throw new FileNotFoundException(file.getName());
        Map<String, Object> values = new HashMap<>();
        values.put(Document.COLUMN_DOCUMENT_ID, id(file));
        values.put(Document.COLUMN_DISPLAY_NAME, file.getName());
        values.put(Document.COLUMN_MIME_TYPE, file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream");
        values.put(Document.COLUMN_SIZE, file.length());
        values.put(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        values.put(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME |
            (file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE));
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) row.add(values.get(column));
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection :
            new String[] {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS});
        Map<String, Object> values = new HashMap<>();
        values.put(Root.COLUMN_ROOT_ID, "root"); values.put(Root.COLUMN_DOCUMENT_ID, "root");
        values.put(Root.COLUMN_TITLE, "RPF test fixtures");
        values.put(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) row.add(values.get(column));
        return cursor;
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        row(cursor, file(documentId));
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        File[] files = file(parent).listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) row(cursor, file);
        }
        return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        File child = new File(file(parent), checkedName(name));
        try {
            if (!(mime.equals(Document.MIME_TYPE_DIR) ? child.mkdir() : child.createNewFile()))
                throw new FileNotFoundException("Output already exists");
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return id(child);
    }
    @Override public String renameDocument(String documentId, String name) throws FileNotFoundException {
        File old = file(documentId), next = new File(old.getParentFile(), checkedName(name));
        if (next.exists() || !old.renameTo(next)) throw new FileNotFoundException("Cannot rename document");
        return id(next);
    }
    private void remove(File file) throws FileNotFoundException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (!file.delete()) throw new FileNotFoundException("Cannot delete fixture");
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (id.equals("root")) throw new FileNotFoundException("Cannot delete fixture root");
        remove(file(id));
    }
    @Override public boolean isChildDocument(String parent, String document) { return document.startsWith(parent + "/"); }
}
