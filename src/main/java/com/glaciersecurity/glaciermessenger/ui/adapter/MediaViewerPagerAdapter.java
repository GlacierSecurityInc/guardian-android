package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.graphics.Movie;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.MediaController;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ItemMediaViewerBinding;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter backing the swipeable media gallery in {@link com.glaciersecurity.glaciermessenger.ui.MediaViewerActivity}.
 * Each page renders a single image, gif or video so the user can swipe left/right between shared media.
 */
public class MediaViewerPagerAdapter extends RecyclerView.Adapter<MediaViewerPagerAdapter.MediaPageViewHolder> {

    public interface OnMediaClickListener {
        void onMediaClick();
    }

    public static class MediaItem {
        public final Uri uri;
        public final String mime;
        public final String displayName;

        public MediaItem(final Uri uri, final String mime, final String displayName) {
            this.uri = uri;
            this.mime = mime;
            this.displayName = displayName;
        }
    }

    private final List<MediaItem> items = new ArrayList<>();
    private final OnMediaClickListener clickListener;

    public MediaViewerPagerAdapter(final List<MediaItem> items, final OnMediaClickListener clickListener) {
        this.items.addAll(items);
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public MediaPageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        final ItemMediaViewerBinding binding =
                DataBindingUtil.inflate(layoutInflater, R.layout.item_media_viewer, parent, false);
        return new MediaPageViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaPageViewHolder holder, int position) {
        holder.bind(items.get(position), clickListener);
    }

    @Override
    public void onViewRecycled(@NonNull MediaPageViewHolder holder) {
        super.onViewRecycled(holder);
        holder.stopVideo();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class MediaPageViewHolder extends RecyclerView.ViewHolder {

        private final ItemMediaViewerBinding binding;
        private MediaController mediaController;
        private boolean isVideo;

        MediaPageViewHolder(final ItemMediaViewerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(final MediaItem item, final OnMediaClickListener clickListener) {
            isVideo = false;
            final String mime = item.mime == null ? "" : item.mime;
            if (mime.startsWith("image/gif")) {
                displayGif(item.uri, clickListener);
            } else if (mime.startsWith("image/")) {
                displayImage(item.uri, clickListener);
            } else if (mime.startsWith("video/")) {
                displayVideo(item.uri);
            } else {
                // Nothing we can render. Clear the page rather than leaving whatever the
                // recycled holder was showing before, which would look like the wrong media.
                clear();
            }
        }

        private void clear() {
            binding.mediaImage.setImageDrawable(null);
            binding.mediaImage.setVisibility(View.GONE);
            binding.mediaGif.setVisibility(View.GONE);
            binding.mediaVideo.setVisibility(View.GONE);
        }

        private void displayImage(final Uri uri, final OnMediaClickListener clickListener) {
            binding.mediaVideo.setVisibility(View.GONE);
            binding.mediaGif.setVisibility(View.GONE);
            binding.mediaImage.setVisibility(View.VISIBLE);
            try {
                binding.mediaImage.setImageURI(uri);
            } catch (final SecurityException | IllegalArgumentException e) {
                // Don't leave the previous page's bitmap behind on a recycled holder.
                binding.mediaImage.setImageDrawable(null);
                return;
            }
            binding.mediaImage.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onMediaClick();
                }
            });
        }

        private void displayGif(final Uri uri, final OnMediaClickListener clickListener) {
            binding.mediaImage.setVisibility(View.GONE);
            binding.mediaVideo.setVisibility(View.GONE);
            binding.mediaGif.setVisibility(View.VISIBLE);
            try (final InputStream inputStream =
                         binding.getRoot().getContext().getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    return;
                }
                final Movie movie = Movie.decodeStream(inputStream);
                if (movie == null) {
                    return;
                }
                binding.mediaGif.setMovie(movie);
            } catch (final SecurityException | IllegalArgumentException | java.io.IOException e) {
                return;
            }
            binding.mediaGif.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onMediaClick();
                }
            });
        }

        private void displayVideo(final Uri uri) {
            isVideo = true;
            binding.mediaImage.setVisibility(View.GONE);
            binding.mediaGif.setVisibility(View.GONE);
            binding.mediaVideo.setVisibility(View.VISIBLE);
            if (mediaController == null) {
                mediaController = new MediaController(binding.getRoot().getContext());
                mediaController.setAnchorView(binding.mediaVideo);
            }
            binding.mediaVideo.setMediaController(mediaController);
            // Don't auto-start here: a freshly bound off-screen page would begin playing
            // audio before the user swipes to it. Playback is driven by resumeIfVideo()
            // for the currently selected page only; VideoView remembers the requested
            // target state and starts once it has finished preparing.
            try {
                binding.mediaVideo.setVideoURI(uri);
            } catch (final SecurityException | IllegalArgumentException e) {
                // ignore - nothing to display
            }
        }

        public void resumeIfVideo() {
            if (isVideo && binding.mediaVideo.getVisibility() == View.VISIBLE) {
                binding.mediaVideo.start();
            }
        }

        /**
         * Playback position of this page's video, or 0 when it has none. Safe to call while paused:
         * {@code pause()} keeps the position, only {@code stopPlayback()} rewinds.
         */
        public int videoPositionMs() {
            if (isVideo && binding.mediaVideo.getVisibility() == View.VISIBLE) {
                return Math.max(0, binding.mediaVideo.getCurrentPosition());
            }
            return 0;
        }

        /**
         * Seeks this page's video. VideoView holds the request until it has finished preparing, so
         * this works straight after {@code setVideoURI()} on a freshly bound holder.
         */
        public void seekIfVideo(final int positionMs) {
            if (isVideo && positionMs > 0 && binding.mediaVideo.getVisibility() == View.VISIBLE) {
                binding.mediaVideo.seekTo(positionMs);
            }
        }

        public void pauseIfVideo() {
            if (isVideo && binding.mediaVideo.getVisibility() == View.VISIBLE
                    && binding.mediaVideo.isPlaying()) {
                binding.mediaVideo.pause();
            }
        }

        void stopVideo() {
            if (isVideo) {
                binding.mediaVideo.stopPlayback();
            }
        }
    }
}
