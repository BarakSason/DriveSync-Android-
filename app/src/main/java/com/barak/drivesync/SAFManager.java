package com.barak.drivesync;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class SAFManager {
    private static final String TAG = "SAFManager";
    private final Context context;

    public SAFManager(Context context) {
        this.context = context;
    }

    public boolean isDirectoryAccessible(Uri dirUri) {
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    dirUri, DocumentsContract.getTreeDocumentId(dirUri));
            try (Cursor cursor = context.getContentResolver().query(
                    childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null)) {
                return cursor != null;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Long> listFilesWithModified(Uri dirUri) {
        Map<String, Long> fileMap = new HashMap<>();
        ContentResolver resolver = context.getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, DocumentsContract.getTreeDocumentId(dirUri));
        try (Cursor cursor = resolver.query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                int mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while (cursor.moveToNext()) {
                    String name = cursor.getString(nameIndex);
                    long modified = cursor.getLong(modifiedIndex);
                    String mimeType = cursor.getString(mimeTypeIndex);
                    if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        fileMap.put(name, modified);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "listFilesWithModified: Error listing local SAF files", e);
        }
        return fileMap;
    }

    public Uri createFile(Uri treeUri, String fileName, String mimeType) {
        try {
            Uri existingFileUri = findFile(treeUri, fileName);
            if (existingFileUri != null) return existingFileUri;
            Uri parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            return DocumentsContract.createDocument(context.getContentResolver(), parentDocumentUri, mimeType, fileName);
        } catch (Exception e) {
            return null;
        }
    }

    public Uri findFile(Uri treeUri, String fileName) {
        ContentResolver resolver = context.getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        try (Cursor cursor = resolver.query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String name = cursor.getString(1);
                    if (fileName.equals(name)) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public boolean deleteFile(Uri dirUri, String fileName) {
        ContentResolver resolver = context.getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, DocumentsContract.getTreeDocumentId(dirUri));
        try (Cursor cursor = resolver.query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            if (cursor != null) {
                int docIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(docIdIndex);
                    String name = cursor.getString(nameIndex);
                    String mimeType = cursor.getString(mimeTypeIndex);
                    if (fileName.equals(name) && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        Uri fileUriToDelete = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId);
                        return DocumentsContract.deleteDocument(resolver, fileUriToDelete);
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    public OutputStream openFileOutputStream(Uri fileUri) {
        try {
            return context.getContentResolver().openOutputStream(fileUri, "wt");
        } catch (Exception e) {
            return null;
        }
    }
}