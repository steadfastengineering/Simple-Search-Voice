# Voice search augmentation

Add on-device English voice search to this Android app. Bake a Whisper tiny.en model into the APK and add a microphone button on the right side of the home-screen widget bar. Tapping it should feel like the Google search widget: the user speaks, the query is transcribed locally, and the existing search path runs.

Do not use Android `SpeechRecognizer`, `RecognizerIntent`, or ML Kit. Those are not a baked tiny.en model.

## Constraints

- This is a Java Android app (`minSdk` 21, release minify on). Package `de.tobiasbielefeld.searchbar`.
- Home-screen widgets are `RemoteViews`. They cannot record audio, run inference, or show a live listening UI.
- Android will not grant microphone access to a launcher widget. Recording must happen in a foreground activity.
- `MainActivity` is `singleTop`. A second launch goes to `onNewIntent()` then `loadIntent()`, not only `onCreate()`.
- `startSearch()` returns early while `searchEngineListLoading` is true. The engine list is loaded asynchronously in `onResume()`.
- tiny.en is English-only. Other app locales will not transcribe.
- whisper.cpp is MIT and Whisper weights are MIT. Both are compatible with this GPLv3+ app. Keep attribution.

## Runtime

Use whisper.cpp through the GiviMAD `whisper-jni` Maven artifact. It ships the native library, so do not add CMake or the NDK. Inference is CPU-only. A short query is typically under a second on arm64.

If that wrapper's minSdk or ABIs conflict with this app, fall back to sherpa-onnx's prebuilt Android AAR plus its int8 Whisper tiny.en ONNX bundle. That Java API is easier, but the ONNX runtime is larger. Do not use PyTorch Mobile or TFLite.

## Model

Bake one file: `ggml-tiny.en-q5_1.bin` (~31 MB) from the whisper.cpp ggml builds. Put it in `app/src/main/assets/`. `q8_0` (~42 MB) is the accuracy upgrade. Full fp16 tiny.en (~75 MB) is not worth it.

whisper.cpp needs a real filesystem path. On first run, copy the asset into `filesDir`. Do not open it from assets. In `app/build.gradle`, `noCompress` the model extension or the copy will be corrupt. Prefer Git LFS or a pinned build-time download so the repo does not grow by 31 MB.

## Flow

1. The mic `PendingIntent` starts `MainActivity` with an extra such as `EXTRA_VOICE=true`. The rest of the bar stays a normal launch.
2. Handle that extra in `loadIntent()`, which already runs from both `onCreate()` and `onNewIntent()`. Clear the extra so rotation or a later resume does not record again.
3. Request `RECORD_AUDIO` at runtime. Record 16 kHz mono PCM16 with `AudioRecord` while the activity is visible. Stop on a second tap, ~1 second of silence, or an 8–10 second cap.
4. Convert PCM16 to float and run whisper.cpp's blocking full transcribe on a background thread.
5. Drop blank audio and common tiny.en silence hallucinations (`thanks for watching`, `you`, and similar).
6. Put the text in `editTextSearch` via `setSearchText()`, then call `startSearch()`.
7. If the transcript is ready before the search-engine list finishes loading, hold it and submit from that loading callback.
8. Skip `focusSearchBar()`'s keyboard while listening.

No foreground service. A microphone-type service requires a sticky notification and is the wrong tool for tap-to-search.

## Hooks to modify

Widget layouts:

- `app/src/main/res/layout/widget_layout.xml`
- `app/src/main/res/layout/widget_layout2.xml`
- `app/src/main/res/layout/widget_layout3.xml`

The search icon is already on the right. Add an `ImageView` after it, `@+id/widget_voice`. Do not put the voice `PendingIntent` on the root `search_widget`. Give the text area its own id and attach the two click targets separately.

Widget providers:

- `app/src/main/java/de/tobiasbielefeld/searchbar/classes/CustomWidgetProvider.java`
- `app/src/main/java/de/tobiasbielefeld/searchbar/classes/CustomWidgetProvider2.java`
- `app/src/main/java/de/tobiasbielefeld/searchbar/classes/CustomWidgetProvider3.java`

Each currently sets one immutable `PendingIntent` on `R.id.search_widget`. Add a second voice `PendingIntent`. `Intent.filterEquals` ignores extras, so the voice intent needs its own request code or it will overwrite the open-app intent. A shared helper is fine so the three providers do not drift.

Manifest:

- `app/src/main/AndroidManifest.xml`: add `android.permission.RECORD_AUDIO`.

Search submit:

- `app/src/main/java/de/tobiasbielefeld/searchbar/ui/MainActivity.java`
- Voice entry is `loadIntent()`.
- Submit hooks are `setSearchText()` and `startSearch()` only.
- Keep recording and inference out of the activity.

New helpers:

- `helper/VoiceSearchController.java` — permission, `AudioRecord`, stop rules.
- `helper/WhisperEngine.java` — copy model, init once, transcribe.

In-app bar (this is the listening UI and the retry button; the widget cannot show meters or partial text):

- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/activity_main_searchbar_bottom.xml`

Add a mic `ImageView` to the right of `editTextSearch`.

Build and packaging:

- `app/build.gradle` — whisper-jni dependency, `noCompress` for the model extension, ABI filters for at least `arm64-v8a` and `armeabi-v7a`.
- `app/proguard-rules.pro` — keep the JNI classes. Release minify is already on.
- `app/src/main/assets/ggml-tiny.en-q5_1.bin`

Strings:

- `app/src/main/res/values/strings.xml` — mic content description, "Listening…", permission denied. Missing translations are already lint-disabled.

Optional: a settings toggle. Default behavior is tap, transcribe, search. Widget preview drawables may be updated so the mic shows in the picker.

## Out of scope

- Do not record or run the model inside `AppWidgetProvider`.
- Do not add a microphone foreground service.
- Do not stream partial transcripts. Record, then transcribe.
- Do not add CMake/NDK unless the Maven wrapper cannot be used.
