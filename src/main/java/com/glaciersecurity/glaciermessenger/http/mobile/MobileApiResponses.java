package com.glaciersecurity.glaciermessenger.http.mobile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;


public final class MobileApiResponses {
    private MobileApiResponses() {
    }

    public static final class GlacierUserResponse {
        @SerializedName("message")
        public String message;

        @SerializedName("user")
        public GlacierUser user;
    }

    public static final class GlacierUser {
        @SerializedName("organization")
        public String organization;

        @SerializedName("username")
        public String username;
        @SerializedName("messenger_id")
        public String messengerId;
        @SerializedName("xmpp_token")
        public String xmppToken;
        @SerializedName("first_name")
        public String firstName;
        @SerializedName("last_name")
        public String lastName;

        @SerializedName("isSMSEnabled")
        public Boolean isSMSEnabled;
        @SerializedName("add_to_core_vpn")
        public Boolean add_to_core_vpn;

        @SerializedName("add_user_to_purchase_numbers")
        public Boolean add_user_to_purchase_numbers;
        @SerializedName("core_region")
        public JsonElement core_region;
        @SerializedName("securityInfo")
        public JsonElement securityInfo;

        public List<String> getCoreRegionList() {
            final List<String> regions = new ArrayList<>();
            if (core_region == null || core_region.isJsonNull()) {
                return regions;
            }

            if (core_region.isJsonArray()) {
                final JsonArray jsonArray = core_region.getAsJsonArray();
                for (JsonElement element : jsonArray) {
                    if (element != null && element.isJsonPrimitive()) {
                        regions.add(element.getAsString());
                    }
                }
            } else if (core_region.isJsonPrimitive()) {
                regions.add(core_region.getAsString());
            }
            return regions;
        }

        public List<String> getSecurityInfoList() {
            final List<String> secInfo = new ArrayList<>();
            if (securityInfo == null || securityInfo.isJsonNull()) {
                return secInfo;
            }

            // The backend stores securityInfo as an array of per-device JSON objects. Accepting
            // only primitives here skipped every element, so this list came back empty no matter
            // what the server held. Objects are kept as their raw JSON text, which is how every
            // caller re-parses them.
            if (securityInfo.isJsonArray()) {
                for (JsonElement element : securityInfo.getAsJsonArray()) {
                    final String entry = asSecurityInfoEntry(element);
                    if (entry != null) {
                        secInfo.add(entry);
                    }
                }
                return secInfo;
            }
            final String entry = asSecurityInfoEntry(securityInfo);
            if (entry != null) {
                secInfo.add(entry);
            }
            return secInfo;
        }

        private static String asSecurityInfoEntry(final JsonElement element) {
            if (element == null || element.isJsonNull()) {
                return null;
            }
            return element.isJsonPrimitive() ? element.getAsString() : element.toString();
        }

    }

    public static final class CoreRegion {
        public String region;
    }

    public static final class SecurityInfo {
        @SerializedName("glacier_version_outdated")
        public boolean glacier_version_outdated;
        @SerializedName("last_updated")
        public String last_updated;
        @SerializedName("os_version")
        public String os_version;
        @SerializedName("biometric_lock")
        public boolean biometric_lock;

        @SerializedName("deviceid")
        public String deviceid;
        @SerializedName("os_patch")
        public String os_patch;
        @SerializedName("core_enabled")
        public boolean core_enabled;
        @SerializedName("app_lock")
        public boolean app_lock;
        @SerializedName("screen_lock")
        public boolean screen_lock;
        @SerializedName("compromised")
        public boolean compromised;
        @SerializedName("compromised_detail")
        public String compromised_detail;
        @SerializedName("os_version_outdated")
        public boolean os_version_outdated;

        @SerializedName("device")
        public String device;
        @SerializedName("glacier_version")
        public String glacier_version;
    }

    public static final class GlacierOrganizationResponse {
        @SerializedName("message")
        public String message;

        @SerializedName("organization")
        public GlacierOrganization organization;
    }

    public static final class DnsProfileResponse {
        @SerializedName("message")
        public String message;

        @SerializedName("data")
        public String data;
    }

    public static final class GlacierOrganization {
        @SerializedName("organization")
        public String organization;
        @SerializedName("securityhub_data_enabled")
        public Boolean securityhubDataEnabled;
        @SerializedName("sms_enabled")
        public Boolean smsEnabled;
        @SerializedName("upload_enabled")
        public Boolean uploadEnabled;
        @SerializedName("voicemail_enabled")
        public Boolean voicemailEnabled;
        @SerializedName("tracking_enabled")
        public Boolean trackingEnabled;
        @SerializedName("force_tracking")
        public Boolean forceTracking;
        @SerializedName("tracking_frequency")
        public String trackingFrequency;
        @SerializedName("dns_enabled")
        public Boolean dnsEnabled;
    }

    public static final class LatestVersionsResponse {
        @SerializedName("message")
        public String message;
        @SerializedName("versions")
        public Versions versions;
    }

    public static final class Versions {
        @SerializedName("alt_os")
        public String altOs;
        @SerializedName("alt_os_new")
        public String altOsNew;
        @SerializedName("android_os")
        public String androidOs;

        @SerializedName("glacier")
        public String glacier;
        @SerializedName("glacier_android")
        public String glacierAndroid;

    }

    public static final class SecurityInfoUpdateRequest {
        // Annotated like every other DTO here so the JSON keys survive member obfuscation even
        // if the keep rule in proguard-rules.pro is ever narrowed. Without both, R8 renamed or
        // removed these fields and Gson serialised an empty object.
        @SerializedName("organization")
        public String organization;
        @SerializedName("username")
        public String username;
        @SerializedName("securityInfo")
        public JsonElement securityInfo;

        public SecurityInfoUpdateRequest(String organization, String username, JsonElement securityInfo) {
            this.organization = organization;
            this.username = username;
            this.securityInfo = securityInfo;
        }
    }
}
