package com.glaciersecurity.glaciermessenger.ui;

import androidx.annotation.Nullable;

public interface TokenResponseListener {
    void receivedTokenResponse(boolean success, @Nullable Exception exception, String token);
}
