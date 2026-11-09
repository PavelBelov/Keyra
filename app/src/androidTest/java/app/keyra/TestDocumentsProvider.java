package app.keyra;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Standalone test-APK process: deliberately uses no target-APK Kotlin runtime. */
public class TestDocumentsProvider extends ContentProvider {
    private File file(String id) {
        if (!id.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Invalid fixture id");
        File dir = new File(getContext().getFilesDir(), "synthetic-documents");
        dir.mkdirs();
        return new File(dir, id);
    }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String id = DocumentsContract.getDocumentId(uri);
        MatrixCursor cursor = new MatrixCursor(new String[] {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS});
        cursor.addRow(new Object[] {id, id, id.equals("root") ? Document.MIME_TYPE_DIR : "application/octet-stream", file(id).length(), Document.FLAG_SUPPORTS_WRITE | Document.FLAG_DIR_SUPPORTS_CREATE});
        return cursor;
    }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String id = DocumentsContract.getDocumentId(uri);
        if (id.equals("readonly") && !mode.equals("r")) throw new SecurityException("read-only fixture");
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!method.equals("android:createDocument")) throw new UnsupportedOperationException(method);
        Uri parent = extras.getParcelable("uri");
        if (!DocumentsContract.getDocumentId(parent).equals("root")) throw new IllegalArgumentException("Invalid parent");
        String name = extras.getString(Document.COLUMN_DISPLAY_NAME);
        try { file(name).createNewFile(); } catch (IOException e) { throw new IllegalStateException(e); }
        Bundle result = new Bundle();
        result.putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(parent, name));
        return result;
    }
}
