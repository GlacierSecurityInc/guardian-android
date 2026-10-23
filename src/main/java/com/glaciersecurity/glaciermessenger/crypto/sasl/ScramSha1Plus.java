package com.glaciersecurity.glaciermessenger.crypto.sasl;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import com.glaciersecurity.glaciermessenger.entities.Account;

public class ScramSha1Plus extends ScramPlusMechanism {

    public static final String MECHANISM = "SCRAM-SHA-1-PLUS";

    public ScramSha1Plus(final Account account, final ChannelBinding channelBinding) {
        super(account, channelBinding);
    }

    @Override
    protected HashFunction getHMac(final byte[] key) {
        return (key == null || key.length == 0)
                ? Hashing.hmacSha1(EMPTY_KEY)
                : Hashing.hmacSha1(key);
    }

    @Override
    protected HashFunction getDigest() {
        return Hashing.sha1();
    }

    @Override
    public int getPriority() {
        return 35 + ChannelBinding.priority(this.channelBinding); // higher than SCRAM-SHA512 (30)
    }

    @Override
    public String getMechanism() {
        return MECHANISM;
    }
}
