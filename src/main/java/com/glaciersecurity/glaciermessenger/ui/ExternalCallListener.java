package com.glaciersecurity.glaciermessenger.ui;

import androidx.annotation.NonNull;

import com.twilio.voice.Call;
import com.twilio.voice.CallException;

public interface ExternalCallListener extends Call.Listener {
    @Override
    void onRinging(@NonNull Call call);
    @Override
    void onConnectFailure(@NonNull Call call, @NonNull CallException error) ;
    @Override
    void onConnected(@NonNull Call call) ;
    @Override
    void onReconnecting(@NonNull Call call, @NonNull CallException callException);
    @Override
    void onReconnected(@NonNull Call call) ;
    @Override
    void onDisconnected(@NonNull Call call, CallException error);
    void onCallStatusChange(boolean bool);
}
