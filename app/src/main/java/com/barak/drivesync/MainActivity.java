package com.barak.drivesync;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.bumptech.glide.Glide;
import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.SignInButton;
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
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "DriveSyncPrefs";
    private static final String KEY_DRIVE_FOLDER_ID = "drive_folder_id";
    private static final String KEY_DRIVE_FOLDER_NAME = "drive_folder_name";
    private static final String KEY_LOCAL_FOLDER_URI = "local_folder_uri";

    private GoogleSignInClient mGoogleSignInClient;
    private SignInButton signInButton;
    private CardView userCard;
    private ImageView userAvatar;
    private TextView userName, userEmail, txtStatusSAF, txtProgressPercent, txtProgressCount;
    private TextView txtDriveFolderPath, txtLocalFolderPath;
    private Button syncButton, closeButton, selectDriveFolderButton, selectLocalFolderButton;
    private ProgressBar progressBar;

    private Uri localDirUri;
    private Drive driveService;
    private String selectedDriveFolderId, selectedDriveFolderName;

    private ActivityResultLauncher<Intent> signInLauncher;
    private ActivityResultLauncher<Intent> folderPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_READONLY))
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        signInButton = findViewById(R.id.sign_in_button);
        userCard = findViewById(R.id.user_card);
        userAvatar = findViewById(R.id.user_avatar);
        userName = findViewById(R.id.user_name);
        userEmail = findViewById(R.id.user_email);
        syncButton = findViewById(R.id.sync_button);
        txtStatusSAF = findViewById(R.id.txtStatusSAF);
        closeButton = findViewById(R.id.close_button);
        selectDriveFolderButton = findViewById(R.id.select_drive_folder_button);
        selectLocalFolderButton = findViewById(R.id.select_local_folder_button);
        progressBar = findViewById(R.id.progressBar);
        txtProgressPercent = findViewById(R.id.txtProgressPercent);
        txtProgressCount = findViewById(R.id.txtProgressCount);

        txtDriveFolderPath = findViewById(R.id.txtDriveFolderPath);
        txtLocalFolderPath = findViewById(R.id.txtLocalFolderPath);

        closeButton.setOnClickListener(v -> finishAffinity());

        signInButton.setOnClickListener(view -> signIn());
        syncButton.setOnClickListener(v -> {
            if (localDirUri == null) {
                pickLocalDirectory();
            } else if (selectedDriveFolderId == null) {
                pickDriveFolder();
            } else {
                syncDriveFolder();
            }
        });

        selectDriveFolderButton.setOnClickListener(v -> {
            if (driveService == null) {
                Toast.makeText(this, "Sign in to Google first.", Toast.LENGTH_SHORT).show();
                return;
            }
            pickDriveFolder();
        });

        selectLocalFolderButton.setOnClickListener(v -> pickLocalDirectory());

        signInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                        handleSignInResult(task);
                    } else {
                        updateUI(null);
                    }
                }
        );

        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri returnedUri = result.getData().getData();
                        if (returnedUri != null) {
                            localDirUri = returnedUri;
                            final int modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                            try {
                                getContentResolver().takePersistableUriPermission(localDirUri, modeFlags);
                                Toast.makeText(this, "Folder access granted. Ready to sync.", Toast.LENGTH_SHORT).show();
                                saveLocalFolderUri(localDirUri);
                            } catch (SecurityException e) {
                                txtStatusSAF.setText("Error: Failed to get persistent access to the folder.");
                                Toast.makeText(this, "Failed to get persistent access to the folder.", Toast.LENGTH_LONG).show();
                                localDirUri = null;
                            }
                        } else {
                            txtStatusSAF.setText("No folder selected.");
                        }
                        updateFolderPathViews();
                    } else {
                        txtStatusSAF.setText("Folder selection canceled or failed.");
                    }
                }
        );

        // Restore localDirUri from persisted permissions or SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String localUriString = prefs.getString(KEY_LOCAL_FOLDER_URI, null);
        if (localUriString != null) {
            localDirUri = Uri.parse(localUriString);
            if (localDirUri != null && !isSAFDirectoryAccessible(localDirUri)) {
                // Folder no longer exists or is not accessible
                localDirUri = null;
                saveLocalFolderUri(null); // Clear from SharedPreferences
            }
        } else {
            List<android.content.UriPermission> persistedUriPermissions = getContentResolver().getPersistedUriPermissions();
            if (!persistedUriPermissions.isEmpty()) {
                for (int i = persistedUriPermissions.size() - 1; i >= 0; i--) {
                    android.content.UriPermission permission = persistedUriPermissions.get(i);
                    if (permission.isReadPermission() && permission.isWritePermission()) {
                        Uri candidateUri = permission.getUri();
                        if (isSAFDirectoryAccessible(candidateUri)) {
                            localDirUri = candidateUri;
                            break;
                        }
                    }
                }
            }
        }

        // Restore Drive folder selection from SharedPreferences
        selectedDriveFolderId = prefs.getString(KEY_DRIVE_FOLDER_ID, null);
        selectedDriveFolderName = prefs.getString(KEY_DRIVE_FOLDER_NAME, null);

        updateFolderPathViews();
    }

    @Override
    protected void onStart() {
        super.onStart();
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        updateUI(account);
        if (account != null) {
            setupDriveService(account);
        }
    }

    private void signIn() {
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        signInLauncher.launch(signInIntent);
    }

    private void handleSignInResult(Task<GoogleSignInAccount> completedTask) {
        try {
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);
            updateUI(account);
            if (account != null) {
                setupDriveService(account);
            }
        } catch (ApiException e) {
            Toast.makeText(this, "Sign in failed: " + e.getStatusCode(), Toast.LENGTH_SHORT).show();
            updateUI(null);
        }
    }

    private void updateUI(GoogleSignInAccount account) {
        if (account != null) {
            userName.setText(account.getDisplayName());
            userEmail.setText(account.getEmail());
            if (account.getPhotoUrl() != null) {
                Glide.with(this).load(account.getPhotoUrl()).placeholder(R.drawable.ic_launcher_foreground).into(userAvatar);
            } else {
                userAvatar.setImageResource(R.drawable.ic_launcher_foreground);
            }
            userCard.setVisibility(View.VISIBLE);
            signInButton.setVisibility(View.GONE);
            syncButton.setVisibility(View.VISIBLE);
            selectDriveFolderButton.setVisibility(View.VISIBLE);
        } else {
            userCard.setVisibility(View.GONE);
            signInButton.setVisibility(View.VISIBLE);
            syncButton.setVisibility(View.GONE);
            selectDriveFolderButton.setVisibility(View.GONE);
        }
        updateFolderPathViews();
    }

    private void updateFolderPathViews() {
        if (selectedDriveFolderName != null && !selectedDriveFolderName.isEmpty()) {
            txtDriveFolderPath.setText(selectedDriveFolderName);
            txtDriveFolderPath.setVisibility(View.VISIBLE);
        } else {
            txtDriveFolderPath.setVisibility(View.GONE);
        }

        if (localDirUri != null) {
            txtLocalFolderPath.setText(getFileNameFromUri(localDirUri));
            txtLocalFolderPath.setVisibility(View.VISIBLE);
        } else {
            txtLocalFolderPath.setVisibility(View.GONE);
        }

        updateStatusMessage();
    }

    private void updateStatusMessage() {
        if (selectedDriveFolderName == null || selectedDriveFolderName.isEmpty()) {
            txtStatusSAF.setText("Please select a Drive folder to sync from.");
        } else if (localDirUri == null) {
            txtStatusSAF.setText("Please select a local folder to sync to.");
        } else {
            txtStatusSAF.setText("Ready to sync: " + selectedDriveFolderName + " \u2192 " + getFileNameFromUri(localDirUri));
        }
    }

    private void saveLocalFolderUri(Uri uri) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        if (uri == null) {
            editor.remove(KEY_LOCAL_FOLDER_URI);
        } else {
            editor.putString(KEY_LOCAL_FOLDER_URI, uri.toString());
        }
        editor.apply();
    }

    private void saveDriveFolderSelection(String folderId, String folderName) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(KEY_DRIVE_FOLDER_ID, folderId);
        editor.putString(KEY_DRIVE_FOLDER_NAME, folderName);
        editor.apply();
    }

    private void pickLocalDirectory() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    private void pickDriveFolder() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                FileList result = driveService.files().list()
                        .setQ("mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                        .setFields("files(id, name)")
                        .setSpaces("drive")
                        .setPageSize(100)
                        .execute();
                List<File> folders = result.getFiles();
                if (folders == null || folders.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "No folders found in Drive.", Toast.LENGTH_SHORT).show());
                    return;
                }
                String[] folderNames = new String[folders.size()];
                for (int i = 0; i < folders.size(); i++) {
                    folderNames[i] = folders.get(i).getName();
                }
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Select Drive Folder")
                            .setItems(folderNames, (dialog, which) -> {
                                File selected = folders.get(which);
                                selectedDriveFolderId = selected.getId();
                                selectedDriveFolderName = selected.getName();
                                saveDriveFolderSelection(selectedDriveFolderId, selectedDriveFolderName);
                                updateFolderPathViews();
                                Toast.makeText(this, "Selected: " + selectedDriveFolderName, Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to list Drive folders: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void setupDriveService(GoogleSignInAccount account) {
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                this, Collections.singleton(DriveScopes.DRIVE_READONLY));
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
                .setApplicationName(getString(R.string.app_name))
                .build();
    }

    private boolean isSAFDirectoryAccessible(Uri dirUri) {
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    dirUri, DocumentsContract.getTreeDocumentId(dirUri));
            try (Cursor cursor = getContentResolver().query(
                    childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null)) {
                if (cursor == null) return false;
                boolean hasData = cursor.moveToFirst() || cursor.getCount() == 0;
                return hasData;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void syncDriveFolder() {
        if (driveService == null) {
            txtStatusSAF.setText("Not signed in. Please sign in to Google.");
            Toast.makeText(this, "Please sign in first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (localDirUri == null || !isSAFDirectoryAccessible(localDirUri)) {
            txtStatusSAF.setText("Local directory not found. Please pick a folder.");
            Toast.makeText(this, "Local folder not found. Please select a new folder.", Toast.LENGTH_SHORT).show();
            localDirUri = null;
            pickLocalDirectory();
            return;
        }
        if (selectedDriveFolderId == null || selectedDriveFolderName == null) {
            txtStatusSAF.setText("No Drive folder selected. Please select a folder.");
            Toast.makeText(this, "Please select a Drive folder.", Toast.LENGTH_SHORT).show();
            return;
        }

        txtStatusSAF.setText("Syncing '" + selectedDriveFolderName + "' to " + getFileNameFromUri(localDirUri) + "...");

        runOnUiThread(() -> {
            progressBar.setVisibility(View.VISIBLE);
            txtProgressPercent.setVisibility(View.VISIBLE);
            txtProgressCount.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);
            txtProgressPercent.setText("0%");
            txtProgressCount.setText("0/0");
        });

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<File> driveFiles = listFilesInDriveFolder(driveService, selectedDriveFolderId);
                Map<String, Long> localFiles = listLocalSAFFilesWithModified(localDirUri);
                Set<String> driveFileNames = new HashSet<>();

                List<File> filesToSync = new ArrayList<>();
                for (File driveFile : driveFiles) {
                    driveFileNames.add(driveFile.getName());
                    Long localFileModifiedTime = localFiles.get(driveFile.getName());
                    if (localFileModifiedTime == null) {
                        filesToSync.add(driveFile);
                    } else {
                        long driveModified = driveFile.getModifiedTime().getValue();
                        if (driveModified > localFileModifiedTime) {
                            filesToSync.add(driveFile);
                        }
                    }
                }

                int totalToSync = filesToSync.size();
                int syncedCount = 0, downloadedCount = 0, updatedCount = 0, skippedCount = 0, failedCount = 0, deletedCount = 0;

                for (File driveFile : filesToSync) {
                    Long localFileModifiedTime = localFiles.get(driveFile.getName());
                    boolean isNew = (localFileModifiedTime == null);
                    if (downloadDriveFileToSAF(driveService, driveFile, localDirUri)) {
                        if (isNew) downloadedCount++; else updatedCount++;
                    } else {
                        failedCount++;
                    }
                    syncedCount++;
                    final int progress = (int) (((syncedCount) * 100.0f) / (totalToSync == 0 ? 1 : totalToSync));
                    final int currentFile = syncedCount;
                    final int total = totalToSync;
                    runOnUiThread(() -> {
                        progressBar.setProgress(progress);
                        txtProgressPercent.setText(progress + "%");
                        txtProgressCount.setText(currentFile + "/" + total);
                    });
                }

                skippedCount = driveFiles.size() - filesToSync.size();

                for (String localFile : localFiles.keySet()) {
                    if (!driveFileNames.contains(localFile)) {
                        if (deleteLocalSAFFile(localDirUri, localFile)) {
                            deletedCount++;
                        }
                    }
                }

                final int finalDownloaded = downloadedCount, finalUpdated = updatedCount, finalSkipped = skippedCount, finalFailed = failedCount, finalDeleted = deletedCount;
                runOnUiThread(() -> {
                    String summary = "Sync complete. Downloaded: " + finalDownloaded +
                            ", Updated: " + finalUpdated +
                            ", Skipped: " + finalSkipped +
                            ", Failed: " + finalFailed +
                            ", Deleted: " + finalDeleted;
                    txtStatusSAF.setText(summary);
                    Toast.makeText(MainActivity.this, summary, Toast.LENGTH_LONG).show();
                    progressBar.setVisibility(View.GONE);
                    txtProgressPercent.setVisibility(View.GONE);
                    txtProgressCount.setVisibility(View.GONE);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtStatusSAF.setText("Sync failed: " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                    txtProgressPercent.setVisibility(View.GONE);
                    txtProgressCount.setVisibility(View.GONE);
                });
            }
        });
    }

    private List<File> listFilesInDriveFolder(Drive driveService, String folderId) throws Exception {
        String query = "'" + folderId + "' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'";
        FileList result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name, modifiedTime, md5Checksum, mimeType, size)")
                .setPageSize(1000)
                .execute();
        if (result.getFiles() == null) {
            return Collections.emptyList();
        }
        return result.getFiles();
    }

    private Map<String, Long> listLocalSAFFilesWithModified(Uri dirUri) {
        Map<String, Long> fileMap = new HashMap<>();
        ContentResolver resolver = getContentResolver();
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
            Log.e(TAG, "Error listing local SAF files in: " + dirUri, e);
        }
        return fileMap;
    }

    private boolean downloadDriveFileToSAF(Drive driveService, File driveFile, Uri localDirUri) {
        String mimeType = driveFile.getMimeType() != null ? driveFile.getMimeType() : "application/octet-stream";
        Uri newFileUri = createFileInSAFDirectory(localDirUri, driveFile.getName(), mimeType);
        if (newFileUri == null) return false;
        try (OutputStream out = getContentResolver().openOutputStream(newFileUri, "wt")) {
            if (out == null) return false;
            driveService.files().get(driveFile.getId()).executeMediaAndDownloadTo(out);
            return true;
        } catch (Exception e) {
            try { DocumentsContract.deleteDocument(getContentResolver(), newFileUri); } catch (Exception ignore) {}
            return false;
        }
    }

    private Uri createFileInSAFDirectory(Uri treeUri, String fileName, String mimeType) {
        try {
            Uri existingFileUri = findFileInSAFDirectory(treeUri, fileName);
            if (existingFileUri != null) return existingFileUri;
            Uri parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            return DocumentsContract.createDocument(getContentResolver(), parentDocumentUri, mimeType, fileName);
        } catch (Exception e) {
            return null;
        }
    }

    private Uri findFileInSAFDirectory(Uri treeUri, String fileName) {
        ContentResolver resolver = getContentResolver();
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
        } catch (Exception e) {}
        return null;
    }

    private boolean deleteLocalSAFFile(Uri dirUri, String fileName) {
        ContentResolver resolver = getContentResolver();
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
        } catch (Exception e) {}
        return false;
    }

    private String getFileNameFromUri(Uri uri) {
        if (uri == null) return "Unknown";
        String docId = DocumentsContract.getTreeDocumentId(uri);
        if (docId != null) {
            int colon = docId.indexOf(':');
            if (colon >= 0 && colon < docId.length() - 1) {
                return docId.substring(colon + 1);
            } else {
                return docId;
            }
        }
        return "Unknown";
    }
}