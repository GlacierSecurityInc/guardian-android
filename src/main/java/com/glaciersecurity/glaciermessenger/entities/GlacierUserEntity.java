package com.glaciersecurity.glaciermessenger.entities;


import androidx.core.util.ObjectsCompat;

import java.util.List;
import java.util.Objects;

public class GlacierUserEntity {
    private boolean add_to_core_vpn;
    private boolean add_user_to_purchase_numbers;
    private List<String> core_region;
    private   String first_name;
    private   String xmpp_token;
    private  Boolean isSMSEnabled;
    private   String last_name;
    private   String messenger_id;
    private  String organization;
    private  List<String> securityInfo;
    private  String username;

    public Boolean getAddToCoreVpn() {
        return add_to_core_vpn;
    }

    public Boolean getAddUserToPurchaseNumbers() {
        return add_user_to_purchase_numbers;
    }

    public List<String> getCoreRegion() {
        return core_region;
    }

    public String getFirstName() {
        return first_name;
    }

    public String getXmppToken() {
        return xmpp_token;
    }

    public Boolean getIsSmsEnabled() {
        return isSMSEnabled;
    }

    public String getLastName() {
        return last_name;
    }

    public String getMessengerId() {
        return messenger_id;
    }

    public String getOrganization() {
        return organization;
    }

    public List<String> getSecurityInfo() {
        return securityInfo;
    }

    public String getUsername() {
        return username;
    }

