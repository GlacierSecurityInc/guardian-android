package com.glaciersecurity.glaciermessenger.ui;

import static com.glaciersecurity.glaciermessenger.ui.XmppActivity.configureActionBar;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.databinding.DataBindingUtil;

import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityAboutBinding;

public class AboutActivity extends ActionBarActivity {

    private static final String WEBSITE_URL = "https://glacier.chat";
    private static final String SUPPORT_EMAIL = "support@glacier.chat";
    private static final String PRIVACY_POLICY_URL = "https://glacier.chat/legal";
    private static final String SOURCE_CODE_URL = "https://github.com/GlacierSecurityInc";
    private static final String FULL_LICENSE_URL = "https://www.gnu.org/licenses";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ActivityAboutBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_about);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());

        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        setTitle(getString(R.string.title_activity_about_x, getString(R.string.app_name)));

        binding.appNameTitle.setText(BuildConfig.APP_NAME);
        binding.versionPill.setText(BuildConfig.VERSION_NAME);

        binding.rowWebsite.setOnClickListener(v -> openUrl(WEBSITE_URL));
        binding.rowSupport.setOnClickListener(v -> openEmail(SUPPORT_EMAIL));
        binding.rowPrivacy.setOnClickListener(v -> openUrl(PRIVACY_POLICY_URL));
        binding.rowSourceCode.setOnClickListener(v -> openUrl(SOURCE_CODE_URL));
        binding.rowFullLicense.setOnClickListener(v -> openUrl(FULL_LICENSE_URL));

        binding.rowLicense.setOnClickListener(v -> toggleExpand(
                binding.licenseDetailsContainer, binding.licenseExpandIcon, binding.licenseToggleLabel));
        binding.rowLibraries.setOnClickListener(v -> toggleExpand(
                binding.librariesDetailsContainer, binding.librariesExpandIcon, null));
    }

    private void toggleExpand(final TextView detailsContainer, final ImageView expandIcon, final TextView toggleLabel) {
        final boolean expanding = detailsContainer.getVisibility() != View.VISIBLE;
        detailsContainer.setVisibility(expanding ? View.VISIBLE : View.GONE);
        expandIcon.setRotation(expanding ? 180f : 0f);
        if (toggleLabel != null) {
            toggleLabel.setText(expanding ? R.string.about_license_details_hide : R.string.about_license_details_label);
        }
    }

    private void openUrl(final String url) {
        openExternalIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void openEmail(final String email) {
        openExternalIntent(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + email)));
    }

    private void openExternalIntent(final Intent intent) {
        try {
            startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.about_link_unavailable, Toast.LENGTH_LONG).show();
        }
    }
}
