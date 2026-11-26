package com.vincenthzr.locationspoofer.utils;

import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Short-lived root entry point. Only network leases cross this boundary, never the broker token. */
public final class FeedMeProxyCommand {
    private static JSONObject bridge(String operation, JSONObject body) throws Exception {
        File file = new File("/data/adb/feedmeboost-sync/token");
        if (!file.isFile() || file.length() > 128 || Files.isSymbolicLink(file.toPath()))
            throw new IllegalStateException("unavailable");
        String token = new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII).trim();
        if (!token.matches("[0-9a-f]{64}")) throw new IllegalStateException("unavailable");
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:18764/" + operation)
                .openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(operation.equals("acquire") ? 35000 : 5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream stream = connection.getOutputStream()) { stream.write(bytes); }
            if (connection.getResponseCode() != 200) throw new IllegalStateException("unavailable");
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (response.size() + count > 16384) throw new IllegalStateException("response size");
                    response.write(buffer, 0, count);
                }
                JSONObject result = new JSONObject(response.toString("UTF-8"));
                if (!result.optBoolean("ok")) throw new IllegalStateException("unavailable");
                return result;
            }
        } finally { connection.disconnect(); }
    }

    public static void main(String[] args) {
        JSONObject output = new JSONObject();
        try {
            if (args.length == 1 && args[0].equals("acquire")) {
                JSONObject result = bridge("acquire", new JSONObject());
                String lease = result.getString("lease");
                if (!UUID.fromString(lease).toString().equals(lease)) throw new IllegalStateException("lease");
                if (result.getInt("port") != 18765) {
                    bridge("release", new JSONObject().put("lease", lease));
                    throw new IllegalStateException("port");
                }
                output.put("ok", true).put("lease", lease).put("port", 18765);
            } else if (args.length == 2 && args[0].equals("release")) {
                String lease = UUID.fromString(args[1]).toString();
                if (!lease.equals(args[1])) throw new IllegalStateException("lease");
                bridge("release", new JSONObject().put("lease", lease));
                output.put("ok", true);
            } else if (args.length == 1 && args[0].equals("status")) {
                JSONObject result = bridge("status", new JSONObject());
                output.put("ok", true).put("leases", result.getInt("leases"))
                        .put("running", result.getBoolean("running"));
            } else throw new IllegalArgumentException("operation");
        } catch (Exception unavailable) {
            // No exception messages: this process can read a private authentication token.
            try { output = new JSONObject().put("ok", false); } catch (Exception ignored) { }
        }
        System.out.println(output);
    }
}
