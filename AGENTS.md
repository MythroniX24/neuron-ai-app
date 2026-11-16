# Neuron-AI — Agent instructions

## CI / GitHub workflow (standing rule)

Whenever GitHub access is available for this repository (connected via the
Freebuff GitHub integration), the agent MUST:

1. Commit the changes it makes.
2. Push them — first the working branch, then `main` once the work is ready.
3. Trigger and **follow** the `android-ci` workflow (`.github/workflows/android-ci.yml`)
   and keep watching it until a **debug APK artifact** (`neuron-ai-debug-apk`)
   is actually produced by the `build-debug` job's "Upload debug APK artifact"
   step. Do not stop after a push — an APK must be generated.
4. If a CI run fails, diagnose from the workflow logs, fix the cause, and
   re-push / re-run until the run succeeds and the APK artifact exists.

In short: with GitHub access available, always commit + push, and follow CI
through to a generated APK.
