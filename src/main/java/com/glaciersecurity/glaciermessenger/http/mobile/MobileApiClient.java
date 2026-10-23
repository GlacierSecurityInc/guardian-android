package com.glaciersecurity.glaciermessenger.http.mobile;

import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.entities.GlacierOrgEntity;
import com.glaciersecurity.glaciermessenger.entities.GlacierUserEntity;
import com.glaciersecurity.glaciermessenger.entities.LatestVersionEntity;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;


import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class MobileApiClient {
    private static final MobileApiService SERVICE = new Retrofit.Builder()
            .baseUrl(Endpoints.base())
            .client(OkHttpClientProvider.getOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MobileApiService.class);

    private MobileApiClient() {
    }

    public static MobileApiService service() {
        return SERVICE;
    }

    public static GlacierUserEntity toGlacierUsers(MobileApiResponses.GlacierUser user) {
        if (user == null || user.organization == null || user.username == null) {
            return null;
        }

        return GlacierUserEntity.builder()
                .organization(user.organization)
                .username(user.username)
                .addToCoreVpn(user.add_to_core_vpn != null ? user.add_to_core_vpn : Boolean.FALSE)
                .addUserToPurchaseNumbers(user.add_user_to_purchase_numbers != null ? user.add_user_to_purchase_numbers : Boolean.FALSE)
                .coreRegion(user.getCoreRegionList())
                .firstName(user.firstName)
                .xmppToken(user.xmppToken)
                .isSmsEnabled(user.isSMSEnabled != null ? user.isSMSEnabled : Boolean.FALSE)
                .lastName(user.lastName)
                .messengerId(user.messengerId)
                .securityInfo(user.getSecurityInfoList())
                .build();
    }

    public static GlacierOrgEntity toGlacierOrgEntity(MobileApiResponses.GlacierOrganization org) {
        if (org == null || org.organization == null) {
            return null;
        }
        return GlacierOrgEntity.builder()
                .organization(org.organization)
                .dnsEnabled(org.dnsEnabled != null ? org.dnsEnabled : Boolean.FALSE)
                .dnsId("")
                .forceTracking(org.forceTracking != null ? org.forceTracking : Boolean.FALSE)
                .securityHubDataEnabled(org.securityhubDataEnabled != null ? org.securityhubDataEnabled : Boolean.FALSE)
                .smsEnabled(org.smsEnabled != null ? org.smsEnabled : Boolean.FALSE)
                .trackingEnabled(org.trackingEnabled != null ? org.trackingEnabled : Boolean.FALSE)
                .trackingFrequency(org.trackingFrequency != null ? org.trackingFrequency : "")
                .uploadEnabled(org.uploadEnabled != null ? org.uploadEnabled : Boolean.FALSE)
                .voicemailEnabled(org.voicemailEnabled != null ? org.voicemailEnabled : Boolean.FALSE)
                .build();
    }
    public static LatestVersionEntity toLatestVersionEntity(MobileApiResponses.Versions versions) {
        if (versions == null) {
            return null;
        }
        return new LatestVersionEntity(
                versions.altOs,
                versions.altOsNew,
                versions.androidOs,
                versions.altOsNew,
                versions.glacierAndroid,
                "latest_versions"
        );
    }
}
