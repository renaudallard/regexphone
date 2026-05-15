<p align="center">
  <img src="branding/playstore/feature_graphic.png" alt="RegexPhone" />
</p>

<h1 align="center">RegexPhone</h1>

<p align="center">
  Block or allow incoming calls with regular expressions.<br/>
  Native Android <code>CallScreeningService</code>, no background daemons, no contacts permission, no network.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/minSdk-31-3DDC84?logo=android&logoColor=white" alt="minSdk 31" />
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.0" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/build-Gradle%208.10-02303A?logo=gradle&logoColor=white" alt="Gradle 8.10" />
</p>

---

## What it does

For every incoming call, RegexPhone matches the caller's phone number against your rules and either lets it ring or rejects it. Each rule is a regular expression with an action (`BLOCK` or `ALLOW`) and, for block rules, controls over whether the missed-call notification and the call-log entry should appear.

## Highlights

- **Two actions per rule.** `BLOCK` rejects the call; `ALLOW` whitelists.
- **Per-block flags.** Skip the missed-call notification and/or skip the call-log entry, independently, per rule.
- **Allow beats block.** Evaluation is order-independent: if any allow rule matches, the call rings.
- **Live tester.** The edit screen previews the verdict and which flags will apply for a sample number as you type.
- **Import / Export.** Save the full rule set to a JSON file via Storage Access Framework, restore it on another device, or merge two sets together. No permissions needed.
- **Simple storage.** Rules are JSON in `SharedPreferences`, read synchronously inside the screening service so there is no risk of an ANR.
- **No background services, no contacts permission, no network access.**

## Screenshots

<p align="center">
  <img src="branding/screenshots/rules-list.png" alt="Rules list" width="300" />
  &nbsp;&nbsp;
  <img src="branding/screenshots/edit-rule.png" alt="Edit rule" width="300" />
</p>

<p align="center"><em>Left: rules list with role-status banner. Right: edit screen with per-rule notification and call-log toggles.</em></p>

## How matching works

| Aspect | Behaviour |
| --- | --- |
| Source | `Call.Details.handle.schemeSpecificPart`, URI-decoded |
| Hidden / withheld numbers | match as the empty string; block them with `^$` |
| Match function | `Matcher.find()` (substring); anchor with `^` and `$` for whole-number match |
| Invalid regex | never matches; the editor refuses to save it |

For each incoming call:

1. If any enabled `ALLOW` rule matches, the call is allowed.
2. Else if any enabled `BLOCK` rule matches, the call is rejected. The **skip notification** and **skip call log** flags of the *first* matching block rule apply.
3. Otherwise the call is allowed.

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch tap **Set as default** in the status card and accept the system dialog; the card turns green once the role is granted.

## Build from source

| Tool | Version |
| --- | --- |
| JDK | 17 or 21 |
| Android SDK | Platform 35 and Build-tools 35.0.x |
| Gradle | 8.10.2 (via wrapper) |

```sh
git clone https://github.com/<user>/regexphone.git
cd regexphone

# One-time, only if gradle/wrapper/gradle-wrapper.jar is missing:
gradle wrapper --gradle-version 8.10.2

./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Release builds

`./gradlew assembleRelease` produces `app/build/outputs/apk/release/app-release.apk`. The build script picks up signing credentials from Gradle properties (typically `~/.gradle/gradle.properties`):

```
REGEXPHONE_KEYSTORE_PATH=/absolute/path/to/keystore.jks
REGEXPHONE_KEYSTORE_PASSWORD=...
REGEXPHONE_KEY_ALIAS=regexphone
REGEXPHONE_KEY_PASSWORD=...
```

If `REGEXPHONE_KEYSTORE_PATH` is not set, `assembleRelease` still works and emits `app-release-unsigned.apk`. Generate a fresh keystore with:

```sh
keytool -genkeypair -keystore ~/.keystores/regexphone-release.jks \
  -storetype PKCS12 -alias regexphone -keyalg RSA -keysize 2048 \
  -validity 36500 -dname "CN=Your Name, O=RegexPhone"
```

Keystore files (`*.jks`, `*.keystore`) are gitignored. Back the keystore up off-device; losing it means you can never sign a follow-up release with the same identity.

<details>
<summary>Debian arm64 setup (the official <code>google-android-*-installer</code> packages are amd64-only)</summary>

```sh
sudo apt install openjdk-21-jdk
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
mkdir -p ~/Android/Sdk/cmdline-tools
unzip commandlinetools-linux-13114758_latest.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export PATH=~/Android/Sdk/cmdline-tools/latest/bin:$PATH

yes | sdkmanager --licenses
sdkmanager 'platforms;android-35' 'build-tools;35.0.1' 'platform-tools'

echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

Debian's `gradle` is 4.4.1, which is too old to bootstrap AGP 8. Either copy `gradle/wrapper/gradle-wrapper.jar` from a host that has it, or grab a standalone Gradle 8.10.2:

```sh
curl -LO https://services.gradle.org/distributions/gradle-8.10.2-bin.zip
unzip gradle-8.10.2-bin.zip -d ~/Android
~/Android/gradle-8.10.2/bin/gradle wrapper --gradle-version 8.10.2
```

</details>

## Project layout

```
app/src/main/java/it/allard/regexphone/
├── MainActivity.kt
├── data/
│   ├── Rule.kt                      data class + compiled-Pattern cache
│   └── RuleRepository.kt            singleton, SharedPreferences-backed
├── service/
│   └── FilterCallScreeningService.kt    pure decide() + the Android binding
└── ui/
    ├── Theme.kt
    ├── RulesListScreen.kt           list + role-status card + FAB
    └── EditRuleScreen.kt            form + live tester
```

Tests live at `app/src/test/java/it/allard/regexphone/DecideTest.kt` and exercise `FilterCallScreeningService.decide()` end-to-end without any Android stubs.

## Limitations

- Only incoming calls; the `CallScreeningService` API has no outgoing-call hook.
- No contact-name matching (that would need `READ_CONTACTS`).
- No rule import/export, no reordering, no silence-only action.

## Branding

The icon set under `branding/` is generated from `branding/source/*.svg` and theme color `#5E5BFF`. The monochrome layer is wired up for Android 13+ themed icons.
