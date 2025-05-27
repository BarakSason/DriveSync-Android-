package com.barak.drivesync;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import com.google.api.services.drive.model.File;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main activity for the DriveSync application.
 * Handles user authentication, folder selection, and file synchronization between
 * Google Drive and local Android storage.
 */
public class DriveSync extends AppCompatActivity {

    private static final String TAG = "DriveSync";

    // SharedPreferences keys for persisting folder selections
    private static final String PREFS_NAME = "DriveSyncPrefs";
    private static final String KEY_DRIVE_FOLDER_ID = "drive_folder_id";
    private static final String KEY_DRIVE_FOLDER_NAME = "drive_folder_name";
    private static final String KEY_LOCAL_FOLDER_URI = "local_folder_uri";

    // UI elements
    private SignInButton signInButton;
    private CardView userCard;
    private ImageView userAvatar;
    private TextView userName, userEmail, txtStatusSAF, txtProgressPercent, txtProgressCount;
    private TextView txtDriveFolderPath, txtLocalFolderPath;
    private Button syncButton, selectDriveFolderButton, selectLocalFolderButton;
    private ProgressBar progressBar;

    // Folder selection state
    private Uri localDirUri;
    private String selectedDriveFolderId, selectedDriveFolderName;

    // Activity result launchers for sign-in and folder picking
    private ActivityResultLauncher<Intent> signInLauncher;
    private ActivityResultLauncher<Intent> folderPickerLauncher;

    // Executor for background tasks
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    // Service managers for Drive and SAF
    private DriveManager driveManager;
    private SAFManager safManager;

    /**
     * Activity entry point. Initializes UI, managers, listeners, and restores state.
     * @param savedInstanceState Bundle containing saved state, if any.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: Initializing DriveSync activity");

        // Initialize Drive and SAF managers for Google Drive and local storage access
        driveManager = new DriveManager(this);
        safManager = new SAFManager(this);

        // Set up UI components and listeners
        initializeViews();
        setupClickListeners();
        registerActivityResultLaunchers();

        // Restore previous folder selections from SharedPreferences
        restoreSavedPreferences();

        // Update UI to reflect restored state
        updateFolderPathViews();
        updateSyncButtonState();

        Log.i(TAG, "onCreate: DriveSync activity initialized successfully");
    }

    /**
     * Finds and assigns all UI elements from the layout.
     */
    private void initializeViews() {
        Log.d(TAG, "initializeViews: Locating UI components");
        signInButton = findViewById(R.id.sign_in_button);
        userCard = findViewById(R.id.user_card);
        userAvatar = findViewById(R.id.user_avatar);
        userName = findViewById(R.id.user_name);
        userEmail = findViewById(R.id.user_email);
        syncButton = findViewById(R.id.sync_button);
        txtStatusSAF = findViewById(R.id.txtStatusSAF);
        selectDriveFolderButton = findViewById(R.id.select_drive_folder_button);
        selectLocalFolderButton = findViewById(R.id.select_local_folder_button);
        progressBar = findViewById(R.id.progressBar);
        txtProgressPercent = findViewById(R.id.txtProgressPercent);
        txtProgressCount = findViewById(R.id.txtProgressCount);
        txtDriveFolderPath = findViewById(R.id.txtDriveFolderPath);
        txtLocalFolderPath = findViewById(R.id.txtLocalFolderPath);
    }

    /**
     * Sets up click listeners for all interactive UI elements.
     * Handles user actions such as sign-in, folder selection, and sync initiation.
     */
    private void setupClickListeners() {
        Log.d(TAG, "setupClickListeners: Attaching button listeners");

        // Close app button: shuts down executor and finishes activity
        findViewById(R.id.close_button).setOnClickListener(v -> {
            Log.i(TAG, "User requested app close. Shutting down executor and finishing activity.");
            executorService.shutdownNow();
            finishAffinity();
        });

        // Google Sign-in button: initiates Google sign-in flow
        signInButton.setOnClickListener(view -> {
            Log.i(TAG, "Sign-in button clicked. Initiating Google sign-in.");
            driveManager.signIn(signInLauncher);
        });

        // Sync button: starts sync if both folders are selected, otherwise prompts user
        syncButton.setOnClickListener(v -> {
            Log.i(TAG, "Sync button clicked.");
            if (localDirUri == null) {
                Log.w(TAG, "No local directory selected. Prompting user.");
                pickLocalDirectory();
            } else if (selectedDriveFolderId == null) {
                Log.w(TAG, "No Drive folder selected. Prompting user.");
                pickDriveFolder();
            } else {
                Log.i(TAG, "All folders selected. Starting sync.");
                syncDriveFolder();
            }
        });

        // Drive folder selection button: prompts user to select a Drive folder
        selectDriveFolderButton.setOnClickListener(v -> {
            if (!driveManager.isSignedIn()) {
                Log.w(TAG, "Attempted Drive folder selection without sign-in.");
                Toast.makeText(this, "Sign in to Google first.", Toast.LENGTH_SHORT).show();
                return;
            }
            Log.i(TAG, "Drive folder selection initiated.");
            pickDriveFolder();
        });

        // Local folder selection button: prompts user to select a local folder
        selectLocalFolderButton.setOnClickListener(v -> {
            Log.i(TAG, "Local folder selection initiated.");
            pickLocalDirectory();
        });
    }