    public GlacierUserEntity(boolean add_to_core_vpn, boolean add_user_to_purchase_numbers, List<String> core_region, String first_name, String xmpp_token, Boolean isSMSEnabled, String last_name, String messenger_id, String organization, List<String> securityInfo, String username) {
        this.add_to_core_vpn = add_to_core_vpn;
        this.add_user_to_purchase_numbers = add_user_to_purchase_numbers;
        this.core_region = core_region;
        this.first_name = first_name;
        this.xmpp_token = xmpp_token;
        this.isSMSEnabled = isSMSEnabled;
        this.last_name = last_name;
        this.messenger_id = messenger_id;
        this.organization = organization;
        this.securityInfo = securityInfo;
        this.username = username;
    }


    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if(obj == null || getClass() != obj.getClass()) {
            return false;
        } else {
            GlacierUserEntity glacierUsers = (GlacierUserEntity) obj;
            return ObjectsCompat.equals(getAddToCoreVpn(), glacierUsers.getAddToCoreVpn()) &&
                    ObjectsCompat.equals(getAddUserToPurchaseNumbers(), glacierUsers.getAddUserToPurchaseNumbers()) &&
                    ObjectsCompat.equals(getCoreRegion(), glacierUsers.getCoreRegion()) &&
                  ObjectsCompat.equals(getFirstName(), glacierUsers.getFirstName()) &&
                    ObjectsCompat.equals(getXmppToken(), glacierUsers.getXmppToken()) &&
                    ObjectsCompat.equals(getIsSmsEnabled(), glacierUsers.getIsSmsEnabled()) &&
                    ObjectsCompat.equals(getLastName(), glacierUsers.getLastName()) &&
                    ObjectsCompat.equals(getMessengerId(), glacierUsers.getMessengerId()) &&
                    ObjectsCompat.equals(getOrganization(), glacierUsers.getOrganization()) &&
                    ObjectsCompat.equals(getSecurityInfo(), glacierUsers.getSecurityInfo()) &&
                    ObjectsCompat.equals(getUsername(), glacierUsers.getUsername());
        }
    }

    @Override
    public int hashCode() {
        return new StringBuilder()
                .append(getAddToCoreVpn())
                .append(getAddUserToPurchaseNumbers())
                .append(getCoreRegion())
                .append(getFirstName())
                .append(getXmppToken())
                .append(getIsSmsEnabled())
                .append(getLastName())
                .append(getMessengerId())
                .append(getOrganization())
                .append(getSecurityInfo())
                .append(getUsername())
                .toString()
                .hashCode();
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("GlacierUsers {")
                .append("add_to_core_vpn=" + String.valueOf(getAddToCoreVpn()) + ", ")
                .append("add_user_to_purchase_numbers=" + String.valueOf(getAddUserToPurchaseNumbers()) + ", ")
                .append("core_region=" + String.valueOf(getCoreRegion()) + ", ")
                .append("first_name=" + String.valueOf(getFirstName()) + ", ")
                .append("isSMSEnabled=" + String.valueOf(getIsSmsEnabled()) + ", ")
                .append("last_name=" + String.valueOf(getLastName()) + ", ")
                .append("messenger_id=" + String.valueOf(getMessengerId()) + ", ")
                .append("organization=" + String.valueOf(getOrganization()) + ", ")
                .append("securityInfo=" + String.valueOf(getSecurityInfo()) + ", ")
                .append("username=" + String.valueOf(getUsername()))
                .append("}")
                .toString();
    }

    public static OrganizationStep builder() {
        return new Builder();
    }

    public CopyOfBuilder copyOfBuilder() {
        return new CopyOfBuilder(this);
    }
    public interface OrganizationStep {
        UsernameStep organization(String organization);
    }


    public interface UsernameStep {
        BuildStep username(String username);
    }


    public interface BuildStep {
        GlacierUserEntity build();
        BuildStep addToCoreVpn(Boolean addToCoreVpn);
        BuildStep addUserToPurchaseNumbers(Boolean addUserToPurchaseNumbers);
        BuildStep coreRegion(List<String> coreRegion);
        BuildStep firstName(String firstName);
        BuildStep xmppToken(String xmppToken);
        BuildStep isSmsEnabled(Boolean isSmsEnabled);
        BuildStep lastName(String lastName);
        BuildStep messengerId(String messengerId);
        BuildStep securityInfo(List<String> securityInfo);
    }


    public static class Builder implements OrganizationStep, UsernameStep, BuildStep {
        private String organization;
        private String username;
        private Boolean add_to_core_vpn;
        private Boolean add_user_to_purchase_numbers;
        private List<String> core_region;
        private String first_name;
        private String xmpp_token;
        private Boolean isSMSEnabled;
        private String last_name;
        private String messenger_id;
        private List<String> securityInfo;
        public Builder() {

        }

        private Builder(Boolean add_to_core_vpn, Boolean add_user_to_purchase_numbers, List<String> core_region, String first_name, String xmpp_token, Boolean isSMSEnabled, String last_name, String messenger_id, String organization, List<String> securityInfo, String username) {
            this.add_to_core_vpn = add_to_core_vpn;
            this.add_user_to_purchase_numbers = add_user_to_purchase_numbers;
            this.core_region = core_region;
            this.first_name = first_name;
            this.xmpp_token = xmpp_token;
            this.isSMSEnabled = isSMSEnabled;
            this.last_name = last_name;
            this.messenger_id = messenger_id;
            this.organization = organization;
            this.securityInfo = securityInfo;
            this.username = username;
        }

        @Override
        public GlacierUserEntity build() {

            return new GlacierUserEntity(
                    add_to_core_vpn,
                    add_user_to_purchase_numbers,
                    core_region,
                    first_name,
                    xmpp_token,
                    isSMSEnabled,
                    last_name,
                    messenger_id,
                    organization,
                    securityInfo,
                    username);
        }

        @Override
        public UsernameStep organization(String organization) {
            Objects.requireNonNull(organization);
            this.organization = organization;
            return this;
        }

        @Override
        public BuildStep username(String username) {
            Objects.requireNonNull(username);
            this.username = username;
            return this;
        }

        @Override
        public BuildStep addToCoreVpn(Boolean addToCoreVpn) {
            this.add_to_core_vpn = addToCoreVpn;
            return this;
        }

        @Override
        public BuildStep addUserToPurchaseNumbers(Boolean addUserToPurchaseNumbers) {
            this.add_user_to_purchase_numbers = addUserToPurchaseNumbers;
            return this;
        }

        @Override
        public BuildStep coreRegion(List<String> coreRegion) {
            this.core_region = coreRegion;
            return this;
        }

        @Override
        public BuildStep firstName(String firstName) {
            this.first_name = firstName;
            return this;
        }

        @Override
        public BuildStep xmppToken(String xmppToken) {
            this.xmpp_token = xmppToken;
            return this;
        }

        @Override
        public BuildStep isSmsEnabled(Boolean isSmsEnabled) {
            this.isSMSEnabled = isSmsEnabled;
            return this;
        }

        @Override
        public BuildStep lastName(String lastName) {
            this.last_name = lastName;
            return this;
        }

        @Override
        public BuildStep messengerId(String messengerId) {
            this.messenger_id = messengerId;
            return this;
        }

        @Override
        public BuildStep securityInfo(List<String> securityInfo) {
            this.securityInfo = securityInfo;
            return this;
        }


    }


    // Static so a builder does not pin the entity it was copied from.
    public static final class CopyOfBuilder extends Builder {
        private CopyOfBuilder(final GlacierUserEntity source) {
            super(source.add_to_core_vpn, source.add_user_to_purchase_numbers, source.core_region, source.first_name, source.xmpp_token, source.isSMSEnabled, source.last_name, source.messenger_id, source.organization, source.securityInfo, source.username);
            Objects.requireNonNull(source.organization);
            Objects.requireNonNull(source.username);
        }

        @Override
        public CopyOfBuilder organization(String organization) {
            return (CopyOfBuilder) super.organization(organization);
        }

        @Override
        public CopyOfBuilder username(String username) {
            return (CopyOfBuilder) super.username(username);
        }

        @Override
        public CopyOfBuilder addToCoreVpn(Boolean addToCoreVpn) {
            return (CopyOfBuilder) super.addToCoreVpn(addToCoreVpn);
        }

        @Override
        public CopyOfBuilder addUserToPurchaseNumbers(Boolean addUserToPurchaseNumbers) {
            return (CopyOfBuilder) super.addUserToPurchaseNumbers(addUserToPurchaseNumbers);
        }

        @Override
        public CopyOfBuilder coreRegion(List<String> coreRegion) {
            return (CopyOfBuilder) super.coreRegion(coreRegion);
        }

        @Override
        public CopyOfBuilder firstName(String firstName) {
            return (CopyOfBuilder) super.firstName(firstName);
        }

        @Override
        public CopyOfBuilder xmppToken(String xmppToken) {
            return (CopyOfBuilder) super.xmppToken(xmppToken);
        }

        @Override
        public CopyOfBuilder isSmsEnabled(Boolean isSmsEnabled) {
            return (CopyOfBuilder) super.isSmsEnabled(isSmsEnabled);
        }

        @Override
        public CopyOfBuilder lastName(String lastName) {
            return (CopyOfBuilder) super.lastName(lastName);
        }

        @Override
        public CopyOfBuilder messengerId(String messengerId) {
            return (CopyOfBuilder) super.messengerId(messengerId);
        }

        @Override
        public CopyOfBuilder securityInfo(List<String> securityInfo) {
            return (CopyOfBuilder) super.securityInfo(securityInfo);
        }


    }


}
