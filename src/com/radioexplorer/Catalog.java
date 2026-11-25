package com.radioexplorer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

final class Catalog {
    static final int PAGE_SIZE = 100;
    private static final String DISCOVERY = "https://all.api.radio-browser.info";
    private volatile String server = DISCOVERY;
    private boolean discovered;

    static final class Station {
        final String id, name, url, country, tags, codec;
        final int bitrate;
        final JSONObject json;

        Station(JSONObject o) throws JSONException {
            id = o.getString("stationuuid");
            name = o.getString("name").trim();
            String resolved = o.optString("url_resolved").trim();
            url = resolved.isEmpty() ? o.optString("url").trim() : resolved;
            country = o.optString("countrycode");
            tags = o.optString("tags");
            codec = o.optString("codec");
            bitrate = o.optInt("bitrate");
            json = o;
        }
    }

    static final class Page {
        final List<Station> stations = new ArrayList<>();
        final int rawCount;

        Page(JSONArray array) throws JSONException {
            rawCount = array.length();
            for (int i = 0; i < array.length(); i++) {
                Station s = new Station(array.getJSONObject(i));
                if (!s.id.isEmpty() && !s.name.isEmpty() && playable(s.url)) stations.add(s);
            }
        }
    }

    static boolean playable(String url) {
        try {
            URI u = URI.create(url);
            return ("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme()))
                    && u.getHost() != null && u.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private synchronized void discover() throws IOException, JSONException {
        if (discovered) return;
        JSONArray hosts = new JSONArray(get(DISCOVERY + "/json/servers"));
        for (int i = 0; i < hosts.length(); i++) {
            String name = hosts.getJSONObject(i).optString("name");
            if (name.matches("[a-z0-9-]+\\.api\\.radio-browser\\.info")) {
                server = "https://" + name;
                discovered = true;
                return;
            }
        }
        throw new IOException("Radio Browser no ha devuelto servidores disponibles.");
    }

    private String request(String path) throws IOException, JSONException {
        discover();
        try {
            return get(server + path);
        } catch (IOException first) {
            try {
                return get(DISCOVERY + path);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    Page search(String country, String name, String tag, int offset) throws IOException, JSONException {
        String path = "/json/stations/search?hidebroken=true&order=clickcount&reverse=true"
                + "&limit=" + PAGE_SIZE + "&offset=" + offset;
        if (!country.isEmpty()) path += "&countrycode=" + enc(country);
        if (!name.isEmpty()) path += "&name=" + enc(name);
        if (!tag.isEmpty()) path += "&tag=" + enc(tag);
        return new Page(new JSONArray(request(path)));
    }

    JSONArray countries() throws IOException, JSONException {
        return new JSONArray(request("/json/countrycodes?hidebroken=true"));
    }

    void click(String id) throws IOException, JSONException {
        request("/json/url/" + enc(id));
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String get(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(18000);
        c.setRequestProperty("User-Agent", "RadioExplorer/1.0");
        c.setRequestProperty("Accept", "application/json");
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("Radio Browser: HTTP " + status);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 8 * 1024 * 1024) throw new IOException("Respuesta demasiado grande.");
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
