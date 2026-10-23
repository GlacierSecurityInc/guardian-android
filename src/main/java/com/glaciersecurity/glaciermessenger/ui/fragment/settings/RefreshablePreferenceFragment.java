package com.glaciersecurity.glaciermessenger.ui.fragment.settings;

public interface RefreshablePreferenceFragment {
    void onUserRefresh();
    public interface RefreshCompleteListener {
        void onRefreshComplete();
    }
}
