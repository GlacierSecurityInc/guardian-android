package com.glaciersecurity.glaciermessenger.crypto.sasl;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import com.glaciersecurity.glaciermessenger.entities.Account;

public class ScramSha512 extends ScramMechanism {

    public static final String MECHANISM = "SCRAM-SHA-512";

    public ScramSha512(final Account account) {
        super(account, ChannelBinding.NONE);
    }

    @Override
    protected HashFunction getHMac(final byte[] key) {
        return (key == null || key.length == 0)
                ? Hashing.hmacSha512(EMPTY_KEY)
                : Hashing.hmacSha512(key);
    }

    @Override
    protected HashFunction getDigest() {
        return Hashing.sha512();
    }

    @Override
    public int getPriority() {
        return 30;
    }

    @Override
    public String getMechanism() {
        return MECHANISM;
    }
}
