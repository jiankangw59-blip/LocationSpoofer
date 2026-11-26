package com.codex.locationstartup;

import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class LocationFields {
    private static final String[] SATELLITE_KEYS = {
        "satellites", "satellites_visible", "satellites_used_in_fix", "satellites_in_view"
    };

    private LocationFields() {}

    static JSONObject snapshot(Location value) {
        JSONObject data = new JSONObject();
        putFields(value, data);
        return data;
    }

    static void putFields(Location value, JSONObject data) {
        put(data, "provider", value.getProvider());
        put(data, "time_ms", value.getTime());
        put(data, "elapsed_realtime_ns", value.getElapsedRealtimeNanos());
        put(data, "latitude", value.getLatitude());
        put(data, "longitude", value.getLongitude());
        boolean hasAccuracy = value.hasAccuracy();
        put(data, "has_accuracy", hasAccuracy);
        put(data, "accuracy_m", hasAccuracy ? value.getAccuracy() : null);
        boolean hasAltitude = value.hasAltitude();
        put(data, "has_altitude", hasAltitude);
        put(data, "altitude_m", hasAltitude ? value.getAltitude() : null);
        boolean hasSpeed = value.hasSpeed();
        put(data, "has_speed", hasSpeed);
        put(data, "speed_m_s", hasSpeed ? value.getSpeed() : null);
        boolean hasBearing = value.hasBearing();
        put(data, "has_bearing", hasBearing);
        put(data, "bearing_deg", hasBearing ? value.getBearing() : null);
        put(data, "is_mock", value.isMock());

        int api = Build.VERSION.SDK_INT;
        put(data, "location_api_level", api);
        if (api >= 26) {
            boolean hasVerticalAccuracy = value.hasVerticalAccuracy();
            put(data, "has_vertical_accuracy", hasVerticalAccuracy);
            put(data, "vertical_accuracy_m", hasVerticalAccuracy ? value.getVerticalAccuracyMeters() : null);
            boolean hasSpeedAccuracy = value.hasSpeedAccuracy();
            put(data, "has_speed_accuracy", hasSpeedAccuracy);
            put(data, "speed_accuracy_m_s", hasSpeedAccuracy ? value.getSpeedAccuracyMetersPerSecond() : null);
            boolean hasBearingAccuracy = value.hasBearingAccuracy();
            put(data, "has_bearing_accuracy", hasBearingAccuracy);
            put(data, "bearing_accuracy_deg", hasBearingAccuracy ? value.getBearingAccuracyDegrees() : null);
        }
        if (api >= 29) {
            boolean hasUncertainty = value.hasElapsedRealtimeUncertaintyNanos();
            put(data, "has_elapsed_realtime_uncertainty", hasUncertainty);
            put(data, "elapsed_realtime_uncertainty_ns", hasUncertainty ? value.getElapsedRealtimeUncertaintyNanos() : null);
        }
        if (api >= 33) put(data, "is_complete", value.isComplete());
        if (api >= 34) {
            boolean hasMslAltitude = value.hasMslAltitude();
            put(data, "has_msl_altitude", hasMslAltitude);
            put(data, "msl_altitude_m", hasMslAltitude ? value.getMslAltitudeMeters() : null);
            boolean hasMslAccuracy = value.hasMslAltitudeAccuracy();
            put(data, "has_msl_altitude_accuracy", hasMslAccuracy);
            put(data, "msl_altitude_accuracy_m", hasMslAccuracy ? value.getMslAltitudeAccuracyMeters() : null);
        }
        recordExtras(value, data);
    }

    private static void recordExtras(Location value, JSONObject data) {
        JSONArray keys = new JSONArray();
        int omitted = 0;
        int shortened = 0;
        Bundle extras = null;
        try {
            extras = value.getExtras();
            if (extras != null) for (String name : extras.keySet()) {
                if (keys.length() >= 64) { omitted++; continue; }
                if (name != null && name.length() > 256) {
                    name = name.substring(0, Character.isHighSurrogate(name.charAt(255)) ? 255 : 256);
                    shortened++;
                }
                keys.put(name);
            }
        } catch (RuntimeException error) {
            put(data, "extras_keys_error", error.getClass().getName());
            keys = null;
        }
        put(data, "extras_keys", keys);
        put(data, "extras_keys_omitted_count", omitted);
        put(data, "extras_keys_shortened_count", shortened);
        if (keys != null) put(data, "satellite_extras", satelliteExtras(extras, data));
    }

    // Only inspect the allowlisted scalar slots. Keep malformed primitive types visible without their content.
    @SuppressWarnings("deprecation")
    private static JSONObject satelliteExtras(Bundle extras, JSONObject data) {
        JSONObject result = new JSONObject();
        if (extras == null) return result;
        try {
            for (String key : SATELLITE_KEYS) {
                if (!extras.containsKey(key)) continue;
                Object value = extras.get(key);
                JSONObject entry = new JSONObject();
                if (value instanceof Integer || value instanceof Long || value instanceof Short
                    || value instanceof Byte || value instanceof Float || value instanceof Double) {
                    put(entry, "type", value.getClass().getSimpleName());
                    put(entry, "value", value);
                } else {
                    put(entry, "type", value == null ? "null" : "non_numeric");
                }
                put(result, key, entry);
            }
        } catch (RuntimeException error) {
            put(data, "satellite_extras_error", error.getClass().getName());
            return null;
        }
        return result;
    }

    static void put(JSONObject data, String key, Object value) {
        if ((value instanceof Double && !Double.isFinite((Double) value)) ||
            (value instanceof Float && !Float.isFinite((Float) value))) value = value.toString();
        try { data.put(key, value == null ? JSONObject.NULL : value); }
        catch (JSONException error) { throw new IllegalArgumentException("Cannot encode field " + key, error); }
    }
}
