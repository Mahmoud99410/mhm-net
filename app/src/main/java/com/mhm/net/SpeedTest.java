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
 * Measures download / upload speed (bytes per second) and ping over a specific network,
 * using Cloudflare's public speed-test endpoints.
 */
public final class SpeedTest {
    private SpeedTest() {}

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
    private static final long DL_MS = 8000, UL_MS = 7000;

    public static Result run(Network net) {
        Result r = new Result();
        r.time = System.currentTimeMillis();
        try {
            r.pingMs = ping(net);
            r.dl = download(net);
            r.ok = r.dl > 0;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return r;
        }
        try {
            r.ul = upload(net);
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
        h.setRequestProperty("User-Agent", "MHM-NET/1.0");
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

    private static double download(Network net) throws IOException {
        HttpURLConnection h = open(net, DOWN + "60000000");
        InputStream in = h.getInputStream();
        byte[] buf = new byte[64 * 1024];
        long first = -1, bytes = 0, end = 0;
        long deadline = SystemClock.elapsedRealtime() + DL_MS;
        try {
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                long now = SystemClock.elapsedRealtime();
                if (first < 0) first = now; else bytes += n;
                end = now;
                if (now > deadline) break;
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            h.disconnect();
        }
        long ms = end - first;
        if (first < 0 || ms <= 0) return 0;
        return bytes * 1000.0 / ms;
    }

    private static double upload(Network net) throws IOException {
        HttpURLConnection h = open(net, UP);
        h.setDoOutput(true);
        h.setRequestMethod("POST");
        h.setChunkedStreamingMode(64 * 1024);
        h.setRequestProperty("Content-Type", "application/octet-stream");
        byte[] buf = new byte[64 * 1024];
        new Random().nextBytes(buf);
        long start = SystemClock.elapsedRealtime();
        long deadline = start + UL_MS;
        long bytes = 0;
        OutputStream out = h.getOutputStream();
        try {
            while (SystemClock.elapsedRealtime() < deadline && bytes < 40L * 1024 * 1024) {
                out.write(buf);
                bytes += buf.length;
            }
            out.flush();
        } finally {
            out.close();
        }
        h.getResponseCode();
        long ms = SystemClock.elapsedRealtime() - start;
        h.disconnect();
        if (ms <= 0) return 0;
        return bytes * 1000.0 / ms;
    }
}
