package com.glaciersecurity.glaciermessenger.http.mobile;

import com.glaciersecurity.glaciermessenger.EndPointConfig;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.PUT;
import retrofit2.http.Query;

public interface MobileApiService {
    @GET("/api/mobile/glacier-users?")
    Call<MobileApiResponses.GlacierUserResponse> getGlacierUser(
            @Query("organization") String organization,
            @Query("username") String username);

    @GET("/api/mobile/glacier-organization")
    Call<MobileApiResponses.GlacierOrganizationResponse> getGlacierOrganization(
            @Query("organization") String organization,
            @Query("username") String username);

    @GET("/api/mobile/latest-version")
    Call<MobileApiResponses.LatestVersionsResponse> getLatestVersion(
            @Query("key") String key);

    @GET(EndPointConfig.GET_DNS_PROFILE_URL)
    Call<MobileApiResponses.DnsProfileResponse> getDnsProfile(
            @Query("organization") String organization);

    @PUT("/api/mobile/glacier-users/security-info")
    Call<Void> updateSecurityInfo(
            @Body MobileApiResponses.SecurityInfoUpdateRequest request);

    @GET("/api/mobile/core-profile")
    Call<ResponseBody> getCoreProfile(
            @Query("organization") String organization,
            @Query("core_region") String coreRegion,
            @Query("username") String username);
}
