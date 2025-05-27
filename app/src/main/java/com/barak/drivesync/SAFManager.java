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

/**
 * SAFManager provides utility methods for interacting with the Android Storage Access Framework (SAF).
 * It allows querying, creating, finding, deleting, and opening files in a SAF directory.
 */
public class SAFManager {
    private static final String TAG = "SAFManager";
    private final Context context;

    /**
     * Constructs a SAFManager with the given context.
     * @param context The application context.
     */
    public SAFManager(Context context) {
        this.context = context;
    }

    /**
     * Checks if the given directory URI is accessible by attempting to query its children.
     * @param dirUri The URI of the directory to check.
     * @return true if the directory is accessible, false otherwise.
     */
    public boolean isDirectoryAccessible(Uri dirUri) {
        try {
            // Build the URI for the children of the directory
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    dirUri, DocumentsContract.getTreeDocumentId(dirUri));
            // Query for at least one child to verify access
            try (Cursor cursor = context.getContentResolver().query(
                    childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null)) {
                // If the cursor is not null, the directory is accessible
                return cursor != null;
            }
        } catch (Exception e) {
            // Any exception means the directory is not accessible
            return false;
        }
    }

    /**
     * Returns a map of file names to their last modified timestamps for all non-directory files in the given directory.
     * @param dirUri The URI of the directory to list.
     * @return Map of file name to last modified time (epoch millis).
     */
    public Map<String, Long> getFileModifiedMap(Uri dirUri) {
        Map<String, Long> fileMap = new HashMap<>();
        ContentResolver resolver = context.getContentResolver();
        // Build the URI for the children of the directory
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, DocumentsContract.getTreeDocumentId(dirUri));
        try (Cursor cursor = resolver.query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                },
                null, null, null)) {
            if (cursor != null) {
                // Get column indices for the required fields
                int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                int mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                // Iterate through all children
                while (cursor.moveToNext()) {
                    String name = cursor.getString(nameIndex);
                    long modified = cursor.getLong(modifiedIndex);
                    String mimeType = cursor.getString(mimeTypeIndex);
                    // Only include files (not directories)
                    if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        fileMap.put(name, modified);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getFileModifiedMap: Error listing local SAF files", e);
        }
        return fileMap;
    }

    /**
     * Creates a new file in the given SAF directory, or returns the URI if it already exists.
     * @param treeUri The URI of the parent directory.
     * @param fileName The name of the file to create.
     * @param mimeType The MIME type of the file.
     * @return The URI of the created or existing file, or null on failure.
     */
    public Uri createFile(Uri treeUri, String fileName, String mimeType) {
        try {
            // Check if the file already exists
            Uri existingFileUri = findFile(treeUri, fileName);
            if (existingFileUri != null) return existingFileUri;
            // Build the parent document URI
            Uri parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            // Create the new file in the directory
            return DocumentsContract.createDocument(context.getContentResolver(), parentDocumentUri, mimeType, fileName);
        } catch (Exception e) {
            // Any exception means file creation failed
            return null;
        }
    }

    /**
     * Finds a file with the given name in the specified SAF directory.
     * @param treeUri The URI of the directory to search.
     * @param fileName The name of the file to find.
     * @return The URI of the file if found, or null if not found.
     */
    public Uri findFile(Uri treeUri, String fileName) {
        ContentResolver resolver = context.getContentResolver();
        // Build the URI for the children of the directory
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        try (Cursor cursor = resolver.query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                },
                null, null, null)) {
            if (cursor != null) {
                // Iterate through all children
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String name = cursor.getString(1);
                    // If the file name matches, build and return its URI
                    if (fileName.equals(name)) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore exceptions, just return null if not found
        }
        return null;
    }

    /**
     * Deletes a file with the given name from the specified SAF directory.
     * @param dirUri The URI of the directory.
     * @param fileName The name of the file to delete.
     * @return true if the file was deleted, false otherwise.
     */
    public boolean deleteFile(Uri dirUri, String fileName) {
        ContentResolver resolver = context.getContentResolver();
        // Build the URI for the children of the directory
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, DocumentsContract.getTreeDocumentId(dirUri));
        try (Cursor cursor = resolver.query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                },
                null, null, null)) {
            if (cursor != null) {
                // Get column indices for the required fields
                int docIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                // Iterate through all children
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(docIdIndex);
                    String name = cursor.getString(nameIndex);
                    String mimeType = cursor.getString(mimeTypeIndex);
                    // If the file name matches and it's not a directory, delete it
                    if (fileName.equals(name) && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        Uri fileUriToDelete = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId);
                        return DocumentsContract.deleteDocument(resolver, fileUriToDelete);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore exceptions, just return false if deletion fails
        }
        return false;
    }

    /**
     * Opens an OutputStream for writing to the specified file URI.
     * @param fileUri The URI of the file to open.
     * @return An OutputStream for writing, or null if opening fails.
     */
    public OutputStream openFileOutputStream(Uri fileUri) {
        try {
            // Open the file for writing (truncate mode)
            return context.getContentResolver().openOutputStream(fileUri, "wt");
        } catch (Exception e) {
            // Any exception means opening failed
            return null;
        }
    }
}