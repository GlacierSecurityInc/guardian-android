package com.glaciersecurity.glaciermessenger.wireguard;

import com.wireguard.android.backend.Tunnel;

public class WgTunnel implements Tunnel {
    private String wgTunnelName;
    @Override
    public String getName() {
        return wgTunnelName;
    }

    public void setName(String name){
        wgTunnelName = name;
    }

    @Override
    public void onStateChange(State newState) {
        if (newState == State.DOWN) {
            WireGuardStateMonitor.notifyTunnelDown(wgTunnelName);
        } else {
            WireGuardStateMonitor.notifyStateChanged();
        }
    }
}
