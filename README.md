# Love Android Project (v1.0.3 build workflow fix)

This is a Kotlin + Jetpack Compose Android UI prototype.

## Included functionality
- Light-theme Home, Contacts, Dialpad and Settings screens
- Dialpad input and deletion
- SIM dialer handoff through Android `ACTION_DIAL`
- Clearly labelled call and group-call preview screens

## Important limitation
This is **not a live VoIP calling app yet**. App-call and group-call buttons open preview screens only; they do not transmit audio. Live calling still requires SIP/WebRTC signalling, STUN/TURN, a conference media server for 3-5 participants, and a lawful SIP trunk/PSTN provider for internet-to-phone-number calls.

## Build APK using GitHub Actions from a phone
1. Create a GitHub repository and upload the contents of this project folder.
2. Open the repository's **Actions** tab.
3. Select **Build Love Android APK**.
4. Tap **Run workflow** and wait for it to finish successfully.
5. Open the completed workflow run, scroll to **Artifacts**, and download `Love-app-debug`.
6. Extract the artifact ZIP. Install `app-debug.apk` on your Android phone.

The workflow installs Gradle 8.10.2 directly, so it does not depend on a missing Gradle wrapper script or wrapper JAR.

## Local build
Requires JDK 17, Android SDK Platform 35, and Gradle 8.10.2:
```bash
gradle assembleDebug
```
APK output: `app/build/outputs/apk/debug/app-debug.apk`.

Package: `com.love.calling.debug` for debug builds.
