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
  <img src="https://img.shields.io/badge/license-BSD--2--Clause-blue" alt="BSD 2-Clause license" />
  <img src="https://img.shields.io/github/downloads/renaudallard/regexphone/total?logo=github&logoColor=white&label=downloads&cb=2" alt="GitHub downloads" />
</p>

---

## What it does

For every incoming call, RegexPhone matches the caller's phone number against your rules and either lets it ring, rejects it, or silences the ringtone. Each rule is a regular expression with an action (`BLOCK`, `SILENCE`, or `ALLOW`) and, for block rules, controls over whether the missed-call notification and the call-log entry should appear.

## Highlights

- **Three actions per rule.** `BLOCK` rejects the call; `SILENCE` mutes the ringtone but lets the call still hit the call log and notification shade; `ALLOW` whitelists.
- **Per-block flags.** Skip the missed-call notification and/or skip the call-log entry, independently, per rule.
- **Predictable precedence.** Allow beats block beats silence; otherwise the call is allowed. Order of rules within each action is irrelevant.
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
3. Else if any enabled `SILENCE` rule matches, the call rings silently (no audible ringtone), but the call log and notifications are unaffected.
4. Otherwise the call is allowed.

## Example rules

> All numbers below use the NANP fictional ranges (`555` exchange in any area code, or area code `555`) so they cannot belong to any real subscriber.

| Pattern | Action | What it does |
| --- | --- | --- |
| `^\+12025550123$` | `BLOCK` | Block exactly one specific number. Substitute the real number from your call log. |
| `^\+44` | `BLOCK` | Block every call from a country (here `+44` is the UK). Works for any country code. |
| `^$` | `BLOCK` | Block withheld / hidden numbers — Android delivers an empty string in that case. |
| `^\+1555\d{7}$` | `BLOCK` | Block a whole carrier or number range. `\d{7}` matches the seven digits after the fixed `+1555` prefix. |
| `^\+32`<br/>`.*` | `ALLOW`<br/>`BLOCK` | Two rules together: whitelist a country (Belgium), reject everything else. `.*` matches any sequence including the empty string. |
| `^\+1` | `SILENCE` | Mute the ringtone for calls from a country. The call still hits the call log and notification shade. |

## Install

Grab the latest signed APK from the [Releases page](https://github.com/renaudallard/regexphone/releases/latest), then:

```sh
adb install -r regexphone-X.Y.Z.apk
```

On first launch tap **Set as default** in the status card and accept the system dialog; the card turns green once the role is granted.

## Build from source

| Tool | Version |
| --- | --- |
| JDK | 17 or 21 |
| Android SDK | Platform 35 and Build-tools 35.0.x |
| Gradle | 8.10.2 (via wrapper) |

```sh
git clone https://github.com/renaudallard/regexphone.git
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
│   ├── RuleIO.kt                    pure encode / decode / merge helpers
│   └── RuleRepository.kt            singleton, SharedPreferences-backed
├── service/
│   └── FilterCallScreeningService.kt    pure decide() + the Android binding
└── ui/
    ├── Theme.kt
    ├── RulesListScreen.kt           list + role-status card + FAB + menu
    └── EditRuleScreen.kt            form + live tester
```

Tests live under `app/src/test/java/it/allard/regexphone/`: `DecideTest.kt` exercises `FilterCallScreeningService.decide()`, `RuleIOTest.kt` covers JSON round-trip and merge-with-fresh-ids. Both run without Android stubs.

## Limitations

- Only incoming calls; the `CallScreeningService` API has no outgoing-call hook.
- **Callers already in your contact list bypass the regex entirely.** Android's telecom layer short-circuits `CallScreeningService` when the incoming number matches a saved contact: it returns *allow* without ever invoking the screening service, so no rule of yours can run. To block a number that is in contacts, delete (or temporarily delete) the contact entry first. This is by design at the system level — the official `CallScreeningService` documentation states the service is "called when a new incoming or outgoing call is added which is not in the user's contact list."

---

## Support this project

If you find RegexPhone useful, you can support development:

[![PayPal](https://img.shields.io/badge/PayPal-Donate-blue.svg?logo=paypal)](https://www.paypal.me/RenaudAllard)

## License

BSD 2-Clause "Simplified" License. Copyright (c) 2026, Renaud Allard <renaud@allard.it>. See [LICENSE](LICENSE) for the full text. Every Kotlin source file carries the same header.

## Branding

The icon set under `branding/` is generated from `branding/source/*.svg` and theme color `#5E5BFF`. The monochrome layer is wired up for Android 13+ themed icons.