    /**
     * Registers activity result launchers for sign-in and folder picking.
     * Handles results from external activities and updates state accordingly.
     */
    private void registerActivityResultLaunchers() {
        Log.d(TAG, "registerActivityResultLaunchers: Registering result handlers.");

        // Google Sign-in result handler
        signInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d(TAG, "Sign-in result received. Code: " + result.getResultCode());
                    driveManager.handleSignInResult(result, this::onDriveSignIn);
                }
        );

        // Folder picker result handler
        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        // User picked a folder, persist URI permission and save selection
                        Uri returnedUri = result.getData().getData();
                        if (returnedUri != null) {
                            Log.i(TAG, "Local folder selected: " + returnedUri);
                            getContentResolver().takePersistableUriPermission(returnedUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            localDirUri = returnedUri;
                            saveLocalFolderUri(localDirUri);
                        }
                        updateFolderPathViews();
                        updateSyncButtonState();
                    } else {
                        // User canceled or failed to pick a folder
                        Log.w(TAG, "Folder selection canceled or failed.");
                        txtStatusSAF.setText(getString(R.string.status_sync_failed, "Folder selection canceled or failed."));
                        updateSyncButtonState();
                    }
                }
        );
    }

    /**
     * Restores user preferences (selected folders) from SharedPreferences.
     * If the saved local folder is no longer accessible, clears the selection.
     */
    private void restoreSavedPreferences() {
        Log.d(TAG, "restoreSavedPreferences: Loading saved folder selections.");
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Restore local folder URI
        String localUriString = prefs.getString(KEY_LOCAL_FOLDER_URI, null);
        if (localUriString != null) {
            Log.d(TAG, "Restoring local folder URI: " + localUriString);
            localDirUri = Uri.parse(localUriString);
            // Check if the folder is still accessible
            if (localDirUri != null && !safManager.isDirectoryAccessible(localDirUri)) {
                Log.w(TAG, "Saved local folder is not accessible. Clearing selection.");
                localDirUri = null;
                saveLocalFolderUri(null);
            }
        }

        // Restore Drive folder ID and name
        selectedDriveFolderId = prefs.getString(KEY_DRIVE_FOLDER_ID, null);
        selectedDriveFolderName = prefs.getString(KEY_DRIVE_FOLDER_NAME, null);
        if (selectedDriveFolderId != null) {
            Log.d(TAG, "Restored Drive folder: " + selectedDriveFolderName + " (ID: " + selectedDriveFolderId + ")");
        }
    }

    /**
     * Attempts silent sign-in when the activity starts.
     * If successful, updates the UI to reflect the signed-in state.
     */
    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart: Attempting silent Google sign-in.");
        driveManager.trySilentSignIn(this::onDriveSignIn);
    }

    /**
     * Callback for Drive sign-in completion.
     * Updates the UI based on the sign-in result.
     * @param account The signed-in Google account, or null if sign-in failed.
     */
    private void onDriveSignIn(GoogleSignInAccount account) {
        Log.i(TAG, "onDriveSignIn: " + (account != null ? "Sign-in successful." : "Sign-in failed."));
        updateUI(account);
    }

    /**
     * Updates the UI based on the user's sign-in state.
     * Shows or hides user info and enables/disables sync controls accordingly.
     * @param account The signed-in Google account, or null if not signed in.
     */
    private void updateUI(GoogleSignInAccount account) {
        boolean isUserSignedIn = account != null;
        Log.d(TAG, "updateUI: User signed in: " + isUserSignedIn);

        if (isUserSignedIn) {
            // Show user info and enable sync controls
            userName.setText(account.getDisplayName());
            userEmail.setText(account.getEmail());
            if (account.getPhotoUrl() != null) {
                Log.d(TAG, "Loading user avatar: " + account.getPhotoUrl());
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
            // Hide user info and disable sync controls
            userCard.setVisibility(View.GONE);
            signInButton.setVisibility(View.VISIBLE);
            syncButton.setVisibility(View.GONE);
            selectDriveFolderButton.setVisibility(View.GONE);
            selectLocalFolderButton.setVisibility(View.GONE);
        }

        updateFolderPathViews();
        updateSyncButtonState();
    }

    /**
     * Updates the folder path text views based on current selections.
     * Hides the views if no folder is selected.
     */
    private void updateFolderPathViews() {
        Log.d(TAG, "updateFolderPathViews: Updating folder path displays.");

        // Drive folder path
        if (selectedDriveFolderName != null && !selectedDriveFolderName.isEmpty()) {
            txtDriveFolderPath.setText(selectedDriveFolderName);
            txtDriveFolderPath.setVisibility(View.VISIBLE);
        } else {
            txtDriveFolderPath.setVisibility(View.GONE);
        }

        // Local folder path
        if (localDirUri != null) {
            String localFolderName = getFileNameFromUri(localDirUri);
            Log.d(TAG, "Local folder name: " + localFolderName);
            txtLocalFolderPath.setText(localFolderName);
            txtLocalFolderPath.setVisibility(View.VISIBLE);
        } else {
            txtLocalFolderPath.setVisibility(View.GONE);
        }

        updateStatusMessage();
        updateSyncButtonState();
    }

    /**
     * Updates the status message based on the current app state.
     * Shows prompts for sign-in, folder selection, or readiness to sync.
     */
    private void updateStatusMessage() {
        boolean isUserSignedIn = driveManager.isSignedIn();
        Log.d(TAG, "updateStatusMessage: isUserSignedIn=" + isUserSignedIn +
                ", DriveFolder=" + (selectedDriveFolderName != null) +
                ", LocalFolder=" + (localDirUri != null));

        if (!isUserSignedIn) {
            txtStatusSAF.setText(R.string.status_sign_in);
        } else if (selectedDriveFolderName == null || selectedDriveFolderName.isEmpty()) {
            txtStatusSAF.setText(R.string.status_select_drive_folder);
        } else if (localDirUri == null) {
            txtStatusSAF.setText(R.string.status_select_local_folder);
        } else {
            txtStatusSAF.setText(getString(R.string.status_ready_to_sync,
                    selectedDriveFolderName,
                    getFileNameFromUri(localDirUri)));
        }
    }

    /**
     * Enables or disables the sync button based on folder selections.
     */
    private void updateSyncButtonState() {
        boolean isDriveSelected = selectedDriveFolderName != null && !selectedDriveFolderName.isEmpty();
        boolean isLocalSelected = localDirUri != null;
        boolean isEnabled = isDriveSelected && isLocalSelected;

        Log.d(TAG, "updateSyncButtonState: DriveSelected=" + isDriveSelected +
                ", LocalSelected=" + isLocalSelected +
                ", SyncEnabled=" + isEnabled);

        syncButton.setEnabled(isEnabled);
    }

    /**
     * Saves the local folder URI to SharedPreferences for persistence.
     * @param uri The URI of the selected local folder, or null to clear.
     */
    private void saveLocalFolderUri(Uri uri) {
        Log.d(TAG, "saveLocalFolderUri: Saving URI: " + (uri != null ? uri.toString() : "null"));
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        if (uri == null) {
            editor.remove(KEY_LOCAL_FOLDER_URI);
        } else {
            editor.putString(KEY_LOCAL_FOLDER_URI, uri.toString());
        }
        editor.apply();
    }

    /**
     * Saves the Drive folder selection to SharedPreferences.
     * @param folderId   The ID of the selected Drive folder.
     * @param folderName The display name of the selected Drive folder.
     */
    private void saveDriveFolderSelection(String folderId, String folderName) {
        Log.d(TAG, "saveDriveFolderSelection: Saving Drive folder: " + folderName + " (ID: " + folderId + ")");
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(KEY_DRIVE_FOLDER_ID, folderId);
        editor.putString(KEY_DRIVE_FOLDER_NAME, folderName);
        editor.apply();
    }

    /**
     * Launches the Storage Access Framework to let the user pick a local directory.
     */
    private void pickLocalDirectory() {
        Log.i(TAG, "pickLocalDirectory: Launching folder picker intent.");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    /**
     * Fetches the list of available Google Drive folders in a background thread,
     * then displays a dialog for the user to select one.
     * Updates the selected folder state and UI upon selection.
     * Handles errors by showing a message to the user.
     */
    private void pickDriveFolder() {
        Log.i(TAG, "pickDriveFolder: Fetching Drive folders in background.");
        executorService.execute(() -> {
            try {
                // Fetch list of folders from Drive
                List<File> folders = driveManager.listFolders();
                if (folders == null || folders.isEmpty()) {
                    Log.w(TAG, "No folders found in Drive.");
                    runOnUiThread(() -> Toast.makeText(this, "No folders found in Drive.", Toast.LENGTH_SHORT).show());
                    return;
                }

                Log.d(TAG, "Found " + folders.size() + " folders in Drive.");
                String[] folderNames = new String[folders.size()];
                for (int i = 0; i < folders.size(); i++) {
                    folderNames[i] = folders.get(i).getName();
                }

                // Show folder selection dialog on UI thread
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Select Drive Folder")
                            .setItems(folderNames, (dialog, which) -> {
                                // User selected a folder, save selection and update UI
                                File selectedFolder = folders.get(which);
                                selectedDriveFolderId = selectedFolder.getId();
                                selectedDriveFolderName = selectedFolder.getName();
                                Log.i(TAG, "Drive folder selected: " + selectedDriveFolderName + " (ID: " + selectedDriveFolderId + ")");
                                saveDriveFolderSelection(selectedDriveFolderId, selectedDriveFolderName);
                                updateFolderPathViews();
                                updateSyncButtonState();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            } catch (Exception e) {
                // Handle errors and show message to user
                Log.e(TAG, "Error listing Drive folders.", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed to list Drive folders: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Synchronizes files from the selected Drive folder to the local folder.
     * Downloads new or updated files, deletes local files not present in Drive,
     * and updates the UI with progress and results.
     * Handles errors gracefully and updates the UI accordingly.
     */
    private void syncDriveFolder() {
        // Validate preconditions: user must be signed in and folders selected
        if (!driveManager.isSignedIn()) {
            Log.e(TAG, "syncDriveFolder: Not signed in to Google.");
            txtStatusSAF.setText(R.string.status_not_signed_in);
            Toast.makeText(this, "Please sign in first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (localDirUri == null || !safManager.isDirectoryAccessible(localDirUri)) {
            Log.e(TAG, "syncDriveFolder: Local folder not accessible: " + localDirUri);
            txtStatusSAF.setText(R.string.status_local_not_found);
            Toast.makeText(this, "Local folder not found. Please select a new folder.", Toast.LENGTH_SHORT).show();
            localDirUri = null;
            pickLocalDirectory();
            return;
        }
        if (selectedDriveFolderId == null || selectedDriveFolderName == null) {
            Log.e(TAG, "syncDriveFolder: Drive folder not selected.");
            txtStatusSAF.setText(R.string.status_drive_not_selected);
            Toast.makeText(this, "Please select a Drive folder.", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "syncDriveFolder: Starting sync from Drive folder '" + selectedDriveFolderName +
                "' to local folder '" + getFileNameFromUri(localDirUri) + "'.");

        txtStatusSAF.setText(getString(R.string.status_syncing,
                selectedDriveFolderName,
                getFileNameFromUri(localDirUri)));

        // Show progress UI
        runOnUiThread(() -> {
            progressBar.setVisibility(View.VISIBLE);
            txtProgressPercent.setVisibility(View.VISIBLE);
            txtProgressCount.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);
            txtProgressPercent.setText(getString(R.string.progress_percent, 0));
            txtProgressCount.setText(getString(R.string.progress_count, 0, 0));
        });

        // Perform sync in background thread
        executorService.execute(() -> {
            try {
                // 1. List files in Drive and local folder
                Log.d(TAG, "Listing files in Drive folder: " + selectedDriveFolderName);
                List<File> driveFiles = driveManager.listFilesInDrive(selectedDriveFolderId);
                Log.d(TAG, "Found " + driveFiles.size() + " files in Drive folder.");

                Log.d(TAG, "Listing files in local folder.");
                Map<String, Long> localFiles = safManager.getFileModifiedMap(localDirUri);
                Log.d(TAG, "Found " + localFiles.size() + " files in local folder.");

                Set<String> driveFileNames = new HashSet<>();
                List<File> filesToSync = new ArrayList<>();

                // 2. Determine which files need to be downloaded or updated
                for (File driveFile : driveFiles) {
                    driveFileNames.add(driveFile.getName());
                    Long localFileModifiedTime = localFiles.get(driveFile.getName());
                    if (localFileModifiedTime == null) {
                        // File does not exist locally, needs to be downloaded
                        Log.d(TAG, "File to download (new): " + driveFile.getName());
                        filesToSync.add(driveFile);
                    } else {
                        // File exists locally, check if Drive version is newer
                        long driveModified = driveFile.getModifiedTime().getValue();
                        if (driveModified > localFileModifiedTime) {
                            Log.d(TAG, "File to update: " + driveFile.getName());
                            filesToSync.add(driveFile);
                        } else {
                            Log.d(TAG, "File up to date: " + driveFile.getName());
                        }
                    }
                }

                // 3. Initialize counters for sync summary
                int totalToSync = filesToSync.size();
                int syncedCount = 0, downloadedCount = 0, updatedCount = 0, skippedCount = 0, failedCount = 0, deletedCount = 0;

                Log.i(TAG, "syncDriveFolder: " + totalToSync + " files to sync.");

                // 4. Download or update files as needed
                for (File driveFile : filesToSync) {
                    String fileName = driveFile.getName();
                    Log.d(TAG, "Downloading: " + fileName);
                    Long localFileModifiedTime = localFiles.get(fileName);
                    boolean isNew = (localFileModifiedTime == null);

                    // Download file from Drive to local folder
                    boolean ok = driveManager.downloadFileToSAF(driveFile, localDirUri, safManager);
                    if (ok) {
                        if (isNew) {
                            downloadedCount++;
                            Log.d(TAG, "Downloaded new file: " + fileName);
                        } else {
                            updatedCount++;
                            Log.d(TAG, "Updated file: " + fileName);
                        }
                    } else {
                        failedCount++;
                        Log.e(TAG, "Failed to download: " + fileName);
                    }

                    syncedCount++;
                    final int progress = (int) (((syncedCount) * 100.0f) / (totalToSync == 0 ? 1 : totalToSync));
                    final int currentFile = syncedCount;
                    final int total = totalToSync;

                    // Update progress UI on main thread
                    runOnUiThread(() -> {
                        progressBar.setProgress(progress);
                        txtProgressPercent.setText(getString(R.string.progress_percent, progress));
                        txtProgressCount.setText(getString(R.string.progress_count, currentFile, total));
                    });
                }

                // 5. Count skipped files (already up to date)
                skippedCount = driveFiles.size() - filesToSync.size();
                Log.d(TAG, "Skipped (already up to date): " + skippedCount);

                // 6. Delete local files that are not present in Drive
                Log.d(TAG, "Checking for local files to delete.");
                for (String localFile : localFiles.keySet()) {
                    if (!driveFileNames.contains(localFile)) {
                        Log.d(TAG, "Deleting local file not in Drive: " + localFile);
                        if (safManager.deleteFile(localDirUri, localFile)) {
                            deletedCount++;
                            Log.d(TAG, "Deleted: " + localFile);
                        } else {
                            Log.e(TAG, "Failed to delete: " + localFile);
                        }
                    }
                }

                // 7. Log and show sync summary
                Log.i(TAG, "Sync complete. Downloaded: " + downloadedCount +
                        ", Updated: " + updatedCount +
                        ", Skipped: " + skippedCount +
                        ", Failed: " + failedCount +
                        ", Deleted: " + deletedCount);

                final int finalDownloaded = downloadedCount, finalUpdated = updatedCount,
                        finalSkipped = skippedCount, finalFailed = failedCount,
                        finalDeleted = deletedCount;

                // Update UI with sync summary
                runOnUiThread(() -> {
                    String summary = getString(R.string.status_sync_complete,
                            finalDownloaded, finalUpdated, finalDeleted,
                            finalFailed, finalSkipped);
                    txtStatusSAF.setText(summary);
                    progressBar.setVisibility(View.GONE);
                    txtProgressPercent.setVisibility(View.GONE);
                    txtProgressCount.setVisibility(View.GONE);
                });

            } catch (Exception e) {
                // Handle errors and update UI
                Log.e(TAG, "Sync failed with exception.", e);
                runOnUiThread(() -> {
                    txtStatusSAF.setText(getString(R.string.status_sync_failed, e.getMessage()));
                    progressBar.setVisibility(View.GONE);
                    txtProgressPercent.setVisibility(View.GONE);
                    txtProgressCount.setVisibility(View.GONE);
                });
            }
        });
    }

    /**
     * Extracts a readable folder name from a URI.
     * @param uri The URI of the folder.
     * @return The folder name, or "Unknown" if it cannot be determined.
     */
    private String getFileNameFromUri(Uri uri) {
        if (uri == null) return "Unknown";
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId != null) {
                int colon = docId.indexOf(':');
                if (colon >= 0 && colon < docId.length() - 1) {
                    // Return the part after the colon as the folder name
                    return docId.substring(colon + 1);
                } else {
                    return docId;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getFileNameFromUri: Error extracting filename from URI: " + uri, e);
        }
        return "Unknown";
    }
}