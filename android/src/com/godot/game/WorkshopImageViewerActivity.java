package com.godot.game;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/** Full-screen Workshop image surface. The image never leaves the launcher for viewing. */
public final class WorkshopImageViewerActivity extends AppCompatActivity {
    private static final String EXTRA_URL = "image_url";
    private static final int REQUEST_WRITE_IMAGE = 4101;
    private static final int MAX_DECODE_EDGE = 4096;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sts2-workshop-image-viewer");
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        return thread;
    });
    private ZoomableImageView imageView;
    private ProgressBar progress;
    private TextView error;
    private Bitmap bitmap;
    private String imageUrl;
    private boolean pendingSave;
    private volatile Call activeCall;
    private volatile boolean destroyed;

    public static Intent createIntent(Context context, String url) {
        return new Intent(context, WorkshopImageViewerActivity.class).putExtra(EXTRA_URL, url);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SystemBarInsetsHelper.enableEdgeToEdge(this);
        imageUrl = getIntent().getStringExtra(EXTRA_URL);
        if (TextUtils.isEmpty(imageUrl)) {
            finish();
            return;
        }
        setContentView(buildContent());
        imageView.post(this::loadImage);
    }

    private View buildContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(10, 12, 14));

        imageView = new ZoomableImageView(this);
        imageView.setOnLongClickListener(v -> {
            if (bitmap != null) {
                showImageActions();
                return true;
            }
            return false;
        });
        root.addView(imageView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.workshop_image_viewer_title);
        toolbar.setNavigationIcon(R.drawable.ic_close_24);
        toolbar.setNavigationContentDescription(android.R.string.cancel);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setBackgroundColor(Color.argb(210, 10, 12, 14));
        SystemBarInsetsHelper.applySystemBarPadding(toolbar, true, true, false, true);
        FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(toolbar, toolbarParams);

        progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
            ExtraSettingsUi.dp(this, 48), ExtraSettingsUi.dp(this, 48), Gravity.CENTER);
        root.addView(progress, progressParams);

        error = new TextView(this);
        error.setTextColor(Color.WHITE);
        error.setTextSize(15);
        error.setGravity(Gravity.CENTER);
        error.setPadding(ExtraSettingsUi.dp(this, 32), ExtraSettingsUi.dp(this, 16),
            ExtraSettingsUi.dp(this, 32), ExtraSettingsUi.dp(this, 16));
        error.setVisibility(View.GONE);
        root.addView(error, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        SystemBarInsetsHelper.applySystemBarPadding(imageView, false, true, true, true);
        return root;
    }

    private void loadImage() {
        final int targetEdge = Math.min(MAX_DECODE_EDGE,
            Math.max(1024, Math.max(imageView.getWidth(), imageView.getHeight()) * 2));
        final String url = normalizeUrl(imageUrl);
        worker.execute(() -> {
            Bitmap result = null;
            try {
                result = fetchBitmap(url, targetEdge);
            } catch (Exception ignored) {
                // The UI reports the failure without exposing transport details.
            }
            Bitmap loaded = result;
            runOnUiThread(() -> {
                if (destroyed || isFinishing() || isDestroyed()) {
                    if (loaded != null && !loaded.isRecycled()) loaded.recycle();
                    return;
                }
                progress.setVisibility(View.GONE);
                if (loaded == null) {
                    error.setText(getString(R.string.workshop_image_viewer_load_failed));
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                bitmap = loaded;
                imageView.setImageBitmap(loaded);
            });
        });
    }

    private Bitmap fetchBitmap(String url, int targetEdge) throws IOException {
        IOException last = null;
        OkHttpClient[] clients = new OkHttpClient[] {
            buildClient(WorkshopHttpRouteMode.DEFAULT),
            buildClient(WorkshopHttpRouteMode.ORIGINAL_ONLY),
            buildClient(WorkshopHttpRouteMode.DIRECT_ONLY)
        };
        for (OkHttpClient client : clients) {
            try {
                Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android) STS2Workshop/1.0")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", "https://steamcommunity.com/")
                    .build();
                Call call = client.newCall(request);
                activeCall = call;
                try (Response response = call.execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("HTTP " + response.code());
                    }
                    File temporary = File.createTempFile("workshop-viewer-", ".tmp", getCacheDir());
                    try {
                        try (java.io.InputStream input = response.body().byteStream();
                             FileOutputStream output = new FileOutputStream(temporary)) {
                            byte[] buffer = new byte[16 * 1024];
                            int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        }
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(temporary.getPath(), bounds);
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            throw new IOException("Invalid image dimensions");
                        }
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 1;
                        while (bounds.outWidth / (options.inSampleSize * 2) >= targetEdge
                            || bounds.outHeight / (options.inSampleSize * 2) >= targetEdge) {
                            options.inSampleSize *= 2;
                        }
                        Bitmap decoded = BitmapFactory.decodeFile(temporary.getPath(), options);
                        if (decoded == null) throw new IOException("Unable to decode image");
                        return decoded;
                    } finally {
                        // The decoded bitmap owns its pixels; the compressed response does not stay in memory.
                        temporary.delete();
                    }
                } finally {
                    if (activeCall == call) activeCall = null;
                }
            } catch (IOException exception) {
                last = exception;
            } finally {
                client.dispatcher().cancelAll();
                client.connectionPool().evictAll();
            }
        }
        throw last == null ? new IOException("Image request failed") : last;
    }

    private OkHttpClient buildClient(WorkshopHttpRouteMode mode) {
        return SteamWorkshopDirectAccess.INSTANCE.buildClient(this, mode, builder -> {
            builder.connectTimeout(8, TimeUnit.SECONDS);
            builder.readTimeout(20, TimeUnit.SECONDS);
            builder.writeTimeout(8, TimeUnit.SECONDS);
            builder.callTimeout(30, TimeUnit.SECONDS);
            builder.protocols(java.util.Collections.singletonList(Protocol.HTTP_1_1));
            builder.connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS));
            builder.retryOnConnectionFailure(true);
            return kotlin.Unit.INSTANCE;
        });
    }

    private void showImageActions() {
        String[] actions = {
            getString(R.string.workshop_image_viewer_save),
            getString(R.string.workshop_image_viewer_share),
            getString(android.R.string.cancel)
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.workshop_image_viewer_actions)
            .setItems(actions, (dialog, which) -> {
                if (which == 0) saveImage();
                else if (which == 1) shareImage();
            })
            .show();
    }

    private void saveImage() {
        Bitmap source = bitmap;
        if (source == null) return;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
            && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSave = true;
            requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQUEST_WRITE_IMAGE);
            return;
        }
        worker.execute(() -> {
            try {
                writeImageToGallery(source);
                runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.workshop_image_viewer_saved), Toast.LENGTH_SHORT).show());
            } catch (Exception exception) {
                runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.workshop_image_viewer_save_failed), Toast.LENGTH_LONG).show());
            }
        });
    }

    private Uri writeImageToGallery(Bitmap source) throws IOException {
        String name = "sts2-workshop-" + stableName(imageUrl) + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/STS2 Workshop");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Unable to create gallery item");
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null || !source.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                throw new IOException("Unable to encode image");
            }
        } catch (Exception exception) {
            getContentResolver().delete(uri, null, null);
            if (exception instanceof IOException) throw (IOException) exception;
            throw new IOException(exception);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, ready, null, null);
        }
        return uri;
    }

    private void shareImage() {
        Bitmap source = bitmap;
        if (source == null) return;
        worker.execute(() -> {
            File directory = new File(getCacheDir(), "shared");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.workshop_image_viewer_share_failed), Toast.LENGTH_LONG).show());
                return;
            }
            File file = new File(directory, "sts2-workshop-" + stableName(imageUrl) + ".jpg");
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!source.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw new IOException("encode");
                Uri uri = FileProvider.getUriForFile(this,
                    BuildConfig.APPLICATION_ID + ".fileprovider", file);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("image/jpeg");
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.setClipData(ClipData.newRawUri(getString(R.string.workshop_image_viewer_title), uri));
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, getString(R.string.workshop_image_viewer_share)));
                });
            } catch (Exception exception) {
                runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.workshop_image_viewer_share_failed), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_IMAGE && pendingSave) {
            pendingSave = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) saveImage();
            else Toast.makeText(this, R.string.workshop_image_viewer_save_permission, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        Call call = activeCall;
        if (call != null) call.cancel();
        bitmap = null;
        super.onDestroy();
    }

    private static String normalizeUrl(String url) {
        String normalized = url == null ? "" : url.trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\x26", "&")
            .replace("&amp;", "&");
        if (normalized.startsWith("//")) normalized = "https:" + normalized;
        return normalized
            .replace("https://steamcommunity.rmbgame.net/", "https://steamcommunity.com/")
            .replace("http://steamcommunity.rmbgame.net/", "https://steamcommunity.com/")
            .replace("https://steamstore.rmbgame.net/", "https://api.steampowered.com/")
            .replace("http://steamstore.rmbgame.net/", "https://api.steampowered.com/");
    }

    private static String stableName(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) result.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return result.toString();
        } catch (Exception ignored) {
            return Long.toHexString(System.currentTimeMillis());
        }
    }

    private static final class ZoomableImageView extends androidx.appcompat.widget.AppCompatImageView {
        private final Matrix matrix = new Matrix();
        private final float[] values = new float[9];
        private final PointF lastPoint = new PointF();
        private final float touchSlop;
        private float minimumScale = 1f;
        private float currentScale = 1f;
        private boolean dragging;
        private final android.view.ScaleGestureDetector scaleDetector;
        private final Runnable longPress = this::performLongClick;

        ZoomableImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            scaleDetector = new android.view.ScaleGestureDetector(context,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(android.view.ScaleGestureDetector detector) {
                        if (getDrawable() == null) return false;
                        float next = Math.max(minimumScale, Math.min(minimumScale * 5f,
                            currentScale * detector.getScaleFactor()));
                        float factor = next / currentScale;
                        matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                        currentScale = next;
                        constrain();
                        setImageMatrix(matrix);
                        return true;
                    }
                });
        }

        @Override public void setImageBitmap(Bitmap bitmap) {
            super.setImageBitmap(bitmap);
            resetImage();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (getDrawable() != null) resetImage();
        }

        private void resetImage() {
            if (getDrawable() == null || getWidth() <= 0 || getHeight() <= 0) return;
            float drawableWidth = getDrawable().getIntrinsicWidth();
            float drawableHeight = getDrawable().getIntrinsicHeight();
            minimumScale = Math.min(getWidth() / drawableWidth, getHeight() / drawableHeight);
            currentScale = minimumScale;
            matrix.reset();
            matrix.postScale(minimumScale, minimumScale);
            matrix.postTranslate((getWidth() - drawableWidth * minimumScale) / 2f,
                (getHeight() - drawableHeight * minimumScale) / 2f);
            setImageMatrix(matrix);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastPoint.set(event.getX(), event.getY());
                    dragging = true;
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    removeCallbacks(longPress);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress() && event.getPointerCount() == 1 && dragging) {
                        float dx = event.getX() - lastPoint.x;
                        float dy = event.getY() - lastPoint.y;
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                            removeCallbacks(longPress);
                            matrix.postTranslate(dx, dy);
                            constrain();
                            setImageMatrix(matrix);
                            lastPoint.set(event.getX(), event.getY());
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    removeCallbacks(longPress);
                    if (event.getPointerCount() > 2) return true;
                    int remaining = event.getActionIndex() == 0 ? 1 : 0;
                    if (remaining < event.getPointerCount()) lastPoint.set(event.getX(remaining), event.getY(remaining));
                    return true;
                default:
                    return true;
            }
        }

        private void constrain() {
            if (getDrawable() == null) return;
            matrix.getValues(values);
            float scale = values[Matrix.MSCALE_X];
            float width = getDrawable().getIntrinsicWidth() * scale;
            float height = getDrawable().getIntrinsicHeight() * scale;
            float x = values[Matrix.MTRANS_X];
            float y = values[Matrix.MTRANS_Y];
            if (width <= getWidth()) x = (getWidth() - width) / 2f;
            else x = Math.min(0f, Math.max(getWidth() - width, x));
            if (height <= getHeight()) y = (getHeight() - height) / 2f;
            else y = Math.min(0f, Math.max(getHeight() - height, y));
            values[Matrix.MTRANS_X] = x;
            values[Matrix.MTRANS_Y] = y;
            matrix.setValues(values);
        }
    }
}
