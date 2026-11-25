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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static de.tobiasbielefeld.searchbar.SharedData.getSavedBoolean;
import static de.tobiasbielefeld.searchbar.SharedData.putSavedBoolean;

/**
 * Downloads the one speech model and the speech detector used by dictation.
 * Neither is shipped in the APK. A partial download stays in a temporary directory until every
 * file is present.
 */
public class SpeechModelStore {

    public static final String PREF_DICTATION = "pref_dictation_enabled";

    private static final String MODEL_DIR = "sherpa-onnx-streaming-zipformer-en-2023-06-21";
    private static final String MODEL_REPO = "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21";
    static final String ENCODER = "encoder-epoch-99-avg-1.int8.onnx";
    static final String DECODER = "decoder-epoch-99-avg-1.int8.onnx";
    static final String JOINER = "joiner-epoch-99-avg-1.int8.onnx";
    static final String TOKENS = "tokens.txt";
    static final String VAD = "silero_vad.onnx";
    private static final String VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    public interface Progress {
        void onProgress(int percent);
    }

    private final Context context;

    public SpeechModelStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isEnabled() {
        return getSavedBoolean(PREF_DICTATION, false) && isReady();
    }

    public void setEnabled(boolean enabled) {
        putSavedBoolean(PREF_DICTATION, enabled);
    }

    public boolean isReady() {
        File dir = modelDir();
        return new File(dir, ENCODER).isFile()
                && new File(dir, DECODER).isFile()
                && new File(dir, JOINER).isFile()
                && new File(dir, TOKENS).isFile()
                && new File(context.getFilesDir(), VAD).isFile();
    }

    public String modelFile(String fileName) {
        return new File(modelDir(), fileName).getAbsolutePath();
    }

    public String vadFile() {
        return new File(context.getFilesDir(), VAD).getAbsolutePath();
    }

    public void download(Progress progress) throws IOException {
        File staging = new File(context.getFilesDir(), MODEL_DIR + ".partial");
        deleteRecursively(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("Could not create " + staging);
        }

        String[] names = {ENCODER, DECODER, JOINER, TOKENS, VAD};
        String[] urls = {
                fileUrl(ENCODER),
                fileUrl(DECODER),
                fileUrl(JOINER),
                fileUrl(TOKENS),
                VAD_URL
        };
        long[] sizes = new long[names.length];
        long total = 0;
        for (int i = 0; i < names.length; i++) {
            sizes[i] = contentLength(urls[i]);
            if (sizes[i] > 0) {
                total += sizes[i];
            }
        }

        long completed = 0;
        byte[] buffer = new byte[64 * 1024];
        for (int i = 0; i < names.length; i++) {
            File out = new File(staging, names[i]);
            HttpURLConnection connection = open(urls[i]);
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(out)) {
                int count;
                long fileRead = 0;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    fileRead += count;
                    report(progress, completed + fileRead, total);
                }
                if (sizes[i] > 0 && fileRead != sizes[i]) {
                    throw new IOException("Incomplete download: " + names[i]);
                }
            } finally {
                connection.disconnect();
            }
            completed += sizes[i] > 0 ? sizes[i] : out.length();
            report(progress, completed, total);
        }

        File vad = new File(staging, VAD);
        if (!vad.renameTo(new File(context.getFilesDir(), VAD))) {
            deleteRecursively(staging);
            throw new IOException("Could not install speech detection");
        }
        File destination = modelDir();
        deleteRecursively(destination);
        if (!staging.renameTo(destination)) {
            deleteRecursively(staging);
            throw new IOException("Could not install the speech model");
        }
    }

    public void delete() {
        deleteRecursively(modelDir());
        deleteRecursively(new File(context.getFilesDir(), MODEL_DIR + ".partial"));
        new File(context.getFilesDir(), VAD).delete();
    }

    private File modelDir() {
        return new File(context.getFilesDir(), MODEL_DIR);
    }

    private static String fileUrl(String fileName) {
        return "https://huggingface.co/" + MODEL_REPO + "/resolve/main/" + fileName;
    }

    private static long contentLength(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("HEAD");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.connect();
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return -1;
            }
            return connection.getContentLengthLong();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.connect();
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("Download failed: " + status);
        }
        return connection;
    }

    private static void report(Progress progress, long read, long total) {
        if (progress == null || total <= 0) {
            return;
        }
        progress.onProgress((int) Math.min(100, read * 100 / total));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
