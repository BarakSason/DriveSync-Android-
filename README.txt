# DriveSync

## Overview

DriveSync is an Android app that allows users to synchronize files from a selected Google Drive folder to a local folder on their device. It features Google sign-in, Drive and local folder selection, and a one-click sync process, ensuring your local folder mirrors the contents of your chosen Drive folder.

## Project Structure

    app/
     ├── src/
     │   ├── main/
     │   │   ├── java/com/barak/drivesync/MainActivity.java
     │   │   ├── res/layout/activity_main.xml
     │   │   └── res/values/strings.xml
     │   └── test/
     ├── build.gradle
     ├── .gitignore
     └── ...

- `MainActivity.java`: Main activity with authentication, folder selection, and sync logic.
- `activity_main.xml`: UI layout for the main screen.
- `strings.xml`: String resources.
- `.gitignore`: Files and folders ignored by git.

## Setup Instructions

1. **Clone the repository:**
    ```
    git clone https://github.com/BarakSason/DriveSync.git
    cd DriveSync
    ```

2. **Open in Android Studio:**
    - Open the project folder in Android Studio.

3. **Configure Google API:**
    - Create a Google Cloud project and enable the Drive API.
    - Download `google-services.json` and place it in the `app/` directory.

4. **Build the project:**
    - Sync Gradle and build the project in Android Studio.

5. **Run the app:**
    - Deploy to an Android device or emulator.

## Usage

1. Launch the app.
2. Sign in with your Google account.
3. Select a Google Drive folder.
4. Select a local folder on your device.
5. Tap the **Sync** button to synchronize files from Drive to your local folder.

## Dependencies

- Google Play Services Auth
- Google Drive API
- AndroidX Libraries
- Glide (for user avatar)
- Java 8+

All dependencies are managed via Gradle.

## License

This project is licensed under the MIT License. See the `LICENSE` file for details.