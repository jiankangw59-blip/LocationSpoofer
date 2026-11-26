package com.codex.locationstartup;

import android.location.GnssStatus;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

final class GnssStatusSnapshot {
    static final int MAX_SATELLITES_PER_FRAME = 256;

    private GnssStatusSnapshot() {}

    static JSONObject snapshot(GnssStatus status, int count) {
        JSONArray rows = new JSONArray();
        for (int index = 0; index < count; index++) {
            JSONObject satellite = new JSONObject();
            boolean hasCarrier = Build.VERSION.SDK_INT >= 26 && status.hasCarrierFrequencyHz(index);
            boolean hasBaseband = Build.VERSION.SDK_INT >= 30 && status.hasBasebandCn0DbHz(index);
            LocationFields.put(satellite, "svid", status.getSvid(index));
            LocationFields.put(satellite, "constellation_type", status.getConstellationType(index));
            LocationFields.put(satellite, "cn0_db_hz", status.getCn0DbHz(index));
            LocationFields.put(satellite, "elevation_deg", status.getElevationDegrees(index));
            LocationFields.put(satellite, "azimuth_deg", status.getAzimuthDegrees(index));
            LocationFields.put(satellite, "has_carrier_frequency_hz", hasCarrier);
            LocationFields.put(satellite, "carrier_frequency_hz", hasCarrier ? status.getCarrierFrequencyHz(index) : null);
            LocationFields.put(satellite, "has_baseband_cn0_db_hz", hasBaseband);
            LocationFields.put(satellite, "baseband_cn0_db_hz", hasBaseband ? status.getBasebandCn0DbHz(index) : null);
            LocationFields.put(satellite, "used_in_fix", status.usedInFix(index));
            LocationFields.put(satellite, "has_almanac_data", status.hasAlmanacData(index));
            LocationFields.put(satellite, "has_ephemeris_data", status.hasEphemerisData(index));
            rows.put(satellite);
        }
        JSONObject payload = new JSONObject();
        LocationFields.put(payload, "satellite_count", count);
        LocationFields.put(payload, "satellites", rows);
        return payload;
    }
}
