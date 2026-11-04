# ANCALL wake-word build track

This branch contains an isolated GitHub Actions build workflow for the upstream
Sherpa-ONNX Android keyword-spotting sample.

## Build an APK

1. Open the **Actions** tab.
2. Select **ANCALL Android KWS APK**.
3. Choose **Run workflow** on branch `ancall-wakeword`.
4. When the run succeeds, download the artifact named
   `ancall-sherpa-kws-unsigned-apks`.

The APKs are unsigned build outputs. This workflow does not require repository
secrets, does not publish releases, and does not modify the upstream
`k2-fsa/sherpa-onnx` repository.

## Important: this is a baseline, not the ANCALL trigger yet

The upstream sample currently builds the pretrained Chinese Wenetspeech and
English GigaSpeech keyword models. It does **not** yet recognize the custom
phrase **"Hey ANCALL"**. We must integrate and test a compatible custom keyword
model before treating it as ANCALL's wake-word detector.
