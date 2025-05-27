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

public class DriveManager {
    private static final String TAG = "DriveManager";
    private final Context context;
    private GoogleSignInClient googleSignInClient;
    private GoogleSignInAccount account;
    private Drive driveService;

    public DriveManager(Context context) {
        this.context = context;
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_READONLY))
                .build();
        googleSignInClient = GoogleSignIn.getClient(context, gso);
    }

    public void signIn(androidx.activity.result.ActivityResultLauncher<Intent> launcher) {
        Intent signInIntent = googleSignInClient.getSignInIntent();
        launcher.launch(signInIntent);
    }

    public void handleSignInResult(ActivityResult result, java.util.function.Consumer<GoogleSignInAccount> callback) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
            try {
                account = task.getResult(ApiException.class);
                setupDriveService();
                callback.accept(account);
            } catch (ApiException e) {
                callback.accept(null);
            }
        } else {
            callback.accept(null);
        }
    }

    public void trySilentSignIn(java.util.function.Consumer<GoogleSignInAccount> callback) {
        account = GoogleSignIn.getLastSignedInAccount(context);
        if (account != null) {
            setupDriveService();
        }
        callback.accept(account);
    }

    public boolean isSignedIn() {
        return account != null && driveService != null;
    }

    private void setupDriveService() {
        if (account == null || account.getAccount() == null) return;
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_READONLY));
        credential.setSelectedAccount(account.getAccount());
        HttpRequestInitializer timeoutInitializer = request -> {
            credential.initialize(request);
            request.setConnectTimeout(3 * 60 * 1000);
            request.setReadTimeout(3 * 60 * 1000);
        };
        driveService = new Drive.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                timeoutInitializer)
                .setApplicationName(context.getString(R.string.app_name))
                .build();
    }

    public List<File> listFolders() throws Exception {
        FileList result = driveService.files().list()
                .setQ("mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                .setFields("files(id, name)")
                .setSpaces("drive")
                .setPageSize(100)
                .execute();
        return result.getFiles() != null ? result.getFiles() : Collections.emptyList();
    }

    public List<File> listFilesInFolder(String folderId) throws Exception {
        String query = "'" + folderId + "' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'";
        FileList result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name, modifiedTime, md5Checksum, mimeType, size)")
                .setPageSize(1000)
                .execute();
        return result.getFiles() != null ? result.getFiles() : Collections.emptyList();
    }

    public boolean downloadFileToSAF(File driveFile, Uri localDirUri, SAFManager safManager) {
        String mimeType = driveFile.getMimeType() != null ? driveFile.getMimeType() : "application/octet-stream";
        Uri newFileUri = safManager.createFile(localDirUri, driveFile.getName(), mimeType);
        if (newFileUri == null) return false;
        try (OutputStream out = safManager.openFileOutputStream(newFileUri)) {
            if (out == null) return false;
            driveService.files().get(driveFile.getId()).executeMediaAndDownloadTo(out);
            return true;
        } catch (Exception e) {
            try { safManager.deleteFile(localDirUri, driveFile.getName()); } catch (Exception ignore) {}
            return false;
        }
    }
}