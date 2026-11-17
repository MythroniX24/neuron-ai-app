# Release signing & APK delivery

Neuron-AI ships two kinds of APK from CI:

| Artifact | Job | Installable? | Signature |
|---|---|---|---|
| `neuron-ai-debug-apk` | `build-debug` | yes (debug build, Play Protect warnings are more likely) | the **release key** when the secrets below exist, otherwise the runner's debug key |
| `neuron-ai-release-apk` | `build-release` | yes **when the signing secrets exist**; otherwise built UNSIGNED and not installable | the release key |

A **signed release APK** is the one to install: same app, properly signed, far
fewer "app may be harmful" warnings during sideload. The **"Install unknown
apps"** permission prompt always remains for APKs installed outside the Play
Store — that is an OS rule, not a problem.

The keystore **never** goes into the repository. CI reads it from GitHub Secrets
at build time and writes it to a temporary runner path (`NEURON_KEYSTORE_PATH`).

## 1. Create a keystore (on a PC, once)

`keytool` ships with any JDK:

```bash
keytool -genkeypair -v \
  -keystore neuron-release.jks \
  -alias neuron \
  -keyalg RSA -keysize 4096 -validity 10000
```

- Set and **remember** the keystore password and the key password (they may be
  the same).
- The name/organisation prompts accept anything.
- **Back the file up.** If it is lost, no future release can install over the
  existing app — Android treats a different key as a different app (users would
  have to uninstall and lose their chats and local models).

## 2. Base64-encode it

```bash
base64 -w0 neuron-release.jks > neuron-release.b64     # Linux
base64 -i neuron-release.jks -o neuron-release.b64     # macOS
```

## 3. Add four GitHub Secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `NEURON_KEYSTORE_B64` | full contents of `neuron-release.b64` |
| `NEURON_KEYSTORE_PASSWORD` | keystore password from step 1 |
| `NEURON_KEY_ALIAS` | `neuron` |
| `NEURON_KEY_PASSWORD` | key password from step 1 |

Nothing else needs configuring: `build-debug` and `build-release` both decode
the keystore into `$RUNNER_TEMP`, export `NEURON_KEYSTORE_PATH`, and
`app/build.gradle.kts` picks up all four variables (the debug build type uses
the same key, see below).

## Why both APKs use the release key

Google Sign-In checks the running APK's **package name + signing certificate
SHA-1** against the Android OAuth client. If that fingerprint changes, the
Google Cloud entry silently stops matching and the consent flow comes back as a
plain *cancel* (`result 0`, no authorization code) — which looks like a bug in
the app but is a signing change.

A CI debug build used to be signed by the runner's own `~/.android/debug.keystore`.
That key is generated per runner and the `actions/cache` entry that pinned it can
be evicted, so the SHA-1 changed between runs and a registered fingerprint went
stale every time.

Now, when the signing secrets exist, `build-debug` is signed with the same
long-lived release key, so there is exactly **one** fingerprint to register and it
never changes. `Report APK signing fingerprint` prints the SHA-1 that is really
inside the APK (`apksigner verify --print-certs`) in the job summary. Without the
secrets the workflow falls back to the cached debug keystore.

## 4. What happens on the next push

1. `build-debug` runs `./gradlew testDebugUnitTest`, builds the debug APK,
   prints its real signing SHA-1 and uploads `neuron-ai-debug-apk`.
2. `build-release` runs `./gradlew assembleRelease` and uploads
   `neuron-ai-release-apk` — signed now that the secrets exist.
3. Install the **release** APK from **Actions → android-ci → build-release**.

## Tags publish a Release

Pushing a tag such as `v1.2.6` runs the same jobs and then `attach-to-release`,
which uploads the release APK to the GitHub Release for that tag (with generated
notes). Releases are listed at
[github.com/MythroniX24/neuron-ai-app/releases](https://github.com/MythroniX24/neuron-ai-app/releases).

Because every release is signed with the same key, a newer release installs
straight over an older one — no uninstall, no lost data (chats live in Room,
models in app-private storage, and both survive an upgrade).

## Local builds

`assembleRelease` on a workstation uses the same four variables:

```bash
export NEURON_KEYSTORE_PATH=/path/to/neuron-release.jks
export NEURON_KEYSTORE_PASSWORD=…
export NEURON_KEY_ALIAS=neuron
export NEURON_KEY_PASSWORD=…
./gradlew assembleRelease
```

Without them the build still succeeds and produces an **unsigned** APK (it just
cannot be installed), so a missing keystore never breaks development or CI.

## Versioning

Version and build number live in `app/build.gradle.kts`
(`versionCode` / `versionName`). Android only accepts an update whose
`versionCode` is higher than the installed one, so bump both for every
user-facing release, then push and tag.

## Notes

- Keep the signing config **out of git** — it is env-driven by design.
- Changing the key later requires uninstall/reinstall (chat history and local
  models are lost unless backed up). Choose a strong password once and keep it.
- The debug APK remains available as a workflow artifact; it is useful for
  development but not recommended for daily use.
