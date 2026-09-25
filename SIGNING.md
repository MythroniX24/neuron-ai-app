# Release Signing Setup (one-time, ~5 minutes)

A **signed** release APK removes the scary **"App is harmful / Play Protect
scan"** style warnings that debug APKs show while installing. The
**"Install unknown apps"** permission prompt will always remain for any APK
installed outside the Play Store — that is an OS rule, not a problem.

The keystore **never** goes into the repository. CI reads it from GitHub
Secrets at build time.

## 1. Create a keystore (on a PC, once)

You need Java installed (`keytool` ships with it). Run:

```bash
keytool -genkeypair -v \
  -keystore neuron-release.jks \
  -alias neuron \
  -keyalg RSA -keysize 4096 -validity 10000
```

- Set and REMEMBER the keystore password and key password (can be the same).
- Answer the name/organization prompts with anything (e.g. your name).
- **Back this file up.** If it is lost, updates cannot be installed over the
  old app (Android treats different signing keys as different apps).

## 2. Base64-encode it

```bash
base64 -w0 neuron-release.jks > neuron-release.b64     # Linux
base64 -i neuron-release.jks -o neuron-release.b64     # macOS
```

## 3. Add 4 GitHub Secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret name | Value |
|---|---|
| `NEURON_KEYSTORE_B64` | full contents of `neuron-release.b64` |
| `NEURON_KEYSTORE_PASSWORD` | keystore password from step 1 |
| `NEURON_KEY_ALIAS` | `neuron` |
| `NEURON_KEY_PASSWORD` | key password from step 1 |

## 4. Done

Every push now also uploads **`neuron-ai-release-apk`** — install THAT one
instead of the debug APK. Same app, properly signed, minimal warnings.

> If secrets are not set, CI still succeeds and uploads an UNSIGNED release
> APK (it just cannot be installed) — the debug APK keeps working as before.

## Notes

- Keep signing config OUT of git — this setup reads everything from env
  vars (see `app/build.gradle.kts`).
- To change the key later you must uninstall/reinstall the app (chat history
  is lost unless backed up). Choose a strong password once and keep it.
