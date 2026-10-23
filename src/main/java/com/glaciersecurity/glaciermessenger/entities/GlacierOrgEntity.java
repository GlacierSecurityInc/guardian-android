package com.glaciersecurity.glaciermessenger.entities;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import androidx.core.util.ObjectsCompat;

import java.util.Objects;
import java.util.regex.Pattern;

public class GlacierOrgEntity extends AbstractEntity {
    public static final String TABLENAME = "glacier_organization";
    public static final String DNS_ENABLED = "dns_enabled";
    public static final String DNS_ID = "dns_id";
    public static final String FORCE_TRACKING = "force_tracking";
    public static final String ORGANIZATION = "organization";
    public static final String SECURITYHUB_DATA_ENABLED = "securityhub_data_enabled";
    public static final String SMS_ENABLED = "sms_enabled";
    public static final String TRACKING_ENABLED = "tracking_enabled";
    public static final String TRACKING_FREQUENCY = "tracking_frequency";
    public static final String UPLOAD_ENABLED = "upload_enabled";
    public static final String VOICEMAIL_ENABLED = "voicemail_enabled";

    protected Boolean dns_enabled;
    protected String dns_id;
    protected Boolean force_tracking;
    protected String organization;
    protected Boolean security_hub_data_enabled;
    protected Boolean sms_enabled;
    protected Boolean tracking_enabled;
    protected String tracking_frequency;
    protected Boolean upload_enabled;
    protected Boolean voicemail_enabled;

    private static final Pattern DNS_ID_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");
    private static final int MAX_DNS_ID_LENGTH = 128;

    public static boolean isValidDnsId(final String dnsId) {
        return dnsId != null
                && dnsId.length() <= MAX_DNS_ID_LENGTH
                && DNS_ID_PATTERN.matcher(dnsId).matches();
    }

    public static OrganizationStep builder() {
        return new Builder();
    }

    public interface OrganizationStep {
        BuildStep organization(String organization);
    }

    public interface BuildStep {
        GlacierOrgEntity build();
        BuildStep dnsEnabled(Boolean dnsEnabled);
        BuildStep dnsId(String dnsId);
        BuildStep forceTracking(Boolean forceTracking);
        BuildStep securityHubDataEnabled(Boolean securityHubDataEnabled);
        BuildStep smsEnabled(Boolean smsEnabled);
        BuildStep trackingEnabled(Boolean trackingEnabled);
        BuildStep trackingFrequency(String trackingFrequency);
        BuildStep uploadEnabled(Boolean uploadEnabled);
        BuildStep voicemailEnabled(Boolean voicemailEnabled);
    }

    public static class Builder implements OrganizationStep, BuildStep {
        private Boolean dns_enabled;
        private String dns_id;
        private Boolean force_tracking;
        private String organization;
        private Boolean security_hub_data_enabled;
        private Boolean sms_enabled;
        private Boolean tracking_enabled;
        private String tracking_frequency;
        private Boolean upload_enabled;
        private Boolean voicemail_enabled;

        @Override
        public BuildStep organization(String organization) {
            Objects.requireNonNull(organization);
            this.organization = organization;
            return this;
        }

        @Override
        public BuildStep dnsEnabled(Boolean dnsEnabled) {
            this.dns_enabled = dnsEnabled;
            return this;
        }

        @Override
        public BuildStep dnsId(String dnsId) {
            this.dns_id = dnsId;
            return this;
        }

        @Override
        public BuildStep forceTracking(Boolean forceTracking) {
            this.force_tracking = forceTracking;
            return this;
        }

        @Override
        public BuildStep securityHubDataEnabled(Boolean securityHubDataEnabled) {
            this.security_hub_data_enabled = securityHubDataEnabled;
            return this;
        }

        @Override
        public BuildStep smsEnabled(Boolean smsEnabled) {
            this.sms_enabled = smsEnabled;
            return this;
        }

        @Override
        public BuildStep trackingEnabled(Boolean trackingEnabled) {
            this.tracking_enabled = trackingEnabled;
            return this;
        }

        @Override
        public BuildStep trackingFrequency(String trackingFrequency) {
            this.tracking_frequency = trackingFrequency;
            return this;
        }

        @Override
        public BuildStep uploadEnabled(Boolean uploadEnabled) {
            this.upload_enabled = uploadEnabled;
            return this;
        }

        @Override
        public BuildStep voicemailEnabled(Boolean voicemailEnabled) {
            this.voicemail_enabled = voicemailEnabled;
            return this;
        }

        @Override
        public GlacierOrgEntity build() {
            return new GlacierOrgEntity(
                    Boolean.TRUE.equals(dns_enabled),
                    dns_id,
                    Boolean.TRUE.equals(force_tracking),
                    organization,
                    Boolean.TRUE.equals(security_hub_data_enabled),
                    Boolean.TRUE.equals(sms_enabled),
                    Boolean.TRUE.equals(tracking_enabled),
                    tracking_frequency,
                    Boolean.TRUE.equals(upload_enabled),
                    Boolean.TRUE.equals(voicemail_enabled)
            );
        }
    }


    @Override
    public String toString() {
        return "GlacierOrgEntity{" +
                "dns_enabled=" + dns_enabled +
                ", dns_id='" + dns_id + '\'' +
                ", force_tracking=" + force_tracking +
                ", organization='" + organization + '\'' +
                ", security_hub_data_enabled=" + security_hub_data_enabled +
                ", sms_enabled=" + sms_enabled +
                ", tracking_enabled=" + tracking_enabled +
                ", tracking_frequency='" + tracking_frequency + '\'' +
                ", upload_enabled=" + upload_enabled +
                ", voicemail_enabled=" + voicemail_enabled +
                '}';
    }

