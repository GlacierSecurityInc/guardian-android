package com.glaciersecurity.glaciermessenger.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class CoreProfileTest {

    @Test
    public void renamingDisconnectedProfileUpdatesDisplayNameOnly() {
        CoreProfile profile = new CoreProfile(
                "us-east_user.conf",
                "us-east_user",
                null,
                0,
                "us-east_user.conf");

        profile.setDisplayName("Personal Core");

        assertEquals("Personal Core", profile.getDisplayName());
        assertEquals("us-east_user.conf", profile.getProfName());
        assertEquals("us-east_user.conf", profile.getLegacyName());
        assertFalse(profile.isRunning());
    }
}
