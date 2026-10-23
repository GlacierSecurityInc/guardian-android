package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.CoreProfile;
import com.glaciersecurity.glaciermessenger.ui.CoreConnectionActivity;
import com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties;

import java.util.ArrayList;
import java.util.Collections;

public class CoreProfileAdapter extends ArrayAdapter<CoreProfile> {

    public final ArrayList<CoreProfile> profileList = new ArrayList<>();
    private final LayoutInflater inflater;
    private CoreConnectionActivity coreActivity;

    public CoreProfileAdapter(@NonNull Context context, CoreConnectionActivity activity) {
        super(context, R.layout.core_profile_dropdown_item, new ArrayList<>());
        setNotifyOnChange(false);
        inflater = LayoutInflater.from(context);
        coreActivity = activity;
    }

    @Override
    public int getCount() {
        return profileList.size();
    }

    @Nullable
    @Override
    public CoreProfile getItem(int position) {
        if (position < 0 || position >= profileList.size()) {
            return null;
        }
        return profileList.get(position);
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        return createView(position, convertView, parent);
    }

    @Override
    public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        return createView(position, convertView, parent);
    }

    private View createView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.core_profile_dropdown_item, parent, false);
        }
        TextView title = view.findViewById(R.id.core_profile_dropdown_item_text);
        CoreProfile profile = getItem(position);
        if (profile != null) {
            title.setText(profile.getDisplayName());
        } else {
            title.setText("");
        }
        return view;
    }

    public void addItem(@NonNull CoreProfile item) {
        boolean updated = false;
        for (int i = 0; i < profileList.size(); i++) {
            if (profileList.get(i).getProfName().equals(item.getProfName())) {
                profileList.set(i, item);
                updated = true;
                break;
            }
        }
        if (!updated) {
            profileList.add(item);
        }
        Collections.sort(profileList);
        refreshAdapterContents();
    }

    private boolean isProfileRunning(CoreProfile prof) {
        return PersistentConnectionProperties.getInstance().isProfileRunning(prof.getProfName());
    }

    public void setProfileList(@NonNull ArrayList<CoreProfile> coreProfiles) {
        profileList.clear();
        profileList.addAll(coreProfiles);
        Collections.sort(profileList);
        refreshAdapterContents();
    }

    public void clearProfiles() {
        profileList.clear();
        refreshAdapterContents();
    }

    public void editProfile(@NonNull CoreProfile coreProfile) {
        for (int i = 0; i < profileList.size(); i++) {
            if (profileList.get(i).getProfName().equals(coreProfile.getProfName())) {
                profileList.set(i, coreProfile);
                Collections.sort(profileList);
                refreshAdapterContents();
                return;
            }
        }
    }

    public boolean deleteCoreProfile(@NonNull CoreProfile coreProfile) {
        for (int i = 0; i < profileList.size(); i++) {
            if (profileList.get(i).getProfName().equals(coreProfile.getProfName())) {
                profileList.remove(i);
                refreshAdapterContents();
                coreActivity.xmppConnectionService.deleteCoreProfile(coreProfile);
                return true;
            }
        }
        return false;
    }

    private void refreshAdapterContents() {
        super.clear();
        super.addAll(profileList);
        super.notifyDataSetChanged();
    }
}
