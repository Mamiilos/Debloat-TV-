package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Weather from Yandex Weather (GraphQL API v3, with the person's own key: yandex_weather_key) and from MET Norway
 * (api.met.no, free, no key), turned into HomeData.Weather with Open-Meteo's WMO codes, which the rest of the app reads.
 * HomeData tries Yandex when there is a key, then MET Norway, then Open-Meteo: some networks can't reach Open-Meteo.
 */
final class WeatherSources {
    private WeatherSources() {}

    static final String YANDEX_KEY = Config.YANDEX_WEATHER_KEY.trim();

    // MARK: - Yandex Weather

    /** Now, today's high and low, sunrise and sunset, and the hours of today and tomorrow. */
    static String yandex(String lat, String lon) throws IOException {
        String query = "{ weatherByPoint(request: { lat: " + lat + ", lon: " + lon + " }) {"
                + " now { temperature condition daytime }"
                + " forecast { days(limit: 2) { sunriseTimestamp sunsetTimestamp"
                + " summary { day { minTemperature maxTemperature } }"
                + " hours { timestamp temperature condition daytime } } } } }";
        String body;
        try {
            body = new JSONObject().put("query", query).toString();
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.weather.yandex.ru/graphql/query").openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("X-Yandex-Weather-Key", YANDEX_KEY);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            // 403: a wrong key, or the plan's limit for today (it opens again tomorrow).
            if (c.getResponseCode() != 200) throw new IOException("Yandex HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    static HomeData.Weather parseYandex(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray errors = root.optJSONArray("errors");
        if (errors != null && errors.length() > 0) throw new IOException("Yandex: " + errors.getJSONObject(0).optString("message"));
        JSONObject point = root.getJSONObject("data").getJSONObject("weatherByPoint");
        HomeData.Weather w = new HomeData.Weather();
        JSONObject now = point.getJSONObject("now");
        w.temp = now.getDouble("temperature");
        w.code = wmo(now.optString("condition"));
        w.isDay = !"NIGHT".equals(now.optString("daytime"));
        JSONArray days = point.getJSONObject("forecast").getJSONArray("days");
        JSONObject today = days.getJSONObject(0);
        w.sunrise = today.optLong("sunriseTimestamp") * 1000;
        w.sunset = today.optLong("sunsetTimestamp") * 1000;
        JSONObject summary = today.optJSONObject("summary");
        JSONObject day = summary == null ? null : summary.optJSONObject("day");
        if (day != null) {
            w.max = day.optDouble("maxTemperature", Double.NaN);
            w.min = day.optDouble("minTemperature", Double.NaN);
        }
        long nowMs = System.currentTimeMillis();
        for (int d = 0; d < days.length() && w.hours.size() < 6; d++) {
            JSONArray hours = days.getJSONObject(d).optJSONArray("hours");
            if (hours == null) continue;
            for (int i = 0; i < hours.length() && w.hours.size() < 6; i++) {
                JSONObject h = hours.getJSONObject(i);
                long at = h.optLong("timestamp") * 1000;
                if (at <= nowMs) continue;
                w.hours.add(at);
                w.hourTemp.add(h.getDouble("temperature"));
                w.hourCode.add(wmo(h.optString("condition")));
                w.hourDay.add(!"NIGHT".equals(h.optString("daytime")));
            }
        }
        return w;
    }

    /** Yandex's Condition as the nearest WMO code (HomeData.condition and Symbols.weather read those). */
    private static int wmo(String condition) {
        switch (condition) {
            case "CLEAR": return 0;
            case "PARTLY_CLOUDY": return 2;
            case "CLOUDY":
            case "OVERCAST": return 3;
            case "LIGHT_RAIN": return 61;
            case "RAIN": return 63;
            case "HEAVY_RAIN": return 65;
            case "SHOWERS": return 81;
            case "SLEET": return 66;
            case "LIGHT_SNOW": return 71;
            case "SNOW": return 73;
            case "SNOWFALL": return 75;
            case "HAIL":
            case "THUNDERSTORM_WITH_HAIL": return 96;
            case "THUNDERSTORM":
            case "THUNDERSTORM_WITH_RAIN": return 95;
            default: return -1;
        }
    }

    // MARK: - MET Norway

    static String met(String lat, String lon) throws IOException {
        // MET asks for at most 4 decimals and a User-Agent that says who is calling.
        URL url = new URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=" + round4(lat) + "&lon=" + round4(lon));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.1 github.com/simbasjungles/free-my-tv");
        try {
            if (c.getResponseCode() != 200) throw new IOException("MET HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    static HomeData.Weather parseMet(String body, double lat, double lon) throws Exception {
        JSONArray series = new JSONObject(body).getJSONObject("properties").getJSONArray("timeseries");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        HomeData.Weather w = new HomeData.Weather();
        long now = System.currentTimeMillis();
        Calendar local = Calendar.getInstance();
        int today = local.get(Calendar.DAY_OF_YEAR);
        double max = -Double.MAX_VALUE, min = Double.MAX_VALUE;
        boolean current = false;
        for (int i = 0; i < series.length(); i++) {
            JSONObject item = series.getJSONObject(i);
            long at = iso.parse(item.getString("time")).getTime();
            JSONObject data = item.getJSONObject("data");
            double t = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
            String symbol = symbol(data);
            local.setTimeInMillis(at);
            if (local.get(Calendar.DAY_OF_YEAR) == today) { max = Math.max(max, t); min = Math.min(min, t); }
            // Now: the last step that has started (or the first one, if none has yet).
            if (at <= now || !current) {
                w.temp = t;
                w.code = metCode(symbol);
                w.isDay = !symbol.endsWith("_night");
                current = true;
            }
            if (at > now && w.hours.size() < 6) {
                w.hours.add(at);
                w.hourTemp.add(t);
                w.hourCode.add(metCode(symbol));
                w.hourDay.add(!symbol.endsWith("_night"));
            }
        }
        if (max > -Double.MAX_VALUE) { w.max = max; w.min = min; }
        long[] sun = sun(lat, lon, now);
        w.sunrise = sun[0];
        w.sunset = sun[1];
        return w;
    }

    private static String symbol(JSONObject data) {
        for (String next : new String[] {"next_1_hours", "next_6_hours", "next_12_hours"}) {
            JSONObject o = data.optJSONObject(next);
            JSONObject summary = o == null ? null : o.optJSONObject("summary");
            if (summary != null) return summary.optString("symbol_code", "");
        }
        return "";
    }

    /** MET's symbol codes (clearsky_day, lightrainshowers_night…) as the nearest WMO code. */
    private static int metCode(String symbol) {
        String s = symbol.replaceAll("_(day|night|polartwilight)$", "");
        if (s.contains("thunder")) return 95;
        if (s.contains("sleet")) return 66;
        if (s.contains("snow")) return s.startsWith("heavy") ? 75 : s.startsWith("light") ? 71 : 73;
        if (s.contains("rain")) {
            boolean showers = s.contains("showers");
            if (s.startsWith("heavy")) return showers ? 82 : 65;
            if (s.startsWith("light")) return showers ? 80 : 61;
            return showers ? 81 : 63;
        }
        switch (s) {
            case "clearsky": return 0;
            case "fair": return 1;
            case "partlycloudy": return 2;
            case "cloudy": return 3;
            case "fog": return 45;
            default: return -1;
        }
    }

    /** Today's sunrise and sunset (epoch ms) from NOAA's solar equations; 6:00 and 18:00 local in polar day or night. */
    static long[] sun(double lat, double lon, long now) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(now);
        utc.set(Calendar.HOUR_OF_DAY, 0);
        utc.set(Calendar.MINUTE, 0);
        utc.set(Calendar.SECOND, 0);
        utc.set(Calendar.MILLISECOND, 0);
        double g = 2 * Math.PI / 365 * (utc.get(Calendar.DAY_OF_YEAR) - 1);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double phi = Math.toRadians(lat);
        double cos = Math.cos(Math.toRadians(90.833)) / (Math.cos(phi) * Math.cos(decl)) - Math.tan(phi) * Math.tan(decl);
        if (cos < -1 || cos > 1) {
            Calendar day = Calendar.getInstance();
            day.setTimeInMillis(now);
            day.set(Calendar.HOUR_OF_DAY, 6);
            day.set(Calendar.MINUTE, 0);
            long six = day.getTimeInMillis();
            return new long[] {six, six + 12 * 3_600_000L};
        }
        double ha = Math.toDegrees(Math.acos(cos));
        long midnight = utc.getTimeInMillis();
        return new long[] {midnight + Math.round((720 - 4 * (lon + ha) - eq) * 60_000),
                midnight + Math.round((720 - 4 * (lon - ha) - eq) * 60_000)};
    }

    private static String round4(String v) {
        try {
            return String.format(Locale.US, "%.4f", Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
