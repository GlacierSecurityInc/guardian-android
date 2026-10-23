package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.FileSafeAttachment;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.ui.util.Attachment;
import com.glaciersecurity.glaciermessenger.utils.FileUtils;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class FileSafeAttachmentAdapter extends RecyclerView.Adapter<FileSafeAttachmentAdapter.FileSafeAttachmentViewHolder> {

    public interface OnAttachmentActionListener {
        void onRemove(FileSafeAttachment attachment);
        void onRetry(FileSafeAttachment attachment);
    }

    private static final int CORNER_RADIUS_DP = 10;

    protected XmppActivity activity;
    private List<FileSafeAttachment> fileSafeAttachments = new ArrayList<>();
    private OnAttachmentActionListener actionListener;

    public FileSafeAttachmentAdapter(XmppActivity activity, List<FileSafeAttachment> fileSafeAttachments) {
        this.fileSafeAttachments = fileSafeAttachments;
        this.activity = activity;
    }

    public void setOnAttachmentActionListener(OnAttachmentActionListener listener) {
        this.actionListener = listener;
    }

    @NonNull
    @Override
    public FileSafeAttachmentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_filesafe_attachment, parent, false);
        return new FileSafeAttachmentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileSafeAttachmentViewHolder holder, int position) {
        final FileSafeAttachment item = fileSafeAttachments.get(position);
        final Attachment a = item.getAttachment();
        final FileSafeAttachment.UploadStatus status = item.getUploadStatus();

        holder.name.setText(FileUtils.getDisplayName(activity, a.getUri()));

        bindFileTile(holder, a);
        bindStatusText(holder, status);
        bindProgressBar(holder, status);
        bindActionButton(holder, item, status);
    }

    private void bindFileTile(@NonNull FileSafeAttachmentViewHolder holder, Attachment a) {
        final String ext = getExtension(a != null ? a.getMime() : null);
        final int[] colorRes = getTileColorResIds(ext);
        final android.content.Context ctx = holder.fileTile.getContext();
        final float cornerPx = ctx.getResources().getDisplayMetrics().density * CORNER_RADIUS_DP;

        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(cornerPx);
        bg.setColor(ctx.getColor(colorRes[0]));

        holder.fileTile.setBackground(bg);
        holder.fileTile.setTextColor(ctx.getColor(colorRes[1]));
        holder.fileTile.setText(ext.length() > 4 ? ext.substring(0, 4) : ext);
    }

    private void bindStatusText(@NonNull FileSafeAttachmentViewHolder holder, FileSafeAttachment.UploadStatus status) {
        final String text;
        final int color;
        switch (status) {
            case SUCCESS:
                text = activity.getString(R.string.upload_filesafe_status_uploaded);
                color = activity.getColor(R.color.filesafe_status_success);
                break;
            case FAILED:
                text = activity.getString(R.string.upload_filesafe_status_failed);
                color = activity.getColor(R.color.filesafe_status_failed);
                break;
            case IN_PROGRESS:
                text = activity.getString(R.string.uploading_filesafe_button_message);
                color = resolveAccentColor();
                break;
            default: // INIT
                text = activity.getString(R.string.upload_filesafe_status_waiting);
                color = resolveSecondaryTextColor();
                break;
        }
        holder.statusText.setText(text);
        holder.statusText.setTextColor(color);
    }

    private void bindProgressBar(@NonNull FileSafeAttachmentViewHolder holder, FileSafeAttachment.UploadStatus status) {
        if (status == FileSafeAttachment.UploadStatus.IN_PROGRESS) {
            holder.progressBar.setVisibility(View.VISIBLE);
            holder.progressBar.setIndeterminate(true);
        } else {
            holder.progressBar.setVisibility(View.GONE);
        }
    }

    private void bindActionButton(@NonNull FileSafeAttachmentViewHolder holder, FileSafeAttachment item,
                                  FileSafeAttachment.UploadStatus status) {
        switch (status) {
            case FAILED:
                holder.actionButton.setImageResource(R.drawable.ic_autorenew_24dp);
                holder.actionButton.setContentDescription(
                        activity.getString(R.string.upload_filesafe_retry_file_cd));
                holder.actionButton.setVisibility(View.VISIBLE);
                holder.actionButton.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onRetry(item);
                });
                break;
            case SUCCESS:
            case IN_PROGRESS:
                holder.actionButton.setVisibility(View.GONE);
                holder.actionButton.setOnClickListener(null);
                break;
            default: // INIT
                holder.actionButton.setImageResource(R.drawable.ic_baseline_close_24);
                holder.actionButton.setContentDescription(
                        activity.getString(R.string.upload_filesafe_remove_cd));
                holder.actionButton.setVisibility(View.VISIBLE);
                holder.actionButton.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onRemove(item);
                });
                break;
        }
    }

    @SuppressLint("ResourceType")
    private int resolveAccentColor() {
        final int[] attrs = { com.google.android.material.R.attr.backgroundColor };
        try {
            final android.content.res.TypedArray ta =
                    activity.obtainStyledAttributes(attrs);
            final int color = ta.getColor(0, Color.BLUE);
            ta.recycle();
            return color;
        } catch (Exception e) {
            return Color.BLUE;
        }
    }

    @SuppressLint("ResourceType")
    private int resolveSecondaryTextColor() {
        final int[] attrs = { android.R.attr.textColorSecondary };
        try {
            final android.content.res.TypedArray ta =
                    activity.obtainStyledAttributes(attrs);
            final int color = ta.getColor(0, Color.GRAY);
            ta.recycle();
            return color;
        } catch (Exception e) {
            return Color.GRAY;
        }
    }

    private String getExtension(String mimeType) {
        if (mimeType == null) return "file";
        switch (mimeType) {
            case "application/pdf":                        return "pdf";
            case "application/msword":
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
                                                           return "doc";
            case "application/vnd.ms-excel":
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
            case "text/csv":                               return "xls";
            case "application/zip":
            case "application/x-zip-compressed":          return "zip";
            case "image/jpeg":                             return "jpg";
            case "image/png":                              return "png";
            case "image/heic":
            case "image/heif":                             return "heic";
            case "video/mp4":                              return "mp4";
            case "video/quicktime":                        return "mov";
            case "video/avi":
            case "video/x-msvideo":                       return "avi";
            case "video/webm":
            case "video/mkv":
            case "video/x-matroska":                      return "mkv";
            case "audio/mpeg":
            case "audio/mp3":                              return "mp3";
            case "text/plain":                             return "txt";
            default:
                // Try to derive from the subtype
                final int slash = mimeType.lastIndexOf('/');
                if (slash >= 0 && slash < mimeType.length() - 1) {
                    return mimeType.substring(slash + 1).replaceAll("[^a-zA-Z0-9]", "");
                }
                return "file";
        }
    }

    private int[] getTileColorResIds(String ext) {
        switch (ext.toLowerCase()) {
            case "pdf":
                return new int[]{ R.color.filesafe_tile_pdf_bg, R.color.filesafe_tile_pdf_fg };
            case "doc": case "docx":
                return new int[]{ R.color.filesafe_tile_doc_bg, R.color.filesafe_tile_doc_fg };
            case "xls": case "xlsx": case "csv":
                return new int[]{ R.color.filesafe_tile_xls_bg, R.color.filesafe_tile_xls_fg };
            case "zip": case "rar": case "7z":
                return new int[]{ R.color.filesafe_tile_zip_bg, R.color.filesafe_tile_zip_fg };
            case "jpg": case "jpeg": case "png":
            case "heic": case "heif": case "gif": case "webp":
                return new int[]{ R.color.filesafe_tile_image_bg, R.color.filesafe_tile_image_fg };
            case "mp4": case "mov": case "avi":
            case "mkv": case "webm":
                return new int[]{ R.color.filesafe_tile_video_bg, R.color.filesafe_tile_video_fg };
            case "txt": case "log":
            default:
                return new int[]{ R.color.filesafe_tile_txt_bg, R.color.filesafe_tile_txt_fg };
        }
    }

    // ── List mutation helpers ────────────────────────────────────────────────

    public void updateAttachmentsList(List<FileSafeAttachment> list) {
        this.fileSafeAttachments = list;
    }

    public void updateAttachment(FileSafeAttachment updated) {
        for (int i = 0; i < fileSafeAttachments.size(); i++) {
            if (updated.equals(fileSafeAttachments.get(i))) {
                fileSafeAttachments.set(i, updated);
                notifyItemChanged(i);
                return;
            }
        }
    }

    public void updateAttachment(Attachment a, FileSafeAttachment.UploadStatus us) {
        updateAttachment(new FileSafeAttachment(a, us));
    }

    public void addAttachmentsList(FileSafeAttachment item) {
        this.fileSafeAttachments.add(0, item);
        notifyItemInserted(0);
    }

    public void addAttachmentsList(Attachment attachment, FileSafeAttachment.UploadStatus status) {
        addAttachmentsList(new FileSafeAttachment(attachment, status));
    }

    public void removeAttachmentsList(Attachment attachment) {
        for (int i = 0; i < fileSafeAttachments.size(); i++) {
            if (fileSafeAttachments.get(i).getAttachment().equals(attachment)) {
                fileSafeAttachments.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    public void clearAttachmentsList() {
        this.fileSafeAttachments.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return fileSafeAttachments != null ? fileSafeAttachments.size() : 0;
    }

    // ── FileSafeAttachmentViewHolder ───────────────────────────────────────────────────────────

    static class FileSafeAttachmentViewHolder extends RecyclerView.ViewHolder {
        final TextView fileTile;
        final ImageView statusBadge;
        final TextView name;
        final TextView statusText;
        final TextView size;
        final ImageButton actionButton;
        final LinearProgressIndicator progressBar;

        FileSafeAttachmentViewHolder(View view) {
            super(view);
            fileTile     = view.findViewById(R.id.file_ext_tile);
            statusBadge  = view.findViewById(R.id.status_badge);
            name         = view.findViewById(R.id.attachment_path);
            statusText   = view.findViewById(R.id.status_text);
            size         = view.findViewById(R.id.attachment_size);
            actionButton = view.findViewById(R.id.action_button);
            progressBar  = view.findViewById(R.id.upload_progress);
        }
    }
}
