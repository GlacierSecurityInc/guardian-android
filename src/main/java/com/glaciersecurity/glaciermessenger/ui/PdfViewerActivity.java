package com.glaciersecurity.glaciermessenger.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.github.barteksc.pdfviewer.PDFView;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.glaciersecurity.glaciermessenger.ui.widget.SignatureView;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.shockwave.pdfium.PdfPasswordException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerActivity extends AppCompatActivity {

    private PDFView pdfView;
    private FrameLayout pdfContainer;
    private ImageView signatureOverlay;
    private SignatureView signatureView;
    private TextInputEditText signatureTypeInput;
    private Slider signatureSizeSlider;
    private android.app.Dialog signatureDialog;
    private Bitmap signatureBitmap;
    private int currentPage = 0;
    private int signatureColor = Color.BLACK;
    private String pdfPassword;
    private AlertDialog passwordDialog;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<SignaturePlacement> signaturePlacements = new ArrayList<>();
    private SignaturePlacement activeDeletePlacement;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(view -> getOnBackPressedDispatcher().onBackPressed());

        pdfView = findViewById(R.id.pdfView);
        pdfContainer = findViewById(R.id.pdf_container);
        signatureOverlay = findViewById(R.id.signature_overlay);

        signatureOverlay.setOnTouchListener(this::onSignatureDragged);

        Uri uri = getIntent().getData();
        if (uri != null) {
            loadPdf(uri, null);
        }
    }

    private void loadPdf(Uri uri, @Nullable String password) {
        pdfPassword = password;
        pdfView.fromUri(uri)
                .password(password)
                .enableDoubletap(false)
                .onPageChange((page, pageCount) -> currentPage = page)
                .onError(error -> onPdfLoadError(uri, password, error))
                .load();
    }

    private void onPdfLoadError(Uri uri, @Nullable String attemptedPassword, Throwable error) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (error instanceof PdfPasswordException) {
            showPasswordPrompt(uri, attemptedPassword != null);
            return;
        }
        Log.w(Config.LOGTAG, "unable to open pdf", error);
        Toast.makeText(this, R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showPasswordPrompt(Uri uri, boolean previousAttemptFailed) {
        View content = getLayoutInflater().inflate(R.layout.dialog_pdf_password, null, false);
        TextInputLayout passwordLayout = content.findViewById(R.id.pdf_password_layout);
        TextInputEditText passwordInput = content.findViewById(R.id.pdf_password);
        if (previousAttemptFailed) {
            passwordLayout.setError(getString(R.string.pdf_password_incorrect));
        }
        passwordDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pdf_password_title)
                .setMessage(R.string.pdf_password_message)
                .setView(content)
                .setPositiveButton(R.string.pdf_password_open, (dialog, which) -> {
                    Editable text = passwordInput.getText();
                    loadPdf(uri, text == null ? "" : text.toString());
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .create();
        passwordInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                passwordDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                return true;
            }
            return false;
        });
        Window window = passwordDialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        passwordDialog.show();
        passwordInput.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (signatureDialog != null) {
            signatureDialog.dismiss();
            signatureDialog = null;
        }
        if (passwordDialog != null) {
            passwordDialog.dismiss();
            passwordDialog = null;
        }
        executor.shutdown();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.pdf_viewer_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        // PdfRenderer can't open an encrypted file below API 35, and the export would drop the
        // protection anyway, so signing stays off for these.
        if ((id == R.id.action_signature || id == R.id.action_share_signed) && pdfPassword != null) {
            Toast.makeText(this, R.string.pdf_signature_protected, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.action_signature) {
            showSignaturePad();
            return true;
        }
        if (id == R.id.action_share_signed) {
            exportAndShare();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSignaturePad() {
        if (signatureDialog != null && signatureDialog.isShowing()) {
            return;
        }
        signatureDialog = new android.app.Dialog(this);
        signatureDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        signatureDialog.setContentView(R.layout.dialog_signature_pad);
        signatureDialog.setCanceledOnTouchOutside(true);
        Window window = signatureDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }

        signatureView = signatureDialog.findViewById(R.id.signature_view);
        signatureTypeInput = signatureDialog.findViewById(R.id.signature_type_input);
        View padClear = signatureDialog.findViewById(R.id.signature_clear);
        View padClose = signatureDialog.findViewById(R.id.signature_close);
        View padUse = signatureDialog.findViewById(R.id.signature_use);
        AutoCompleteTextView colorDropdown = signatureDialog.findViewById(R.id.signature_color_dropdown);

        MaterialButtonToggleGroup modeToggle = signatureDialog.findViewById(R.id.signature_mode_toggle);
        MaterialButton modeDraw = signatureDialog.findViewById(R.id.signature_mode_draw);
        MaterialButton modeType = signatureDialog.findViewById(R.id.signature_mode_type);
        View signatureDrawContainer = signatureDialog.findViewById(R.id.signature_draw_container);
        View signatureTypeContainer = signatureDialog.findViewById(R.id.signature_type_container);
        MaterialCheckBox keepExistingCheckbox = signatureDialog.findViewById(R.id.signature_keep_existing);
        signatureSizeSlider = signatureDialog.findViewById(R.id.signature_size_slider);

        if (signatureView != null) {
            signatureView.clear();
        }
        if (colorDropdown != null) {
            String[] colorOptions = new String[] {
                    getString(R.string.pdf_signature_color_black),
                    getString(R.string.pdf_signature_color_blue),
                    getString(R.string.pdf_signature_color_red),
                    getString(R.string.pdf_signature_color_white)
            };
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_list_item_1,
                    colorOptions
            );
            colorDropdown.setAdapter(adapter);
            colorDropdown.setText(colorOptions[0], false);
            applySignatureColor(colorOptions[0]);
            colorDropdown.setOnItemClickListener((parent, view, position, id) ->
                    applySignatureColor(colorOptions[position]));
        }
        if (signatureSizeSlider != null) {
            signatureSizeSlider.setValue(72f);
            applySignatureSize(signatureSizeSlider.getValue());
            signatureSizeSlider.addOnChangeListener((slider, value, fromUser) -> applySignatureSize(value));
        }

        if (padClear != null) {
            padClear.setOnClickListener(view -> {
                if (signatureDrawContainer != null && signatureDrawContainer.getVisibility() == View.VISIBLE
                        && signatureView != null) {
                    signatureView.clear();
                }
                if (signatureTypeContainer != null && signatureTypeContainer.getVisibility() == View.VISIBLE
                        && signatureTypeInput != null) {
                    signatureTypeInput.setText("");
                }
            });
        }
        if (padUse != null) {
            padUse.setOnClickListener(view -> applySignatureFromPad());
        }
        if (padClose != null) {
            padClose.setOnClickListener(view -> hideSignaturePad());
        }
        if (keepExistingCheckbox != null) {
            keepExistingCheckbox.setChecked(true);
        }
        if (modeToggle != null && modeDraw != null && modeType != null) {
            modeToggle.check(modeDraw.getId());
            modeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked) {
                    return;
                }
                if (checkedId == modeDraw.getId()) {
                    if (signatureDrawContainer != null) {
                        signatureDrawContainer.setVisibility(View.VISIBLE);
                    }
                    if (signatureTypeContainer != null) {
                        signatureTypeContainer.setVisibility(View.GONE);
                    }
                } else if (checkedId == modeType.getId()) {
                    if (signatureDrawContainer != null) {
                        signatureDrawContainer.setVisibility(View.GONE);
                    }
                    if (signatureTypeContainer != null) {
                        signatureTypeContainer.setVisibility(View.VISIBLE);
                    }
                    if (signatureTypeInput != null) {
                        signatureTypeInput.requestFocus();
                    }
                }
            });
        }
        signatureDialog.setOnDismissListener(dialog -> {
            signatureView = null;
            signatureTypeInput = null;
            signatureSizeSlider = null;
        });
        signatureDialog.show();
    }

    private void applySignatureColor(String selection) {
        if (selection == null) {
            return;
        }
        if (selection.equals(getString(R.string.pdf_signature_color_black))) {
            signatureColor = getColor(R.color.signature_ink_black);
        } else if (selection.equals(getString(R.string.pdf_signature_color_blue))) {
            signatureColor = getColor(R.color.signature_ink_blue);
        } else if (selection.equals(getString(R.string.pdf_signature_color_red))) {
            signatureColor = getColor(R.color.signature_ink_red);
        } else if (selection.equals(getString(R.string.pdf_signature_color_white))) {
            signatureColor = getColor(R.color.signature_ink_white);
        }
        if (signatureView != null) {
            signatureView.setStrokeColor(signatureColor);
        }
        if (signatureTypeInput != null) {
            signatureTypeInput.setTextColor(signatureColor);
        }
    }

    private void applySignatureSize(float value) {
        if (signatureSizeSlider == null || signatureView == null) {
            return;
        }
        float min = signatureSizeSlider.getValueFrom();
        float max = signatureSizeSlider.getValueTo();
        float ratio = (value - min) / (max - min);
        ratio = Math.max(0f, Math.min(1f, ratio));
        float strokeWidthDp = 2f + ratio * 6f;
        signatureView.setStrokeWidthDp(strokeWidthDp);
    }

    private void hideSignaturePad() {
        if (signatureDialog != null) {
            signatureDialog.dismiss();
            signatureDialog = null;
        }
    }

    private void applySignatureFromPad() {
        if (signatureDialog == null) {
            return;
        }
        View signatureDrawContainer = signatureDialog.findViewById(R.id.signature_draw_container);
        View signatureTypeContainer = signatureDialog.findViewById(R.id.signature_type_container);
        MaterialCheckBox keepExistingCheckbox = signatureDialog.findViewById(R.id.signature_keep_existing);
        boolean keepExisting = keepExistingCheckbox == null || keepExistingCheckbox.isChecked();
        boolean isTypeMode = signatureTypeContainer != null
                && signatureTypeContainer.getVisibility() == View.VISIBLE;
        boolean isDrawMode = signatureDrawContainer != null
                && signatureDrawContainer.getVisibility() == View.VISIBLE;
        Bitmap signatureBitmap;

        if (isTypeMode) {
            String typedName = signatureTypeInput != null
                    ? signatureTypeInput.getText().toString().trim()
                    : "";
            if (typedName.isEmpty()) {
                Toast.makeText(this, R.string.pdf_signature_missing, Toast.LENGTH_SHORT).show();
                return;
            }
            android.graphics.Typeface typeface = resolveSignatureTypeface();
            int color = resolveSignatureColor();
            float textSizeSp = resolveSignatureTextSize();
            signatureBitmap = createTypedSignatureBitmap(typedName, typeface, color, textSizeSp);
        } else if (isDrawMode) {
            if (signatureView == null || signatureView.isEmpty()) {
                Toast.makeText(this, R.string.pdf_signature_missing, Toast.LENGTH_SHORT).show();
                return;
            }
            signatureBitmap = signatureView.getSignatureBitmap();
        } else {
            Toast.makeText(this, R.string.pdf_signature_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        hideSignaturePad();
        pdfContainer.post(() -> placeSignature(signatureBitmap, keepExisting));
    }

    private int resolveSignatureColor() {
        return signatureColor;
    }

    private Bitmap createTypedSignatureBitmap(String name, android.graphics.Typeface typeface, int color, float textSizeSp) {
        float padding = 20f;
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        float textSizePx = textSizeSp * getResources().getDisplayMetrics().scaledDensity;
        paint.setTextSize(textSizePx);
        paint.setTypeface(typeface);
        float textWidth = paint.measureText(name);
        android.graphics.Paint.FontMetrics metrics = paint.getFontMetrics();
        float textHeight = metrics.bottom - metrics.top;
        int width = Math.max(1, Math.round(textWidth + padding * 2));
        int height = Math.max(1, Math.round(textHeight + padding * 2));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT);
        float x = padding;
        float y = padding - metrics.top;
        canvas.drawText(name, x, y, paint);
        return bitmap;
    }

    private android.graphics.Typeface resolveSignatureTypeface() {

        return android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL);
    }



    private float resolveSignatureTextSize() {
        if (signatureSizeSlider == null) {
            return 72f;
        }
        return signatureSizeSlider.getValue();
    }

    private void placeSignature(Bitmap bitmap, boolean keepExisting) {
        if (bitmap == null || pdfContainer.getWidth() == 0 || pdfContainer.getHeight() == 0) {
            return;
        }
        if (!keepExisting) {
            clearSignatures();
        }
        hideActiveDeleteControl();
        ImageView overlayView = getAvailableOverlayView();
        SignaturePlacement placement = new SignaturePlacement(overlayView, bitmap, currentPage);

        float targetWidth = pdfContainer.getWidth() * 0.4f;
        float aspectRatio = bitmap.getHeight() / (float) bitmap.getWidth();
        int width = Math.max(1, Math.round(targetWidth));
        int height = Math.max(1, Math.round(targetWidth * aspectRatio));
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
        overlayView.setImageBitmap(scaled);
        overlayView.getLayoutParams().width = width;
        overlayView.getLayoutParams().height = height;
        overlayView.requestLayout();
        overlayView.setX((pdfContainer.getWidth() - width) / 2f);
        overlayView.setY((pdfContainer.getHeight() - height) / 2f);
        overlayView.setVisibility(View.VISIBLE);
        overlayView.bringToFront();
        overlayView.setTag(R.id.signature_placement, placement);
        overlayView.setTag(R.id.signature_gesture_detector, null);
        updateSignatureRect(placement, overlayView);
        signaturePlacements.add(placement);
    }

    private ImageView getAvailableOverlayView() {
        if (signatureOverlay != null
                && signatureOverlay.getVisibility() != View.VISIBLE
                && !isOverlayInUse(signatureOverlay)) {
            signatureOverlay.setOnTouchListener(this::onSignatureDragged);
            return signatureOverlay;
        }
        ImageView overlayView = new ImageView(this);
        overlayView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        overlayView.setBackgroundResource(R.drawable.signature_overlay_background);
        overlayView.setContentDescription(getString(R.string.pdf_signature_overlay));
        overlayView.setOnTouchListener(this::onSignatureDragged);
        pdfContainer.addView(overlayView);
        return overlayView;
    }

    private boolean isOverlayInUse(ImageView overlayView) {
        for (SignaturePlacement placement : signaturePlacements) {
            if (placement.overlayView == overlayView) {
                return true;
            }
        }
        return false;
    }

    private void clearSignatures() {
        for (SignaturePlacement placement : signaturePlacements) {
            if (placement.overlayView != signatureOverlay) {
                pdfContainer.removeView(placement.overlayView);
            } else {
                signatureOverlay.setVisibility(View.GONE);
                signatureOverlay.setImageDrawable(null);
            }
            if (placement.deleteView != null) {
                pdfContainer.removeView(placement.deleteView);
            }
        }
        signaturePlacements.clear();
        activeDeletePlacement = null;
    }

    private boolean onSignatureDragged(View view, MotionEvent event) {
        Object placementTag = view.getTag(R.id.signature_placement);
        if (!(placementTag instanceof SignaturePlacement)) {
            return false;
        }
        SignaturePlacement placement = (SignaturePlacement) placementTag;
        android.view.GestureDetector gestureDetector = getSignatureGestureDetector(view, placement);
        boolean handled = gestureDetector.onTouchEvent(event);
        if (handled) {
            return true;
        }
        int[] location = new int[2];
        pdfContainer.getLocationOnScreen(location);
        float containerLeft = location[0];
        float containerTop = location[1];
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                hideDeleteControl(placement);
                view.setTag(R.id.signature_drag_offsets, new float[] {
                        event.getRawX() - containerLeft - view.getX(),
                        event.getRawY() - containerTop - view.getY()
                });
                return true;
            case MotionEvent.ACTION_MOVE:
                float[] offsets = (float[]) view.getTag(R.id.signature_drag_offsets);
                if (offsets == null) {
                    return false;
                }
                float newX = event.getRawX() - containerLeft - offsets[0];
                float newY = event.getRawY() - containerTop - offsets[1];
                newX = clamp(newX, 0, pdfContainer.getWidth() - view.getWidth());
                newY = clamp(newY, 0, pdfContainer.getHeight() - view.getHeight());
                view.setX(newX);
                view.setY(newY);
                updateSignatureRect(placement, view);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                updateSignatureRect(placement, view);
                return true;
            default:
                return false;
        }
    }

    private android.view.GestureDetector getSignatureGestureDetector(View view, SignaturePlacement placement) {
        Object detectorTag = view.getTag(R.id.signature_gesture_detector);
        if (detectorTag instanceof android.view.GestureDetector) {
            return (android.view.GestureDetector) detectorTag;
        }
        android.view.GestureDetector detector = new android.view.GestureDetector(
                this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        showDeleteDialog(placement);
                        return true;
                    }
                });
        view.setTag(R.id.signature_gesture_detector, detector);
        return detector;
    }

    private void showDeleteDialog(SignaturePlacement placement) {
        hideActiveDeleteControl();
        new MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.close_dialog)
                .setTitle(R.string.pdf_signature_delete)
                .setMessage(R.string.pdf_signature_delete_confirm)
                .setPositiveButton(R.string.pdf_signature_delete,
                        (dialog, which) -> removeSignaturePlacement(placement))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void removeSignaturePlacement(SignaturePlacement placement) {
        signaturePlacements.remove(placement);
        if (placement.overlayView == signatureOverlay) {
            signatureOverlay.setVisibility(View.GONE);
            signatureOverlay.setImageDrawable(null);
            signatureOverlay.setTag(R.id.signature_placement, null);
            signatureOverlay.setTag(R.id.signature_gesture_detector, null);
        } else {
            pdfContainer.removeView(placement.overlayView);
        }
        if (placement.deleteView != null) {
            pdfContainer.removeView(placement.deleteView);
        }
        if (activeDeletePlacement == placement) {
            activeDeletePlacement = null;
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateSignatureRect(SignaturePlacement placement, View view) {
        placement.rect = new RectF(
                view.getX(),
                view.getY(),
                view.getX() + view.getWidth(),
                view.getY() + view.getHeight());
        updateDeleteControlPosition(placement);
    }

    private void hideDeleteControl(SignaturePlacement placement) {
        if (placement.deleteView != null) {
            placement.deleteView.setVisibility(View.GONE);
        }
        if (activeDeletePlacement == placement) {
            activeDeletePlacement = null;
        }
    }

    private void hideActiveDeleteControl() {
        if (activeDeletePlacement != null) {
            hideDeleteControl(activeDeletePlacement);
        }
    }

    private void updateDeleteControlPosition(SignaturePlacement placement) {
        if (placement.deleteView == null || placement.deleteView.getVisibility() != View.VISIBLE) {
            return;
        }
        float x = placement.overlayView.getX() + placement.overlayView.getWidth()
                - placement.deleteView.getWidth() * 0.6f;
        float y = placement.overlayView.getY() - placement.deleteView.getHeight() * 0.4f;
        x = clamp(x, 0, pdfContainer.getWidth() - placement.deleteView.getWidth());
        y = clamp(y, 0, pdfContainer.getHeight() - placement.deleteView.getHeight());
        placement.deleteView.setX(x);
        placement.deleteView.setY(y);
        placement.deleteView.bringToFront();
    }

    private void exportAndShare() {
        if (signaturePlacements.isEmpty()) {
            Toast.makeText(this, R.string.pdf_signature_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        Uri sourceUri = getIntent().getData();
        if (sourceUri == null) {
            Toast.makeText(this, R.string.pdf_signature_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        List<SignatureRender> placements = new ArrayList<>();
        for (SignaturePlacement placement : signaturePlacements) {
            if (placement.rect != null) {
                placements.add(new SignatureRender(placement.bitmap, new RectF(placement.rect), placement.pageIndex));
            }
        }
        int containerWidth = pdfContainer.getWidth();
        int containerHeight = pdfContainer.getHeight();
        if (containerWidth == 0 || containerHeight == 0) {
            Toast.makeText(this, R.string.pdf_signature_share_error, Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            try {
                File output = createSignedPdf(sourceUri, placements, containerWidth, containerHeight);
                Uri shareUri = FileBackend.getUriForFile(this, output);
                runOnUiThread(() -> sharePdf(shareUri));
            } catch (IOException | RuntimeException e) {
                // PdfRenderer reports an encrypted or malformed file with a SecurityException or
                // IllegalArgumentException; uncaught on this executor it would kill the process.
                Log.w(Config.LOGTAG, "unable to create signed pdf", e);
                runOnUiThread(() -> Toast.makeText(this, R.string.pdf_signature_share_error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private File createSignedPdf(Uri sourceUri, List<SignatureRender> placements, int containerWidth, int containerHeight)
            throws IOException {
        File output = createSignedOutputFile();
        PdfDocument document = new PdfDocument();
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(sourceUri, "r");) {
            if (pfd == null) {
                throw new IOException("Unable to open PDF.");
            }
            PdfRenderer renderer = new PdfRenderer(pfd);
            for (int i = 0; i < renderer.getPageCount(); i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                for (SignatureRender placement : placements) {
                    if (placement.pageIndex != i || placement.rect == null) {
                        continue;
                    }
                    RectF dest = new RectF(
                            placement.rect.left * page.getWidth() / containerWidth,
                            placement.rect.top * page.getHeight() / containerHeight,
                            placement.rect.right * page.getWidth() / containerWidth,
                            placement.rect.bottom * page.getHeight() / containerHeight);
                    canvas.drawBitmap(placement.bitmap, null, dest, null);
                }
                PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        i + 1).create();
                PdfDocument.Page pdfPage = document.startPage(pageInfo);
                pdfPage.getCanvas().drawBitmap(bitmap, 0, 0, null);
                document.finishPage(pdfPage);
                bitmap.recycle();
                page.close();
            }
            renderer.close();
            try (FileOutputStream outputStream = new FileOutputStream(output)) {
                document.writeTo(outputStream);
            }
        }
        return output;
    }

    private File createSignedOutputFile() throws IOException {
        File outputDir = new File(getFilesDir(), "Files/Signed");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Unable to create output directory.");
        }
        return new File(outputDir, "signed_" + System.currentTimeMillis() + ".pdf");
    }

    private void sharePdf(Uri fileUri) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/pdf");
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.pdf_signature_share)));
    }

    private static class SignaturePlacement {
        private final ImageView overlayView;
        private final Bitmap bitmap;
        private final int pageIndex;
        private RectF rect;
        private ImageView deleteView;

        private SignaturePlacement(ImageView overlayView, Bitmap bitmap, int pageIndex) {
            this.overlayView = overlayView;
            this.bitmap = bitmap;
            this.pageIndex = pageIndex;
        }
    }

    private static class SignatureRender {
        private final Bitmap bitmap;
        private final RectF rect;
        private final int pageIndex;

        private SignatureRender(Bitmap bitmap, RectF rect, int pageIndex) {
            this.bitmap = bitmap;
            this.rect = rect;
            this.pageIndex = pageIndex;
        }
    }
}
