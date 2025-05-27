package com.barak.drivesync;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.ActivityResult;
import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.client.http.HttpRequestInitializer;

import java.io.OutputStream;
import java.util.*;

/**
 * DriveManager handles Google Sign-In and Google Drive API operations.
 * It manages authentication, folder/file listing, and file download to local storage.
 */
public class DriveManager {
    private static final String TAG = "DriveManager";
    private final Context context;
    private final GoogleSignInClient googleSignInClient;
    private GoogleSignInAccount account;
    private Drive driveService;

    /**
     * Initializes DriveManager with Google Sign-In options and client.
     * @param context The application context.
     */
    public DriveManager(Context context) {
        this.context = context;
        // Configure Google Sign-In to request email and Drive read-only scope
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_READONLY))
                .build();
        googleSignInClient = GoogleSignIn.getClient(context, gso);
        android.util.Log.d(TAG, "DriveManager initialized with GoogleSignInClient.");
    }

    /**
     * Launches the Google Sign-In intent using the provided launcher.
     * @param launcher ActivityResultLauncher to handle the sign-in result.
     */
    public void signIn(androidx.activity.result.ActivityResultLauncher<Intent> launcher) {
        android.util.Log.i(TAG, "Launching Google Sign-In intent.");
        Intent signInIntent = googleSignInClient.getSignInIntent();
        launcher.launch(signInIntent);
    }

    /**
     * Handles the result of the sign-in activity and sets up Drive service if successful.
     * @param result The ActivityResult from the sign-in intent.
     * @param callback Callback to receive the signed-in account or null on failure.
     */
    public void handleSignInResult(ActivityResult result, java.util.function.Consumer<GoogleSignInAccount> callback) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            // Try to extract the signed-in account from the intent
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
            try {
                account = task.getResult(ApiException.class);
                // Set up Drive API service with the signed-in account
                setupDriveService();
                android.util.Log.i(TAG, "Google Sign-In successful.");
                callback.accept(account);
            } catch (ApiException e) {
                // Sign-in failed, log and return null
                android.util.Log.e(TAG, "Google Sign-In failed: " + e.getMessage(), e);
                callback.accept(null);
            }
        } else {
            // Sign-in was canceled or failed
            android.util.Log.w(TAG, "Google Sign-In canceled or failed.");
            callback.accept(null);
        }
    }

    /**
     * Attempts silent sign-in using the last signed-in account.
     * @param callback Callback to receive the signed-in account or null if not found.
     */
    public void trySilentSignIn(java.util.function.Consumer<GoogleSignInAccount> callback) {
        // Try to get the last signed-in account
        account = GoogleSignIn.getLastSignedInAccount(context);
        if (account != null) {
            // If found, set up Drive API service
            setupDriveService();
            android.util.Log.d(TAG, "Silent sign-in successful.");
        } else {
            android.util.Log.d(TAG, "Silent sign-in failed: No account found.");
        }
        callback.accept(account);
    }

    /**
     * Checks if the user is signed in and Drive service is ready.
     * @return true if signed in and Drive service is initialized, false otherwise.
     */
    public boolean isSignedIn() {
        boolean signedIn = account != null && driveService != null;
        android.util.Log.d(TAG, "isSignedIn: " + signedIn);
        return signedIn;
    }

    /**
     * Sets up the Google Drive service using the signed-in account.
     * Uses OAuth2 credentials and configures timeouts.
     */
    private void setupDriveService() {
        if (account == null || account.getAccount() == null) {
            android.util.Log.w(TAG, "setupDriveService: No account available.");
            return;
        }
        // Create OAuth2 credential for Drive API
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_READONLY));
        credential.setSelectedAccount(account.getAccount());
        // Set up request initializer with timeouts
        HttpRequestInitializer timeoutInitializer = request -> {
            credential.initialize(request);
            request.setConnectTimeout(3 * 60 * 1000); // 3 minutes
            request.setReadTimeout(3 * 60 * 1000);    // 3 minutes
        };
        // Build the Drive API service
        driveService = new Drive.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                timeoutInitializer)
                .setApplicationName(context.getString(R.string.app_name))
                .build();
        android.util.Log.i(TAG, "Drive service initialized.");
    }

    /**
     * Lists all non-trashed folders in the user's Google Drive.
     * @return List of Drive folder File objects.
     * @throws Exception if the API call fails.
     */
    public List<File> listFolders() throws Exception {
        android.util.Log.d(TAG, "Listing folders in Google Drive.");
        // Query for folders that are not trashed
        FileList result = driveService.files().list()
                .setQ("mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                .setFields("files(id, name)")
                .setSpaces("drive")
                .setPageSize(100)
                .execute();
        // Return the list of folders, or an empty list if none found
        List<File> folders = result.getFiles() != null ? result.getFiles() : Collections.emptyList();
        android.util.Log.i(TAG, "Found " + folders.size() + " folders.");
        return folders;
    }

    /**
     * Lists all non-folder, non-trashed files in the specified Drive folder.
     * @param folderId The ID of the Drive folder to list files from.
     * @return List of Drive File objects.
     * @throws Exception if the API call fails.
     */
    public List<File> listFilesInDrive(String folderId) throws Exception {
        android.util.Log.d(TAG, "Listing files in folder: " + folderId);
        // Build query to get all files (not folders) in the given folder
        String query = "'" + folderId + "' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'";
        FileList result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name, modifiedTime, md5Checksum, mimeType, size)")
                .setPageSize(1000)
                .execute();
        // Return the list of files, or an empty list if none found
        List<File> files = result.getFiles() != null ? result.getFiles() : Collections.emptyList();
        android.util.Log.i(TAG, "Found " + files.size() + " files in folder " + folderId);
        return files;
    }

    /**
     * Downloads a file from Google Drive to the local directory using SAF.
     * @param driveFile The Drive File to download.
     * @param localDirUri The URI of the local directory (SAF).
     * @param safManager The SAFManager to handle local file operations.
     * @return true if download succeeded, false otherwise.
     */
    public boolean downloadFileToSAF(File driveFile, Uri localDirUri, SAFManager safManager) {
        // Determine the MIME type for the file, defaulting to binary if unknown
        String mimeType = driveFile.getMimeType() != null ? driveFile.getMimeType() : "application/octet-stream";
        android.util.Log.d(TAG, "Preparing to download file: " + driveFile.getName() + " (MIME: " + mimeType + ")");
        // Create or get the local file URI in the SAF directory
        Uri newFileUri = safManager.createFile(localDirUri, driveFile.getName(), mimeType);
        if (newFileUri == null) {
            android.util.Log.e(TAG, "Failed to create local file for: " + driveFile.getName());
            return false;
        }
        try (OutputStream out = safManager.openFileOutputStream(newFileUri)) {
            if (out == null) {
                android.util.Log.e(TAG, "Failed to open output stream for: " + driveFile.getName());
                return false;
            }
            // Download the file content from Drive and write to the output stream
            driveService.files().get(driveFile.getId()).executeMediaAndDownloadTo(out);
            android.util.Log.i(TAG, "Downloaded file: " + driveFile.getName());
            return true;
        } catch (Exception e) {
            // If download fails, attempt to delete the incomplete file
            android.util.Log.e(TAG, "Error downloading file: " + driveFile.getName(), e);
            try {
                safManager.deleteFile(localDirUri, driveFile.getName());
                android.util.Log.d(TAG, "Deleted incomplete file: " + driveFile.getName());
            } catch (Exception ignore) {
                android.util.Log.w(TAG, "Failed to delete incomplete file: " + driveFile.getName());
            }
            return false;
        }
    }
}