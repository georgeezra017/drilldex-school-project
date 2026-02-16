package com.drilldex.drillbackend.preview;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class PreviewGenerator {

    // Homebrew default; change if yours differs.
    @Value("${app.media.ffmpeg:/opt/homebrew/bin/ffmpeg}")
    private String ffmpegPath;

    // Interval (seconds) between watermark “bursts”
    @Value("${app.media.watermark.intervalSec:5}")
    private int intervalSec;

    // Watermark gain (linear multiplier; e.g. 2.0 ~ +6dB)
    @Value("${app.media.watermark.gain:2.0}")
    private double wmGain;

    // Voice tag file in resources
    @Value("${app.media.watermark.resource:watermarks/drilldex_watermark.mp3}")
    private String watermarkResourcePath;

    // We’ll use 44.1kHz to compute exact samples for aloop
    @Value("${app.media.sampleRate:44100}")
    private int sampleRate;

    private Path watermarkTemp; // extracted on first use

// keep everything else the same, only replace these two methods

    // keep everything else the same, only replace these two methods

    private synchronized Path ensureWatermarkOnDisk() throws IOException {
        if (watermarkTemp != null && Files.exists(watermarkTemp)) return watermarkTemp;

        ClassPathResource res = new ClassPathResource(watermarkResourcePath);
        if (!res.exists()) throw new FileNotFoundException("Watermark resource not found: " + watermarkResourcePath);

        // Keep the original extension of the resource (mp3, wav, etc.)
        String name = watermarkResourcePath;
        int dot = name.lastIndexOf('.');
        String ext = (dot >= 0 ? name.substring(dot) : ".tmp"); // e.g. ".mp3" or ".wav"
        watermarkTemp = Files.createTempFile("wm-", ext);

        try (InputStream in = res.getInputStream(); OutputStream out = Files.newOutputStream(watermarkTemp)) {
            in.transferTo(out);
        }
        watermarkTemp.toFile().deleteOnExit();
        return watermarkTemp;
    }

    /** Generate a fully watermarked preview to outFile.
     *  Input can be mp3/wav/etc. Output codec is chosen by outFile extension.
     */
    public void generatePreview(Path masterFile, Path outFile) throws IOException, InterruptedException {
        Path wm = ensureWatermarkOnDisk();

        int samplesPerInterval = intervalSec * sampleRate;

        String filterGraph = String.join("",
                "[1:a]aresample=", String.valueOf(sampleRate),
                ",highpass=f=150,lowpass=f=6500,volume=", String.valueOf(wmGain),
                ",atrim=0:", String.valueOf(intervalSec),
                ",apad=pad_dur=", String.valueOf(intervalSec),
                ",asetpts=N/SR/TB,aloop=loop=-1:size=", String.valueOf(samplesPerInterval),
                ":start=0[wm];",
                "[0:a]aresample=", String.valueOf(sampleRate), "[main];",
                "[main][wm]amix=inputs=2:duration=first:dropout_transition=0"
        );

        // Choose encoder by output extension
        String out = outFile.toAbsolutePath().toString().toLowerCase();
        List<String> codecArgs = new ArrayList<>();
        if (out.endsWith(".mp3")) {
            codecArgs.add("-c:a"); codecArgs.add("libmp3lame");
            codecArgs.add("-b:a"); codecArgs.add("192k");
            // optional: codecArgs.add("-ar"); codecArgs.add(String.valueOf(sampleRate));
            // optional: codecArgs.add("-ac"); codecArgs.add("2");
        } else if (out.endsWith(".wav")) {
            // 16-bit PCM WAV
            codecArgs.add("-c:a"); codecArgs.add("pcm_s16le");
            // optional: codecArgs.add("-ar"); codecArgs.add(String.valueOf(sampleRate));
            // optional: codecArgs.add("-ac"); codecArgs.add("2");
        } else if (out.endsWith(".m4a") || out.endsWith(".mp4") || out.endsWith(".aac")) {
            codecArgs.add("-c:a"); codecArgs.add("aac");
            codecArgs.add("-b:a"); codecArgs.add("192k");
            codecArgs.add("-movflags"); codecArgs.add("+faststart");
        } else {
            // default to mp3 if unknown (keeps old behavior)
            codecArgs.add("-c:a"); codecArgs.add("libmp3lame");
            codecArgs.add("-b:a"); codecArgs.add("192k");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-i"); cmd.add(masterFile.toAbsolutePath().toString());
        cmd.add("-i"); cmd.add(wm.toAbsolutePath().toString());
        cmd.add("-filter_complex"); cmd.add(filterGraph);
        cmd.addAll(codecArgs);
        cmd.add("-shortest");
        cmd.add(outFile.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line; while ((line = br.readLine()) != null) System.out.println("[ffmpeg] " + line);
        }

        int code = p.waitFor();
        if (code != 0) throw new IOException("ffmpeg failed with exit code " + code);
    }
}