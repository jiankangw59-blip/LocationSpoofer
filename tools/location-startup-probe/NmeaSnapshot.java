package com.codex.locationstartup;

import org.json.JSONObject;

/** Decode location-bearing NMEA fields for the diagnostic report. The original input is kept verbatim. */
final class NmeaSnapshot {
    private NmeaSnapshot() {}

    static JSONObject snapshot(String raw, long timestamp) {
        String framed = raw == null ? "" : raw.replaceFirst("[\\r\\n]+\\z", "");
        int star = framed.indexOf('*');
        String checksum = checksumStatus(framed, star);
        String body = framed.startsWith("$") ? framed.substring(1, star < 0 ? framed.length() : star) : "";
        String[] fields = body.split(",", -1);
        String header = fields[0];
        String type = header.matches("[A-Z]{5}") ? header.substring(2) : null;
        String fix = "unsupported";
        Double latitude = null;
        Double longitude = null;
        if (type != null) {
            int latitudeIndex = switch (type) {
                case "GGA" -> 2;
                case "RMC" -> 3;
                case "GLL" -> 1;
                default -> -1;
            };
            if (latitudeIndex >= 0) {
                latitude = coordinate(field(fields, latitudeIndex), field(fields, latitudeIndex + 1), 2, 90, "N", "S");
                longitude = coordinate(field(fields, latitudeIndex + 2), field(fields, latitudeIndex + 3), 3, 180, "E", "W");
                fix = switch (type) {
                    case "GGA" -> quality(field(fields, 6));
                    case "RMC" -> validity(field(fields, 2), field(fields, 12));
                    case "GLL" -> validity(field(fields, 6), field(fields, 7));
                    default -> throw new AssertionError(type);
                };
            }
        }

        JSONObject data = new JSONObject();
        LocationFields.put(data, "raw_sentence", raw);
        LocationFields.put(data, "sdk_timestamp_ms", timestamp);
        LocationFields.put(data, "sentence_type", type);
        LocationFields.put(data, "checksum_status", checksum);
        LocationFields.put(data, "fix_status", fix);
        LocationFields.put(data, "valid_fix", fix.equals("valid") && checksum.equals("valid")
            && latitude != null && longitude != null);
        LocationFields.put(data, "latitude", latitude);
        LocationFields.put(data, "longitude", longitude);
        return data;
    }

    private static String checksumStatus(String sentence, int star) {
        if (!(sentence.startsWith("$") || sentence.startsWith("!"))) return "malformed";
        if (star < 0) return "missing";
        if (star < 2 || sentence.length() - star != 3) return "malformed";
        int high = hex(sentence.charAt(star + 1));
        int low = hex(sentence.charAt(star + 2));
        if (high < 0 || low < 0) return "malformed";
        int actual = 0;
        for (int i = 1; i < star; i++) {
            char c = sentence.charAt(i);
            if (c < ' ' || c > '~') return "malformed";
            actual ^= c;
        }
        return actual == high * 16 + low ? "valid" : "invalid";
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    private static String field(String[] fields, int index) {
        return index < fields.length ? fields[index] : "";
    }

    private static String quality(String value) {
        if ("0".equals(value)) return "invalid";
        return value.length() == 1 && value.charAt(0) >= '1' && value.charAt(0) <= '8' ? "valid" : "unknown";
    }

    private static String validity(String status, String mode) {
        if ("V".equals(status) || "N".equals(mode)) return "invalid";
        return "A".equals(status) ? "valid" : "unknown";
    }

    private static Double coordinate(String value, String hemisphere, int degreeDigits, int maxDegrees,
                                     String positive, String negative) {
        if (!positive.equals(hemisphere) && !negative.equals(hemisphere)) return null;
        int point = degreeDigits + 2;
        if (value.length() < point) return null;
        if (value.length() > point && (value.charAt(point) != '.' || value.length() == point + 1)) return null;
        for (int i = 0; i < value.length(); i++) {
            if (i == point) continue;
            char c = value.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        int degrees = Integer.parseInt(value.substring(0, degreeDigits));
        double minutes = Double.parseDouble(value.substring(degreeDigits));
        if (minutes >= 60 || degrees > maxDegrees || (degrees == maxDegrees && minutes != 0)) return null;
        double decimal = degrees + minutes / 60.0;
        return negative.equals(hemisphere) ? -decimal : decimal;
    }
}
