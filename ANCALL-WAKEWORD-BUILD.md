# ANCALL wake-word build

This branch adapts the Sherpa-ONNX Android keyword-spotting sample into an
ANCALL foreground wake-word listener.

## What the build contains

- English GigaSpeech keyword-spotting model.
- A generated default keyword entry for **HEY ANCALL**.
- An Android foreground microphone service with a persistent notification.
- Start/stop controls in the ANCALL app.
- A Termux RUN_COMMAND handoff to the existing
  `~/ancall-android/voice/assistant.py` script.

The build uses the open-vocabulary English keyword tokenizer to create the
model-specific keyword tokens. The APK workflow installs the `sherpa-onnx`
Python package for this step.

## Build and install

1. Open the **Actions** tab.
2. Select **ANCALL Android KWS APK**.
3. Choose **Run workflow** on branch `ancall-wakeword`.
4. When the run succeeds, download the artifact named
   `ancall-android-apks`.
5. On the itel A665L, use the **arm64-v8a English GigaSpeech** APK. It is a
   debug-signed APK suitable for local testing.

The workflow does not require repository secrets, does not publish releases,
and does not modify the upstream `k2-fsa/sherpa-onnx` repository.

## One-time Termux handoff setup

For ANCALL to launch the existing Python assistant after hearing the wake
phrase, Termux must allow external app commands:

1. In Termux, open or create `~/.termux/termux.properties`.
2. Add this setting:
   `allow-external-apps = true`
3. Run `termux-reload-settings`.
4. In Android app settings, allow ANCALL to use Termux's RUN_COMMAND permission
   if Android displays a permission prompt.

Do not put the Gemini API key in this app or in the GitHub repository. It stays
in the existing private Termux config file.

## Android behavior and limitations

- The user must open ANCALL and tap **Start listening**; Android does not allow
  this app to silently start microphone monitoring at boot.
- Android shows an ongoing foreground-service notification while listening.
  The notification includes a Stop listening action.
- The first launch asks for microphone permission and, on Android 13+, the
  notification permission.
- After detecting the wake phrase, the listener pauses while the Termux
  assistant uses speech recognition. The Termux command then asks the already
  running foreground service to resume.
- Battery optimization or manufacturer background restrictions may still stop
  long-running listening. This needs real-device testing.
- This is an initial implementation. Detection threshold, pronunciation,
  false triggers, microphone behavior, and Termux handoff must be tested on
  the target phone before relying on it.

The APK is a testing build, not a Play Store release.
