package com.barak.drivesync;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
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
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "DriveSyncPrefs";
    private static final String KEY_DRIVE_FOLDER_ID = "drive_folder_id";
    private static final String KEY_DRIVE_FOLDER_NAME = "drive_folder_name";
    private static final String KEY_LOCAL_FOLDER_URI = "local_folder_uri";

    private SignInButton signInButton;
    private CardView userCard;
    private ImageView userAvatar;
    private TextView userName, userEmail, txtStatusSAF, txtProgressPercent, txtProgressCount;
    private TextView txtDriveFolderPath, txtLocalFolderPath;
    private Button syncButton, selectDriveFolderButton, selectLocalFolderButton;
    private ProgressBar progressBar;

    private Uri localDirUri;
    private String selectedDriveFolderId, selectedDriveFolderName;

    private ActivityResultLauncher<Intent> signInLauncher;
    private ActivityResultLauncher<Intent> folderPickerLauncher;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private DriveManager driveManager;
    private SAFManager safManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        driveManager = new DriveManager(this);
        safManager = new SAFManager(this);

        signInButton = findViewById(R.id.sign_in_button);
        userCard = findViewById(R.id.user_card);
        userAvatar = findViewById(R.id.user_avatar);
        userName = findViewById(R.id.user_name);
        userEmail = findViewById(R.id.user_email);
        syncButton = findViewById(R.id.sync_button);
        txtStatusSAF = findViewById(R.id.txtStatusSAF);
        Button closeButton = findViewById(R.id.close_button);
        selectDriveFolderButton = findViewById(R.id.select_drive_folder_button);
        selectLocalFolderButton = findViewById(R.id.select_local_folder_button);
        progressBar = findViewById(R.id.progressBar);
        txtProgressPercent = findViewById(R.id.txtProgressPercent);
        txtProgressCount = findViewById(R.id.txtProgressCount);
        txtDriveFolderPath = findViewById(R.id.txtDriveFolderPath);
        txtLocalFolderPath = findViewById(R.id.txtLocalFolderPath);

        closeButton.setOnClickListener(v -> {
            executorService.shutdownNow();
            finishAffinity();
        });

        signInButton.setOnClickListener(view -> driveManager.signIn(signInLauncher));

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
            if (!driveManager.isSignedIn()) {
                Toast.makeText(this, "Sign in to Google first.", Toast.LENGTH_SHORT).show();
                return;
            }
            pickDriveFolder();
        });

        selectLocalFolderButton.setOnClickListener(v -> pickLocalDirectory());

        signInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> driveManager.handleSignInResult(result, this::onDriveSignIn)
        );

        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri returnedUri = result.getData().getData();
                        if (returnedUri != null) {
                            getContentResolver().takePersistableUriPermission(returnedUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            localDirUri = returnedUri;
                            saveLocalFolderUri(localDirUri);
                        }
                        updateFolderPathViews();
                        updateSyncButtonState();
                    } else {
                        txtStatusSAF.setText(getString(R.string.status_sync_failed, "Folder selection canceled or failed."));
                        updateSyncButtonState();
                    }
                }
        );

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String localUriString = prefs.getString(KEY_LOCAL_FOLDER_URI, null);
        if (localUriString != null) {
            localDirUri = Uri.parse(localUriString);
            if (localDirUri != null && !safManager.isDirectoryAccessible(localDirUri)) {
                localDirUri = null;
                saveLocalFolderUri(null);
            }
        }
        selectedDriveFolderId = prefs.getString(KEY_DRIVE_FOLDER_ID, null);
        selectedDriveFolderName = prefs.getString(KEY_DRIVE_FOLDER_NAME, null);

        updateFolderPathViews();
        updateSyncButtonState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        driveManager.trySilentSignIn(this::onDriveSignIn);
    }

    private void onDriveSignIn(GoogleSignInAccount account) {
        updateUI(account);
    }

    private void updateUI(GoogleSignInAccount account) {
        boolean isUserSignedIn = account != null;
        if (isUserSignedIn) {
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
            selectLocalFolderButton.setVisibility(View.VISIBLE);
        } else {
            userCard.setVisibility(View.GONE);
            signInButton.setVisibility(View.VISIBLE);
            syncButton.setVisibility(View.GONE);
            selectDriveFolderButton.setVisibility(View.GONE);
            selectLocalFolderButton.setVisibility(View.GONE);
        }
        updateFolderPathViews();
        updateSyncButtonState();
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
        updateSyncButtonState();
    }

    private void updateStatusMessage() {
        boolean isUserSignedIn = driveManager.isSignedIn();
        if (!isUserSignedIn) {
            txtStatusSAF.setText(R.string.status_sign_in);
        } else if (selectedDriveFolderName == null || selectedDriveFolderName.isEmpty()) {
            txtStatusSAF.setText(R.string.status_select_drive_folder);
        } else if (localDirUri == null) {
            txtStatusSAF.setText(R.string.status_select_local_folder);
        } else {
            txtStatusSAF.setText(getString(R.string.status_ready_to_sync, selectedDriveFolderName, getFileNameFromUri(localDirUri)));
        }
    }

    private void updateSyncButtonState() {
        boolean isDriveSelected = selectedDriveFolderName != null && !selectedDriveFolderName.isEmpty();
        boolean isLocalSelected = localDirUri != null;
        syncButton.setEnabled(isDriveSelected && isLocalSelected);
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
        executorService.execute(() -> {
            try {
                List<File> folders = driveManager.listFolders();
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
                                selectedDriveFolderId = folders.get(which).getId();
                                selectedDriveFolderName = folders.get(which).getName();
                                saveDriveFolderSelection(selectedDriveFolderId, selectedDriveFolderName);
                                updateFolderPathViews();
                                updateSyncButtonState();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to list Drive folders: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void syncDriveFolder() {
        if (!driveManager.isSignedIn()) {
            txtStatusSAF.setText(R.string.status_not_signed_in);
            Toast.makeText(this, "Please sign in first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (localDirUri == null || !safManager.isDirectoryAccessible(localDirUri)) {
            txtStatusSAF.setText(R.string.status_local_not_found);
            Toast.makeText(this, "Local folder not found. Please select a new folder.", Toast.LENGTH_SHORT).show();
            localDirUri = null;
            pickLocalDirectory();
            return;
        }
        if (selectedDriveFolderId == null || selectedDriveFolderName == null) {
            txtStatusSAF.setText(R.string.status_drive_not_selected);
            Toast.makeText(this, "Please select a Drive folder.", Toast.LENGTH_SHORT).show();
            return;
        }

        txtStatusSAF.setText(getString(R.string.status_syncing, selectedDriveFolderName, getFileNameFromUri(localDirUri)));

        runOnUiThread(() -> {
            progressBar.setVisibility(View.VISIBLE);
            txtProgressPercent.setVisibility(View.VISIBLE);
            txtProgressCount.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);
            txtProgressPercent.setText(getString(R.string.progress_percent, 0));
            txtProgressCount.setText(getString(R.string.progress_count, 0, 0));
        });

        executorService.execute(() -> {
            try {
                List<File> driveFiles = driveManager.listFilesInFolder(selectedDriveFolderId);
                Map<String, Long> localFiles = safManager.listFilesWithModified(localDirUri);
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
                    boolean ok = driveManager.downloadFileToSAF(driveFile, localDirUri, safManager);
                    if (ok) {
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
                        txtProgressPercent.setText(getString(R.string.progress_percent, progress));
                        txtProgressCount.setText(getString(R.string.progress_count, currentFile, total));
                    });
                }

                skippedCount = driveFiles.size() - filesToSync.size();

                for (String localFile : localFiles.keySet()) {
                    if (!driveFileNames.contains(localFile)) {
                        if (safManager.deleteFile(localDirUri, localFile)) {
                            deletedCount++;
                        }
                    }
                }

                final int finalDownloaded = downloadedCount, finalUpdated = updatedCount, finalSkipped = skippedCount, finalFailed = failedCount, finalDeleted = deletedCount;
                runOnUiThread(() -> {
                    String summary = getString(R.string.status_sync_complete, finalDownloaded, finalUpdated, finalSkipped, finalFailed, finalDeleted);
                    txtStatusSAF.setText(summary);
                    Toast.makeText(MainActivity.this, summary, Toast.LENGTH_LONG).show();
                    progressBar.setVisibility(View.GONE);
                    txtProgressPercent.setVisibility(View.GONE);
                    txtProgressCount.setVisibility(View.GONE);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtStatusSAF.setText(getString(R.string.status_sync_failed, e.getMessage()));
                    progressBar.setVisibility(View.GONE);
                    txtProgressPercent.setVisibility(View.GONE);
                    txtProgressCount.setVisibility(View.GONE);
                });
            }
        });
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