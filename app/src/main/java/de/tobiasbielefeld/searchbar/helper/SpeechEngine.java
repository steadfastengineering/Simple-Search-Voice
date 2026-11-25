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

import android.content.Context;

import java.util.Locale;

import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

/**
 * On-device English transcription with sherpa-onnx's streaming Zipformer.
 *
 * The runtime is the sherpa-onnx Android AAR (Apache-2.0). Both the GigaSpeech weights and
 * Silero VAD are downloaded by {@link SpeechModelStore}. A null asset manager tells the runtime
 * to read those paths from disk.
 */
public class SpeechEngine {

    private static final int VAD_WINDOW = 512;

    private final SpeechModelStore models;
    private OnlineRecognizer recognizer;
    private Vad vad;
    private OnlineStream stream;
    private volatile boolean loaded;

    public SpeechEngine(Context context) {
        models = new SpeechModelStore(context.getApplicationContext());
    }

    public boolean isLoaded() {
        return loaded;
    }

    /** Builds both models so a microphone tap does not pay that cost. */
    public synchronized void preload() {
        if (loaded) {
            return;
        }
        recognizer();
        vad();
        loaded = true;
    }

    public synchronized void startStream() {
        finishStream();
        stream = recognizer().createStream("");
    }

    /** Feeds one chunk and decodes whatever the streaming model can consume now. */
    public synchronized void accept(float[] samples) {
        if (stream == null || samples.length == 0) {
            return;
        }
        OnlineRecognizer active = recognizer();
        stream.acceptWaveform(samples, VoiceSearchController.SAMPLE_RATE);
        while (active.isReady(stream)) {
            active.decode(stream);
        }
    }

    public synchronized String finishStream() {
        OnlineStream activeStream = stream;
        stream = null;
        if (activeStream == null) {
            return "";
        }
        try {
            OnlineRecognizer active = recognizer();
            activeStream.inputFinished();
            while (active.isReady(activeStream)) {
                active.decode(activeStream);
            }
            String text = active.getResult(activeStream).getText();
            return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        } finally {
            activeStream.release();
        }
    }

    /**
     * True when this 512-sample window sounds like speech. Silero ignores steady room
     * noise that a loudness gate treats as more words.
     */
    public synchronized boolean isSpeech(float[] window) {
        if (window.length != VAD_WINDOW) {
            throw new IllegalArgumentException("Silero expects " + VAD_WINDOW + " samples");
        }
        return vad().compute(window) >= 0.5f;
    }

    public synchronized void release() {
        finishStream();
        loaded = false;
        if (recognizer != null) {
            recognizer.release();
            recognizer = null;
        }
        if (vad != null) {
            vad.release();
            vad = null;
        }
    }

    private Vad vad() {
        if (vad == null) {
            SileroVadModelConfig silero = new SileroVadModelConfig();
            silero.setModel(models.vadFile());
            silero.setThreshold(0.5f);
            silero.setWindowSize(VAD_WINDOW);
            silero.setMinSpeechDuration(0.05f);
            silero.setMinSilenceDuration(0.1f);

            VadModelConfig config = new VadModelConfig();
            config.setSileroVadModelConfig(silero);
            config.setSampleRate(VoiceSearchController.SAMPLE_RATE);
            config.setNumThreads(1);
            config.setProvider("cpu");
            vad = new Vad(null, config);
        }
        return vad;
    }

    private OnlineRecognizer recognizer() {
        if (recognizer != null) {
            return recognizer;
        }

        OnlineModelConfig modelConfig = new OnlineModelConfig();
        modelConfig.setTokens(models.modelFile(SpeechModelStore.TOKENS));
        modelConfig.setNumThreads(2);
        modelConfig.setDebug(false);
        modelConfig.setProvider("cpu");
        modelConfig.setModelType("zipformer2");

        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(models.modelFile(SpeechModelStore.ENCODER));
        transducer.setDecoder(models.modelFile(SpeechModelStore.DECODER));
        transducer.setJoiner(models.modelFile(SpeechModelStore.JOINER));
        modelConfig.setTransducer(transducer);

        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setModelConfig(modelConfig);
        config.setDecodingMethod("greedy_search");
        recognizer = new OnlineRecognizer(null, config);
        return recognizer;
    }
}