    public GlacierOrgEntity(Boolean dns_enabled, String dns_id, boolean force_tracking, String organization, boolean securityhub_data_enabled, boolean sms_enabled, boolean tracking_enabled, String tracking_frequency, boolean upload_enabled, boolean voicemail_enabled) {
        this.dns_enabled = dns_enabled;
        this.dns_id = dns_id;
        this.force_tracking = force_tracking;
        this.organization = organization;
        this.security_hub_data_enabled = securityhub_data_enabled;
        this.sms_enabled = sms_enabled;
        this.tracking_enabled = tracking_enabled;
        this.tracking_frequency = tracking_frequency;
        this.upload_enabled = upload_enabled;
        this.voicemail_enabled = voicemail_enabled;
    }


    @Override
    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(DNS_ENABLED, this.dns_enabled);
        values.put(DNS_ID, this.dns_id);
        values.put(FORCE_TRACKING, this.force_tracking);
        values.put(ORGANIZATION, this.organization);
        values.put(SECURITYHUB_DATA_ENABLED, this.security_hub_data_enabled);
        values.put(SMS_ENABLED, this.sms_enabled);
        values.put(TRACKING_ENABLED, this.tracking_enabled);
        values.put(TRACKING_FREQUENCY, this.tracking_frequency);
        values.put(UPLOAD_ENABLED, this.upload_enabled);
        values.put(VOICEMAIL_ENABLED, this.voicemail_enabled);
        return values;
    }

    public static GlacierOrgEntity fromCursor(Cursor cursor) {

            String org = cursor.getString(cursor.getColumnIndexOrThrow(ORGANIZATION));

            return new GlacierOrgEntity(
                (cursor.getInt(cursor.getColumnIndexOrThrow(DNS_ENABLED)) == 1),
                cursor.getString(cursor.getColumnIndexOrThrow(DNS_ID)),
                (cursor.getInt(cursor.getColumnIndexOrThrow(FORCE_TRACKING)) == 1),
                org,
                (cursor.getInt(cursor.getColumnIndexOrThrow(SECURITYHUB_DATA_ENABLED)) == 1),
                (cursor.getInt(cursor.getColumnIndexOrThrow(SMS_ENABLED)) == 1),
                (cursor.getInt(cursor.getColumnIndexOrThrow(TRACKING_ENABLED)) == 1),
                cursor.getString(cursor.getColumnIndexOrThrow(TRACKING_FREQUENCY)),
                (cursor.getInt(cursor.getColumnIndexOrThrow(UPLOAD_ENABLED)) == 1),
                (cursor.getInt(cursor.getColumnIndexOrThrow(VOICEMAIL_ENABLED)) == 1));
    }


    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if(obj == null || getClass() != obj.getClass()) {
            return false;
        } else {
            GlacierOrgEntity glacierOrganization = (GlacierOrgEntity) obj;
            return ObjectsCompat.equals(getOrganization(), glacierOrganization.getOrganization());
        }
    }

    @Override
    public int hashCode() {
        return ObjectsCompat.hashCode(getOrganization());
    }

    public boolean isVoicemailEnabled() {
        return Boolean.TRUE.equals(voicemail_enabled);
    }

    public void setVoicemailEnabled(boolean voicemail_enabled) {
        this.voicemail_enabled = voicemail_enabled;
    }

    public boolean isUploadEnabled() {
        return Boolean.TRUE.equals(upload_enabled);
    }

    public void setUploadEnabled(boolean upload_enabled) {
        this.upload_enabled = upload_enabled;
    }

    public String getTrackingFrequency() {
        return tracking_frequency;
    }

    public void setTrackingFrequency(String tracking_frequency) {
        this.tracking_frequency = tracking_frequency;
    }

    public boolean isTrackingEnabled() {
        return Boolean.TRUE.equals(tracking_enabled);
    }

    public void setTrackingEnabled(boolean tracking_enabled) {
        this.tracking_enabled = tracking_enabled;
    }

    public boolean isSmsEnabled() {
        return Boolean.TRUE.equals(sms_enabled);
    }

    public void setSmsEnabled(boolean sms_enabled) {
        this.sms_enabled = sms_enabled;
    }

    public boolean isSecurityHubDataEnabled() {
        return Boolean.TRUE.equals(security_hub_data_enabled);
    }

    public void setSecurityHubDataEnabled(boolean security_hub_data_enabled) {
        this.security_hub_data_enabled = security_hub_data_enabled;
    }

    public String getOrganization() {
        return organization;
    }

    public void setOrganization(String organization) {
        this.organization = organization;
    }

    public boolean isForceTracking() {
        return Boolean.TRUE.equals(force_tracking);
    }

    public void setForceTracking(boolean force_tracking) {
        this.force_tracking = force_tracking;
    }

    public boolean isDnsEnabled() {
        return Boolean.TRUE.equals(dns_enabled);
    }

    public void setDnsEnabled(boolean dns_enabled) {
        this.dns_enabled = dns_enabled;
    }

    public String getDnsId(){
        return dns_id;
    }

    public void setDnsId(String dns_id){
        this.dns_id = dns_id;
    }
}
