# Покадрово — восстановленный нативный Android-проект

**Restored development sources, not original recovered source code.** Built from analysis of user-provided `Pokadrovo-2.0.apk`, the `krest0208/pokadrovo` README and described product behavior. The site `framecut-studio.kh-ng-gian-l-3883.chatgpt.site` could not be inspected due to restricted access / Cloudflare challenges. Consequently this is **not** an exact copy of the web app or the original Android source tree.

## Included

- An **actual Android Java app** with `ru.pokadrovo.app` package. No WebView.
- Native media picker (`ACTION_OPEN_DOCUMENT`, persisted URI grants), previews using Media3 `CompositionPlayer` (experimental), and non-destructive `EditProject` model.
- Timeline, drag/zoom, split, trim, ripple delete, move, duplicate, undo/redo, multiple aspect ratios, 720p/1080p selection.
- Video brightness/contrast/rotation, layer text/images with position/size/opacity, SRT import, speed, volume, stereo balance and fading.
- Local JSON project storage; MP4 export service using Media3 `Transformer` with foreground progress and output using Android's system save picker.
- Account login/registration/reset UI, Google/VK browser OAuth scaffolding (PKCE), Android Keystore access-token store, and admin users/visits/registrations/purchases/revenue charts. A **separate Node.js/SQLite backend** with mobile API and browser administration pages is included under `server/`; it must be deployed and configured before Android online features work.

## Build instructions

Install Android Studio (JDK 17+, Android SDK 35), open this directory as the project. The Gradle project uses Android Gradle Plugin 8.7.3, Media3 1.9.0 and minSdk 26 (Android 8.0+). Gradle 8.12 is required; if Android Studio asks for a Gradle wrapper, generate one with an installed Gradle using `gradle wrapper --gradle-version 8.12`. Builds using `gradle :app:assembleDebug`, or use `.github/workflows/android-build.yml` after uploading all source files to GitHub.

To configure the app's server origin (**not the public website URL unless you actually host the mobile API there**):

```
gradle :app:assembleDebug -Ppokadrovo.apiBase=https://api.yourdomain.example
```

Without `pokadrovo.apiBase`, network account buttons are disabled and admin screens report no data rather than displaying fabricated accounts or statistics. See [docs/BACKEND-CONTRACT.md](docs/BACKEND-CONTRACT.md) for the required API protocol. OAuth providers also require configured Google/VK applications and server credentials.

Run the pure-Java model tests without Android SDK: `bash core-tests/run.sh`. For the included backend API, run `cd server && npm test` (Node.js 22.13+), then start it with `node --env-file=.env --experimental-sqlite index.mjs` after copying `.env.example` to `.env`. Provision the owner with `ADMIN_INITIAL_PASSWORD=... node --env-file=.env --experimental-sqlite admin.mjs owner@example.com`. See [docs/BACKEND-CONTRACT.md](docs/BACKEND-CONTRACT.md).

## Important limitations

- **This environment could run `javac` core tests but had no Android SDK/Gradle binaries or networking to obtain them**; building the Android APK, emulator boot and physical device/export tests were not completed here. The GitHub Actions workflow can build an APK after source upload and may expose SDK/dependency issues needing fixes.
- It is **not digitally signed with the original app owner's key**. A debug APK won't install over the earlier release without uninstalling it, which may remove local projects. Back up projects before uninstalling; to preserve upgrade compatibility, build and sign with the original private keystore that only the owner should hold.
- Local media URI permissions are tied to Android's system document provider; if original files are removed or permission revoked, a saved project may not load media.
- CompositionPlayer is experimental and some codec/device combinations can fail; audio/effects and exports require phone tests. A test run of the editing logic is not proof of successful MP4 output.
- Some fields from the original APK (`bass`, `treble`, `compress`, `fill`) are retained for future engine work but not fully implemented in effect processing/UI.
- **No real users from the existing site, live purchases, AI, cloud sync, or production social login are in this archive.** The included self-hosted server starts with an empty SQLite database and tracks only its own users and events after deployment. APK decompilation cannot recover backend secrets or user records.

## Repository layout

- `app/src/main/java/ru/pokadrovo/app/` — Android screens, data model, player/export, API client, UI.
- `app/src/main/AndroidManifest.xml` — native entrypoints, permissions, OAuth callback.
- `core-tests/` — Java-only repeatable tests of timeline, JSON and captions.
- `server/` — self-hostable Node.js backend (SQLite accounts, admin, OAuth scaffolding, browser account/admin pages, API tests).
- `docs/BACKEND-CONTRACT.md` — implemented API endpoints, hosting, and security requirements.
- `.github/workflows/android-build.yml` — CI debug APK build and artifact upload.

## GitHub upload, without git CLI

At `https://github.com/krest0208/pokadrovo`, use **Add file → Upload files** and drag the contents of this archive (including the hidden `.github` directory) into the repository, then commit to `main`. Do not upload `local.properties`, `.env`, signing keys, user records, or a real signing backup. GitHub's Actions tab > “Build Android debug APK” > Run workflow can then build a test APK. This archive does not include a new compiled APK. The older supplied 2.0 APK is a reference build, not the output of this restored source.
