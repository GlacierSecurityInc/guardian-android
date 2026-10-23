package com.glaciersecurity.glaciermessenger.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityMediaViewerBinding;
import com.glaciersecurity.glaciermessenger.ui.adapter.MediaViewerPagerAdapter;
import com.glaciersecurity.glaciermessenger.ui.adapter.MediaViewerPagerAdapter.MediaItem;
import com.glaciersecurity.glaciermessenger.utils.ContentUriValidator;
import com.glaciersecurity.glaciermessenger.utils.FileUtils;
import com.glaciersecurity.glaciermessenger.utils.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaViewerActivity extends AppCompatActivity {

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    public static final String EXTRA_MIME_TYPE = "com.glaciersecurity.glaciermessenger.ui.MediaViewerActivity.EXTRA_MIME_TYPE";
    public static final String EXTRA_URIS = "com.glaciersecurity.glaciermessenger.ui.MediaViewerActivity.EXTRA_URIS";
    public static final String EXTRA_MIME_TYPES = "com.glaciersecurity.glaciermessenger.ui.MediaViewerActivity.EXTRA_MIME_TYPES";
    public static final String EXTRA_TITLES = "com.glaciersecurity.glaciermessenger.ui.MediaViewerActivity.EXTRA_TITLES";
    public static final String EXTRA_POSITION = "com.glaciersecurity.glaciermessenger.ui.MediaViewerActivity.EXTRA_POSITION";
    private static final int REQUEST_SAVE_TO = 0x1301;
    private static final String STATE_POSITION = "current_position";
    private static final String STATE_VIDEO_POSITION_MS = "video_position_ms";

    private ActivityMediaViewerBinding binding;
    private MediaViewerPagerAdapter adapter;
    private final List<MediaItem> items = new ArrayList<>();
    private int currentPosition = 0;
    private int pendingVideoSeekMs = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_media_viewer);
        setSupportActionBar(binding.toolbar);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (!buildMediaItems()) {
            showErrorAndFinish();
            return;
        }
        // buildMediaItems() seeds currentPosition from the intent, which is the page the user
        // opened rather than the one they swiped to, so a restore has to happen after it.
        restoreState(savedInstanceState);

        adapter = new MediaViewerPagerAdapter(items, this::finish);
        binding.mediaPager.setAdapter(adapter);
        binding.mediaPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentPosition = position;
                invalidateOptionsMenu();
                updateToolbarTitle();
                updateNavigationButtons();
                updateVideoPlayback();
            }
        });
        binding.prevButton.setOnClickListener(v -> showRelative(-1));
        binding.nextButton.setOnClickListener(v -> showRelative(1));
        binding.mediaPager.setCurrentItem(currentPosition, false);
        updateToolbarTitle();
        updateNavigationButtons();
        binding.mediaPager.post(this::updateVideoPlayback);
    }

    private void restoreState(@Nullable final Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        final int position = savedInstanceState.getInt(STATE_POSITION, currentPosition);
        if (position >= 0 && position < items.size()) {
            currentPosition = position;
        }
        pendingVideoSeekMs = savedInstanceState.getInt(STATE_VIDEO_POSITION_MS, 0);
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_POSITION, currentPosition);
        outState.putInt(STATE_VIDEO_POSITION_MS, currentVideoPositionMs());
    }

    /**
     * ViewPager2 keeps its pages in an internal RecyclerView child, which is the only way to
     * reach the bound view holders. It is absent until the pager has been laid out, so every
     * caller has to cope with null.
     */
    @Nullable
    private RecyclerView pagerRecyclerView() {
        if (binding == null) {
            return null;
        }
        final android.view.View child = binding.mediaPager.getChildAt(0);
        return child instanceof RecyclerView recyclerView ? recyclerView : null;
    }

    @Nullable
    private MediaViewerPagerAdapter.MediaPageViewHolder pageHolderAt(final int position) {
        final RecyclerView recyclerView = pagerRecyclerView();
        if (recyclerView == null) {
            return null;
        }
        final RecyclerView.ViewHolder holder =
                recyclerView.findViewHolderForAdapterPosition(position);
        return holder instanceof MediaViewerPagerAdapter.MediaPageViewHolder pageHolder
                ? pageHolder
                : null;
    }

    private int currentVideoPositionMs() {
        final MediaViewerPagerAdapter.MediaPageViewHolder pageHolder =
                pageHolderAt(currentPosition);
        return pageHolder == null ? 0 : pageHolder.videoPositionMs();
    }

    private void updateToolbarTitle() {
        if (getSupportActionBar() == null) {
            return;
        }
        if (items.size() > 1) {
            getSupportActionBar().setTitle((currentPosition + 1) + " / " + items.size());
        } else {
            getSupportActionBar().setTitle("");
        }
    }

    private void showRelative(final int delta) {
        final int target = currentPosition + delta;
        if (target >= 0 && target < items.size()) {
            binding.mediaPager.setCurrentItem(target, true);
        }
    }

    private void updateNavigationButtons() {
        binding.prevButton.setVisibility(currentPosition > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        binding.nextButton.setVisibility(
                currentPosition < items.size() - 1 ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private boolean buildMediaItems() {
        final Intent intent = getIntent();
        items.clear();

        final ArrayList<Uri> uris = intent.getParcelableArrayListExtra(EXTRA_URIS);
        if (uris != null && !uris.isEmpty()) {
            final ArrayList<String> mimes = intent.getStringArrayListExtra(EXTRA_MIME_TYPES);
            final ArrayList<String> titles = intent.getStringArrayListExtra(EXTRA_TITLES);
            for (int i = 0; i < uris.size(); ++i) {
                final Uri uri = uris.get(i);
                final String mime = mimes != null && i < mimes.size() ? mimes.get(i) : null;
                String displayName = titles != null && i < titles.size() ? titles.get(i) : null;
                items.add(new MediaItem(uri, mime, resolveDisplayName(uri, displayName)));
            }
            int position = intent.getIntExtra(EXTRA_POSITION, 0);
            if (position < 0 || position >= items.size()) {
                position = 0;
            }
            currentPosition = position;
            return true;
        }

        // Backward compatible single-item invocation.
        final Uri uri = intent.getData();
        final String mime = intent.getStringExtra(EXTRA_MIME_TYPE);
        if (uri == null || mime == null) {
            return false;
        }
        items.add(new MediaItem(uri, mime, resolveDisplayName(uri, intent.getStringExtra(Intent.EXTRA_TITLE))));
        currentPosition = 0;
        return true;
    }

    private String resolveDisplayName(final Uri uri, @Nullable final String provided) {
        if (!TextUtils.isEmpty(provided)) {
            return provided;
        }
        final String fromUri = FileUtils.getDisplayName(this, uri);
        if (!TextUtils.isEmpty(fromUri)) {
            return fromUri;
        }
        return "media";
    }

    @Nullable
    private MediaItem currentItem() {
        if (currentPosition < 0 || currentPosition >= items.size()) {
            return null;
        }
        return items.get(currentPosition);
    }

    private void updateVideoPlayback() {
        for (int i = 0; i < items.size(); ++i) {
            final MediaViewerPagerAdapter.MediaPageViewHolder pageHolder = pageHolderAt(i);
            if (pageHolder == null) {
                continue;
            }
            if (i == currentPosition) {
                if (pendingVideoSeekMs > 0) {
                    pageHolder.seekIfVideo(pendingVideoSeekMs);
                    pendingVideoSeekMs = 0;
                }
                pageHolder.resumeIfVideo();
            } else {
                pageHolder.pauseIfVideo();
            }
        }
    }

    private void pauseAllVideos() {
        for (int i = 0; i < items.size(); ++i) {
            final MediaViewerPagerAdapter.MediaPageViewHolder pageHolder = pageHolderAt(i);
            if (pageHolder != null) {
                pageHolder.pauseIfVideo();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        pauseAllVideos();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_media_viewer, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        final MenuItem uploadFileSafe = menu.findItem(R.id.upload_file_s3);
        if (uploadFileSafe != null) {
            uploadFileSafe.setVisible(isFileSafeEnabled());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.share_with) {
            shareWith();
            return true;
        } else if (id == R.id.download_file_to) {
            saveTo();
            return true;
        } else if (id == R.id.upload_file_s3) {
            uploadToFileSafe();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SAVE_TO && resultCode == RESULT_OK && data != null) {
            handleSaveToResult(data.getData());
        }
    }

    private void showErrorAndFinish() {
        android.widget.Toast.makeText(this, R.string.media_viewer_error, android.widget.Toast.LENGTH_SHORT).show();
        finish();
    }

    private void shareWith() {
        final MediaItem item = currentItem();
        if (item == null) {
            return;
        }
        final Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_STREAM, item.uri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.setType(TextUtils.isEmpty(item.mime) ? "*/*" : item.mime);
        try {
            startActivity(Intent.createChooser(shareIntent, getText(R.string.share_with)));
        } catch (ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, R.string.no_application_found_to_open_file, android.widget.Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private void saveTo() {
        final MediaItem item = currentItem();
        if (item == null) {
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(TextUtils.isEmpty(item.mime) ? "*/*" : item.mime);
        intent.putExtra(Intent.EXTRA_TITLE, item.displayName);
        try {
            startActivityForResult(intent, REQUEST_SAVE_TO);
        } catch (ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, R.string.no_application_found, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSaveToResult(@Nullable Uri destination) {
        final MediaItem item = currentItem();
        if (destination == null || item == null) {
            android.widget.Toast.makeText(this, R.string.attachment_save_failed, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The destination is whatever the picker activity put in its result, so it is not ours to
        // trust: refuse to write through one that names our own storage.
        if (!ContentUriValidator.isSafeToResolve(this, destination)) {
            Log.e("MediaViewerActivity", "refusing to save to an untrusted destination");
            android.widget.Toast.makeText(this, R.string.attachment_save_failed, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        ioExecutor.execute(() -> {
            try (InputStream inputStream = getContentResolver().openInputStream(item.uri);
                 OutputStream outputStream = getContentResolver().openOutputStream(destination)) {
                if (inputStream == null || outputStream == null) {
                    runOnUiThread(() -> android.widget.Toast.makeText(
                            this,
                            R.string.attachment_save_failed,
                            android.widget.Toast.LENGTH_SHORT
                    ).show());
                    return;
                }
                final byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                runOnUiThread(() -> android.widget.Toast.makeText(
                        this,
                        R.string.attachment_saved_custom_location,
                        android.widget.Toast.LENGTH_SHORT
                ).show());
            } catch (IOException e) {
                Log.e("MediaViewerActivity", "Unable to save attachment", e);
                runOnUiThread(() -> android.widget.Toast.makeText(
                        this,
                        R.string.attachment_save_failed,
                        android.widget.Toast.LENGTH_SHORT
                ).show());
            }
        });
    }

    private void uploadToFileSafe() {
        final MediaItem item = currentItem();
        if (item == null) {
            return;
        }
        final Intent intent = new Intent(this, FileSafeActivity.class);
        intent.putExtra(FileSafeActivity.EXTRA_PREFILL_URI, item.uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdown();
        super.onDestroy();
    }

    private boolean isFileSafeEnabled() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return preferences.getBoolean("upload_enabled", false);
    }
}
