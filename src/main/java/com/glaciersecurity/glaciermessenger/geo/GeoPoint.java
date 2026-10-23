package com.glaciersecurity.glaciermessenger.geo;

import android.location.Location;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class GeoPoint implements Parcelable {
    private final double latitude;
    private final double longitude;

    public GeoPoint(final double latitude, final double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public GeoPoint(@NonNull final Location location) {
        this(location.getLatitude(), location.getLongitude());
    }

    protected GeoPoint(final Parcel in) {
        this.latitude = in.readDouble();
        this.longitude = in.readDouble();
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeDouble(latitude);
        dest.writeDouble(longitude);
    }

    public static final Creator<GeoPoint> CREATOR = new Creator<GeoPoint>() {
        @Override
        public GeoPoint createFromParcel(final Parcel in) {
            return new GeoPoint(in);
        }

        @Override
        public GeoPoint[] newArray(final int size) {
            return new GeoPoint[size];
        }
    };

    @NonNull
    @Override
    public String toString() {
        return "GeoPoint{" +
                "latitude=" + latitude +
                ", longitude=" + longitude +
                '}';
    }
}
