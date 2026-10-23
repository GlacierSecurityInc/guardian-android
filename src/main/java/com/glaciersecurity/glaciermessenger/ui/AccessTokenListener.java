package com.glaciersecurity.glaciermessenger.ui;

import androidx.annotation.Nullable;

public interface AccessTokenListener {
        void receivedAccessToken(@Nullable String token, @Nullable Exception exception);
}