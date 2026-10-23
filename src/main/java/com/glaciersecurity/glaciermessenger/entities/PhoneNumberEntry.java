package com.glaciersecurity.glaciermessenger.entities;

public class PhoneNumberEntry {
    public String phoneNumber;
    public String phoneType;
    public PhoneNumberEntry(String phoneNumber, String phoneType) {
        this.phoneNumber = phoneNumber;
        this.phoneType = phoneType;
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PhoneNumberEntry)) return false;
        PhoneNumberEntry that = (PhoneNumberEntry) o;
        if (phoneNumber == null && that.phoneNumber == null) return true;
        if (phoneNumber == null || that.phoneNumber == null) return false;
        return phoneNumber.equals(that.phoneNumber);
    }

    @Override
    public int hashCode() {
        return phoneNumber != null ? phoneNumber.hashCode() : 0;
    }
}
