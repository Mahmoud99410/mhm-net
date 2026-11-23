package com.mhm.net;

import android.net.Network;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Random;

/**
 * Measures the real download / upload capacity (bytes per second) and ping of a specific network,
 * using Cloudflare's public speed-test endpoints.
 */
public final class SpeedTest {
    private SpeedTest() {}

    public static final int PHASE_PING = 0, PHASE_DOWN = 1, PHASE_UP = 2;

    /** Live progress while a test runs (called from the test thread). */
    public interface Progress { void update(int phase, double bytesPerSec); }

    public static class Result {
        public boolean ok;
        public double dl;      // bytes/s
        public double ul;      // bytes/s
        public long pingMs;
        public long time;
        public String error;

        public double score() { return dl * 0.7 + ul * 0.3; }
    }

    private static final String DOWN = "https://speed.cloudflare.com/__down?bytes=";
    private static final String UP = "https://speed.cloudflare.com/__up";

    /** Full test (used when comparing the two Wi-Fi networks). */
    public static Result run(Network net) { return run(net, false, null); }

    /**
     * @param quick shorter test (about 8 seconds, max ~40 MB) used for the home screen.
     */
    public static Result run(Network net, boolean quick, Progress p) {
        long dlMs = quick ? 5000 : 8000, ulMs = quick ? 4000 : 7000;
        long dlMax = quick ? 30L * 1024 * 1024 : 60L * 1024 * 1024;
        long ulMax = quick ? 10L * 1024 * 1024 : 40L * 1024 * 1024;
        Result r = new Result();
        r.time = System.currentTimeMillis();
        try {
            if (p != null) p.update(PHASE_PING, 0);
            r.pingMs = ping(net);
            r.dl = download(net, dlMs, dlMax, p);
            r.ok = r.dl > 0;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return r;
        }
        try {
            r.ul = upload(net, ulMs, ulMax, p);
        } catch (Exception e) {
            r.ul = 0;
        }
        return r;
    }

    private static HttpURLConnection open(Network net, String u) throws IOException {
        URL url = new URL(u);
        URLConnection c = net != null ? net.openConnection(url) : url.openConnection();
        HttpURLConnection h = (HttpURLConnection) c;
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        h.setUseCaches(false);
        h.setRequestProperty("User-Agent", "MHM-NET/1.2");
        return h;
    }

    private static long ping(Network net) throws IOException {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = SystemClock.elapsedRealtime();
            HttpURLConnection h = open(net, DOWN + "0");
            h.getResponseCode();
            InputStream in = h.getInputStream();
            while (in.read() >= 0) { /* drain */ }
            in.close();
            h.disconnect();
            best = Math.min(best, SystemClock.elapsedRealtime() - t0);
        }
        return best;
    }

    private static double download(Network net, long durMs, long maxBytes, Progress p) throws IOException {
        HttpURLConnection h = open(net, DOWN + maxBytes);
        InputStream in = h.getInputStream();
        byte[] buf = new byte[64 * 1024];
        long first = -1, bytes = 0, end = 0, lastReport = 0;
        long deadline = SystemClock.elapsedRealtime() + durMs;
        try {
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                long now = SystemClock.elapsedRealtime();
                if (first < 0) first = now; else bytes += n;
                end = now;
                if (p != null && now - lastReport >= 250 && now > first) {
                    lastReport = now;
                    p.update(PHASE_DOWN, bytes * 1000.0 / (now - first));
                }
                if (now > deadline) break;
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            h.disconnect();
        }
        long ms = end - first;
        if (first < 0 || ms <= 0) return 0;
        double v = bytes * 1000.0 / ms;
        if (p != null) p.update(PHASE_DOWN, v);
        return v;
    }

    private static double upload(Network net, long durMs, long maxBytes, Progress p) throws IOException {
        HttpURLConnection h = open(net, UP);
        h.setDoOutput(true);
        h.setRequestMethod("POST");
        h.setChunkedStreamingMode(64 * 1024);
        h.setRequestProperty("Content-Type", "application/octet-stream");
        byte[] buf = new byte[64 * 1024];
        new Random().nextBytes(buf);
        long start = SystemClock.elapsedRealtime();
        long deadline = start + durMs, lastReport = 0;
        long bytes = 0;
        OutputStream out = h.getOutputStream();
        try {
            while (SystemClock.elapsedRealtime() < deadline && bytes < maxBytes) {
                out.write(buf);
                bytes += buf.length;
                long now = SystemClock.elapsedRealtime();
                if (p != null && now - lastReport >= 250 && now > start) {
                    lastReport = now;
                    p.update(PHASE_UP, bytes * 1000.0 / (now - start));
                }
            }
            out.flush();
        } finally {
            out.close();
        }
        h.getResponseCode();
        long ms = SystemClock.elapsedRealtime() - start;
        h.disconnect();
        if (ms <= 0) return 0;
        double v = bytes * 1000.0 / ms;
        if (p != null) p.update(PHASE_UP, v);
        return v;
    }
}
