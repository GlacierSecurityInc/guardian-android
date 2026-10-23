package com.glaciersecurity.glaciermessenger.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityTextViewerBinding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class TextViewerActivity extends AppCompatActivity {

    private static final int MAX_CHARACTERS = 500_000;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_viewer);
        final ActivityTextViewerBinding binding = DataBindingUtil.setContentView(this,R.layout.activity_text_viewer);
        setSupportActionBar(binding.toolbar);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        final TextView textView = findViewById(R.id.text_viewer_content);
        final Uri uri = getIntent().getData();
        if (uri == null) {
            textView.setText(R.string.text_viewer_load_error);
            return;
        }

        final String title = getIntent().getStringExtra(Intent.EXTRA_TITLE);
        if (title != null && !title.isEmpty()) {
            setTitle(title);
        }

        loadText(uri, textView);
    }

    private void loadText(Uri uri, TextView textView) {
        final StringBuilder builder = new StringBuilder();
        boolean truncated = false;

        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                Objects.requireNonNull(getContentResolver().openInputStream(uri)),
                                StandardCharsets.UTF_8))) {
            final char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (builder.length() + read > MAX_CHARACTERS) {
                    final int allowed = MAX_CHARACTERS - builder.length();
                    if (allowed > 0) {
                        builder.append(buffer, 0, allowed);
                    }
                    truncated = true;
                    break;
                }
                builder.append(buffer, 0, read);
            }
        } catch (IOException | NullPointerException | SecurityException e) {
            textView.setText(R.string.text_viewer_load_error);
            return;
        }

        if (builder.length() == 0) {
            textView.setText("");
            return;
        }

        if (truncated) {
            builder.append("\n\n").append(getString(R.string.text_viewer_truncated));
        }

        textView.setText(builder.toString());
        textView.setTextIsSelectable(true);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
