/*
 * Copyright (C) 2026  Tobias Bielefeld
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * If you want to contact me, send me an e-mail at tobias.bielefeld@gmail.com
 */

package de.tobiasbielefeld.searchbar.helper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records 16 kHz mono PCM16 while the activity is visible. Stops on a second tap,
 * about a second of non-speech after the query has started, or a short cap.
 * Speech is detected by Silero VAD, and the Zipformer decodes each chunk as it arrives.
 */
public class VoiceSearchController {

    public static final int SAMPLE_RATE = 16000;
    private static final int MAX_SECONDS = 10;
    private static final int NO_SPEECH_MS = 5000;
    private static final int END_SILENCE_MS = 900;
    private static final int MIN_SPEECH_MS = 250;
    private static final int VAD_WINDOW = 512;
    private static final int PREROLL_WINDOWS = 6;

    public interface Callback {
        void onListeningStarted();

        void onTranscript(String text);

        void onEmpty();

        void onError();
    }

    private final Context context;
    private final SpeechEngine engine;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean recording = new AtomicBoolean(false);

    private AudioRecord audioRecord;

    public VoiceSearchController(Context context, SpeechEngine engine) {
        this.context = context.getApplicationContext();
        this.engine = engine;
    }

    public boolean hasPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isRecording() {
        return recording.get();
    }

    public void preload() {
        try {
            executor.execute(engine::preload);
        } catch (RejectedExecutionException ignored) {
            // The next query loads the models itself.
        }
    }

    public void start(Callback callback) {
        if (!recording.compareAndSet(false, true)) {
            return;
        }

        try {
            executor.execute(() -> record(callback));
        } catch (RejectedExecutionException exception) {
            recording.set(false);
            post(callback::onError);
        }
    }

    public void stop() {
        recording.set(false);
    }

    public void release() {
        recording.set(false);
        executor.shutdownNow();
        releaseRecorder();
        engine.release();
    }

    private void record(Callback callback) {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            recording.set(false);
            post(callback::onError);
            return;
        }

        int bufferSize = Math.max(minBuffer, SAMPLE_RATE / 5);
        AudioRecord recorder;
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2);
        } catch (SecurityException exception) {
            recording.set(false);
            post(callback::onError);
            return;
        }

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            recording.set(false);
            post(callback::onError);
            return;
        }

        audioRecord = recorder;
        recorder.startRecording();
        post(callback::onListeningStarted);

        short[] chunk = new short[bufferSize];
        int elapsedSamples = 0;
        int speechSamples = 0;
        int samplesSinceSpeech = 0;
        boolean speechStarted = false;
        boolean streamStarted = false;
        float[] window = new float[VAD_WINDOW];
        int windowFill = 0;
        float[] preroll = new float[VAD_WINDOW * PREROLL_WINDOWS];
        int prerollFill = 0;

        try {
            engine.startStream();
            streamStarted = true;
        } catch (RuntimeException exception) {
            recording.set(false);
            releaseRecorder();
            post(callback::onError);
            return;
        }

        while (recording.get() && elapsedSamples < SAMPLE_RATE * MAX_SECONDS) {
            int read = recorder.read(chunk, 0, chunk.length);
            if (read <= 0) {
                break;
            }

            int copy = Math.min(read, SAMPLE_RATE * MAX_SECONDS - elapsedSamples);
            elapsedSamples += copy;

            for (int i = 0; i < copy; i++) {
                float sample = chunk[i] / 32768.0f;
                window[windowFill++] = sample;
                if (windowFill < VAD_WINDOW) {
                    continue;
                }
                windowFill = 0;

                boolean speech;
                try {
                    speech = engine.isSpeech(window);
                } catch (RuntimeException exception) {
                    recording.set(false);
                    releaseRecorder();
                    engine.finishStream();
                    post(callback::onError);
                    return;
                }

                if (speech) {
                    speechSamples += VAD_WINDOW;
                    samplesSinceSpeech = 0;
                    if (!speechStarted && speechSamples >= SAMPLE_RATE * MIN_SPEECH_MS / 1000) {
                        speechStarted = true;
                        engine.accept(prerollCopy(preroll, prerollFill));
                    }
                } else {
                    samplesSinceSpeech += VAD_WINDOW;
                }

                if (speechStarted) {
                    engine.accept(windowCopy(window));
                } else {
                    prerollFill = rememberPreroll(preroll, prerollFill, window);
                }
            }

            // A quiet word is not the end of the query. Only a sustained non-speech
            // gap after speech has actually started closes the microphone.
            if (speechStarted && samplesSinceSpeech >= SAMPLE_RATE * END_SILENCE_MS / 1000) {
                break;
            }
            if (!speechStarted && samplesSinceSpeech >= SAMPLE_RATE * NO_SPEECH_MS / 1000
                    && elapsedSamples >= SAMPLE_RATE * NO_SPEECH_MS / 1000) {
                break;
            }
        }

        recording.set(false);
        releaseRecorder();

        String text = "";
        if (streamStarted) {
            try {
                text = engine.finishStream();
            } catch (RuntimeException exception) {
                post(callback::onError);
                return;
            }
        }

        if (!speechStarted || text.isEmpty()) {
            post(callback::onEmpty);
        } else {
            String transcript = text;
            post(() -> callback.onTranscript(transcript));
        }
    }

    private static float[] windowCopy(float[] window) {
        float[] copy = new float[window.length];
        System.arraycopy(window, 0, copy, 0, window.length);
        return copy;
    }

    private static int rememberPreroll(float[] preroll, int fill, float[] window) {
        if (fill + window.length <= preroll.length) {
            System.arraycopy(window, 0, preroll, fill, window.length);
            return fill + window.length;
        }
        System.arraycopy(preroll, window.length, preroll, 0, preroll.length - window.length);
        System.arraycopy(window, 0, preroll, preroll.length - window.length, window.length);
        return preroll.length;
    }

    private static float[] prerollCopy(float[] preroll, int fill) {
        float[] copy = new float[fill];
        System.arraycopy(preroll, 0, copy, 0, fill);
        return copy;
    }

    private void post(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    private void releaseRecorder() {
        AudioRecord recorder = audioRecord;
        audioRecord = null;
        if (recorder == null) {
            return;
        }
        try {
            recorder.stop();
        } catch (IllegalStateException ignored) {
            // startRecording may have failed, or stop already ran.
        }
        recorder.release();
    }

}
