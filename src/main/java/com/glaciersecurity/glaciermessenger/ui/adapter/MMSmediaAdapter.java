package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.glaciersecurity.glaciermessenger.R;
import com.google.android.material.color.MaterialColors;
import com.glaciersecurity.glaciermessenger.databinding.MediaPreviewBinding;
import com.glaciersecurity.glaciermessenger.ui.ToggleInputMethodListener;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.ui.adapter.MediaAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.Attachment;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;

public class MMSmediaAdapter extends RecyclerView.Adapter<MMSmediaAdapter.MediaPreviewViewHolder> {

    private final ArrayList<Attachment> mediaPreviews = new ArrayList<>();
    private Context context;
    private ToggleInputMethodListener listener;

    public MMSmediaAdapter(Context context, ToggleInputMethodListener listener) {
        this.context = context;
        this.listener = listener;
    }

    @NonNull
    @Override
    public MediaPreviewViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        MediaPreviewBinding binding = DataBindingUtil.inflate(layoutInflater, R.layout.media_preview, parent, false);
        return new MediaPreviewViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaPreviewViewHolder holder, int position) {
        final Attachment attachment = mediaPreviews.get(position);
        if (attachment.renderThumbnail()) {
            holder.binding.mediaPreview.setImageAlpha(255);
            loadPreview(attachment, holder.binding.mediaPreview);
        } else {
            cancelPotentialWork(attachment, holder.binding.mediaPreview);
            MediaAdapter.renderPreview(attachment, holder.binding.mediaPreview);
        }
        holder.binding.deleteButton.setOnClickListener(v -> {
            int pos = mediaPreviews.indexOf(attachment);
            if(pos > -1) {
                mediaPreviews.remove(pos);
                notifyItemRemoved(pos);
                listener.toggleInputMethod();
            }else{
                notifyDataSetChanged();
            }
        });
    }

    public void addMediaPreviews(List<Attachment> attachments) {
        this.mediaPreviews.addAll(attachments);
        notifyDataSetChanged();
    }

    private void loadPreview(Attachment attachment, ImageView imageView) {
        if (cancelPotentialWork(attachment, imageView)) {
            XmppActivity activity = (XmppActivity) context;
            final Bitmap bm = activity.xmppConnectionService.getFileBackend().getPreviewForUri(attachment,Math.round(activity.getResources().getDimension(R.dimen.media_preview_size)),true);
            if (bm != null) {
                cancelPotentialWork(attachment, imageView);
                imageView.setImageBitmap(bm);
                imageView.setBackgroundColor(0x00000000);
            } else {
                imageView.setBackgroundColor(MaterialColors.getColor(imageView, com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF333333));
                imageView.setImageDrawable(null);
                final BitmapWorkerTask task = new BitmapWorkerTask(imageView);
                final AsyncDrawable asyncDrawable = new AsyncDrawable(context.getResources(), null, task);
                imageView.setImageDrawable(asyncDrawable);
                try {
                    task.execute(attachment);
                } catch (final RejectedExecutionException ignored) {
                }
            }
        }
    }

    private static boolean cancelPotentialWork(Attachment attachment, ImageView imageView) {
        final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final Attachment oldAttachment = bitmapWorkerTask.attachment;
            if (oldAttachment == null || !oldAttachment.equals(attachment)) {
                bitmapWorkerTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }

    private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable) {
                final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    @Override
    public int getItemCount() {
        return mediaPreviews.size();
    }

    public boolean hasAttachments() {
        return mediaPreviews.size() > 0;
    }

    public ArrayList<Attachment> getAttachments() {
        return mediaPreviews;
    }

    public void clearPreviews() {
        this.mediaPreviews.clear();
    }

    static class MediaPreviewViewHolder extends RecyclerView.ViewHolder {

        private final MediaPreviewBinding binding;

        MediaPreviewViewHolder(MediaPreviewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

        AsyncDrawable(Resources res, Bitmap bitmap, BitmapWorkerTask bitmapWorkerTask) {
            super(res, bitmap);
            bitmapWorkerTaskReference = new WeakReference<>(bitmapWorkerTask);
        }

        BitmapWorkerTask getBitmapWorkerTask() {
            return bitmapWorkerTaskReference.get();
        }
    }

    private static class BitmapWorkerTask extends AsyncTask<Attachment, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewReference;
        private Attachment attachment = null;

        BitmapWorkerTask(ImageView imageView) {
            imageViewReference = new WeakReference<>(imageView);
        }

        @Override
        protected Bitmap doInBackground(Attachment... params) {
            this.attachment = params[0];
            final XmppActivity activity = XmppActivity.find(imageViewReference);
            if (activity == null) {
                return null;
            }
            return activity.xmppConnectionService.getFileBackend().getPreviewForUri(this.attachment, Math.round(activity.getResources().getDimension(R.dimen.media_preview_size)), false);
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null && !isCancelled()) {
                final ImageView imageView = imageViewReference.get();
                if (imageView != null) {
                    imageView.setImageBitmap(bitmap);
                    imageView.setBackgroundColor(0x00000000);
                }
            }
        }
    }
}
