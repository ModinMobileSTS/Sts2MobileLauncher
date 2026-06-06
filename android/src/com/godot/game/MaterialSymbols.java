package com.godot.game;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MenuItem;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.button.MaterialButton;

public final class MaterialSymbols {
	private static Typeface roundedTypeface;

	private MaterialSymbols() {
	}

	public static Drawable drawable(Context context, int iconRes, int tint, int sizeDp) {
		String glyph = glyphForDrawable(iconRes);
		if (glyph == null || glyph.isEmpty()) {
			Drawable fallback = AppCompatResources.getDrawable(context, iconRes);
			if (fallback != null) {
				fallback = fallback.mutate();
				fallback.setTint(tint);
			}
			return fallback;
		}
		return drawable(context, glyph, ColorStateList.valueOf(tint), sizeDp);
	}

	public static Drawable drawable(Context context, String glyph, int tint, int sizeDp) {
		return drawable(context, glyph, ColorStateList.valueOf(tint), sizeDp);
	}

	public static Drawable drawable(Context context, int iconRes, ColorStateList tint, int sizeDp) {
		String glyph = glyphForDrawable(iconRes);
		if (glyph == null || glyph.isEmpty()) {
			Drawable fallback = AppCompatResources.getDrawable(context, iconRes);
			if (fallback != null && tint != null) {
				fallback = fallback.mutate();
				fallback.setTintList(tint);
			}
			return fallback;
		}
		return drawable(context, glyph, tint, sizeDp);
	}

	public static Drawable drawable(Context context, String glyph, ColorStateList tint, int sizeDp) {
		return new MaterialSymbolDrawable(loadTypeface(context), glyph, tint, ExtraSettingsUi.dp(context, sizeDp));
	}

	public static void applyButtonIcon(MaterialButton button, int iconRes, ColorStateList tint, int sizeDp) {
		if (button == null || iconRes == 0) {
			return;
		}
		Drawable icon = drawable(button.getContext(), iconRes, tint, sizeDp);
		button.setIcon(icon);
		button.setIconTint(null);
		button.setIconSize(ExtraSettingsUi.dp(button.getContext(), sizeDp));
	}

	public static void applyButtonIcon(MaterialButton button, String glyph, ColorStateList tint, int sizeDp) {
		if (button == null || glyph == null || glyph.isEmpty()) {
			return;
		}
		Drawable icon = drawable(button.getContext(), glyph, tint, sizeDp);
		button.setIcon(icon);
		button.setIconTint(null);
		button.setIconSize(ExtraSettingsUi.dp(button.getContext(), sizeDp));
	}

	public static void applyMenuIcon(Context context, MenuItem item, int iconRes, int tint, int sizeDp) {
		if (context == null || item == null || iconRes == 0) {
			return;
		}
		item.setIcon(drawable(context, iconRes, tint, sizeDp));
	}

	public static void applyMenuIcon(Context context, MenuItem item, String glyph, int tint, int sizeDp) {
		if (context == null || item == null || glyph == null || glyph.isEmpty()) {
			return;
		}
		item.setIcon(drawable(context, glyph, tint, sizeDp));
	}

	private static Typeface loadTypeface(Context context) {
		if (roundedTypeface == null) {
			try {
				roundedTypeface = ResourcesCompat.getFont(context, R.font.material_symbols_rounded);
			} catch (Exception ignored) {
				roundedTypeface = Typeface.DEFAULT;
			}
			if (roundedTypeface == null) {
				roundedTypeface = Typeface.DEFAULT;
			}
		}
		return roundedTypeface;
	}

	private static String glyphForDrawable(int iconRes) {
		if (iconRes == R.drawable.ic_add_circle_24) return "add_circle";
		if (iconRes == R.drawable.ic_arrow_forward_24) return "arrow_forward";
		if (iconRes == R.drawable.ic_article_24) return "receipt_long";
		if (iconRes == R.drawable.ic_aspect_ratio_24) return "aspect_ratio";
		if (iconRes == R.drawable.ic_auto_awesome_24) return "auto_awesome";
		if (iconRes == R.drawable.ic_badge_24) return "badge";
		if (iconRes == R.drawable.ic_blur_on_24) return "blur_on";
		if (iconRes == R.drawable.ic_bolt_24) return "bolt";
		if (iconRes == R.drawable.ic_build_24) return "build";
		if (iconRes == R.drawable.ic_check_circle_24) return "check_circle";
		if (iconRes == R.drawable.ic_chevron_right_24) return "chevron_right";
		if (iconRes == R.drawable.ic_close_24) return "close";
		if (iconRes == R.drawable.ic_cloud_sync_24) return "cloud_sync";
		if (iconRes == R.drawable.ic_code_24) return "code";
		if (iconRes == R.drawable.ic_compare_arrows_24) return "compare_arrows";
		if (iconRes == R.drawable.ic_controller_24) return "stadia_controller";
		if (iconRes == R.drawable.ic_dashboard_24) return "dashboard";
		if (iconRes == R.drawable.ic_delete_24) return "delete";
		if (iconRes == R.drawable.ic_desktop_windows_24) return "desktop_windows";
		if (iconRes == R.drawable.ic_download_24) return "file_download";
		if (iconRes == R.drawable.ic_drag_indicator_24) return "drag_indicator";
		if (iconRes == R.drawable.ic_edit_24) return "edit";
		if (iconRes == R.drawable.ic_error_outline_24) return "error";
		if (iconRes == R.drawable.ic_expand_less_24) return "expand_less";
		if (iconRes == R.drawable.ic_expand_more_24) return "expand_more";
		if (iconRes == R.drawable.ic_extension_24) return "extension";
		if (iconRes == R.drawable.ic_extra_settings_gear) return "settings";
		if (iconRes == R.drawable.ic_folder_24) return "folder_open";
		if (iconRes == R.drawable.ic_gamepad_24) return "stadia_controller";
		if (iconRes == R.drawable.ic_gesture_24) return "gesture";
		if (iconRes == R.drawable.ic_groups_24) return "groups";
		if (iconRes == R.drawable.ic_high_quality_24) return "high_quality";
		if (iconRes == R.drawable.ic_info_24) return "info";
		if (iconRes == R.drawable.ic_keyboard_24) return "keyboard";
		if (iconRes == R.drawable.ic_layers_24) return "layers";
		if (iconRes == R.drawable.ic_list_24) return "list";
		if (iconRes == R.drawable.ic_lock_open_24) return "lock_open";
		if (iconRes == R.drawable.ic_mood_24) return "mood";
		if (iconRes == R.drawable.ic_more_vert_24) return "more_vert";
		if (iconRes == R.drawable.ic_open_in_new_24) return "open_in_new";
		if (iconRes == R.drawable.ic_person_24) return "person";
		if (iconRes == R.drawable.ic_phone_android_24) return "phone_android";
		if (iconRes == R.drawable.ic_remove_circle_24) return "remove_circle";
		if (iconRes == R.drawable.ic_restart_alt_24) return "restart_alt";
		if (iconRes == R.drawable.ic_rocket_launch_24) return "rocket_launch";
		if (iconRes == R.drawable.ic_save_24) return "save";
		if (iconRes == R.drawable.ic_search_24) return "search";
		if (iconRes == R.drawable.ic_settings_24) return "settings";
		if (iconRes == R.drawable.ic_sort_24) return "sort";
		if (iconRes == R.drawable.ic_speed_24) return "speed";
		if (iconRes == R.drawable.ic_sync_24) return "sync";
		if (iconRes == R.drawable.ic_text_fields_24) return "text_fields";
		if (iconRes == R.drawable.ic_touch_app_24) return "touch_app";
		if (iconRes == R.drawable.ic_tune_24) return "tune";
		if (iconRes == R.drawable.ic_upload_file_24) return "file_upload";
		if (iconRes == R.drawable.ic_volume_up_24) return "volume_up";
		if (iconRes == R.drawable.ic_zoom_in_24) return "zoom_in";
		return null;
	}

	private static final class MaterialSymbolDrawable extends Drawable {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
		private final String glyph;
		private final int intrinsicSize;
		private ColorStateList tint;
		private int currentColor;
		private int alpha = 255;
		private ColorFilter colorFilter;

		MaterialSymbolDrawable(Typeface typeface, String glyph, ColorStateList tint, int intrinsicSize) {
			this.glyph = glyph == null ? "" : glyph;
			this.tint = tint == null ? ColorStateList.valueOf(Color.WHITE) : tint;
			this.intrinsicSize = intrinsicSize;
			this.currentColor = this.tint.getDefaultColor();
			paint.setTypeface(typeface == null ? Typeface.DEFAULT : typeface);
			paint.setTextAlign(Paint.Align.CENTER);
			paint.setFontFeatureSettings("liga");
		}

		@Override
		public void draw(Canvas canvas) {
			Rect bounds = getBounds();
			if (bounds.isEmpty() || glyph.isEmpty()) {
				return;
			}
			float size = Math.min(bounds.width(), bounds.height());
			paint.setTextSize(size * 0.92f);
			paint.setColor(currentColor);
			paint.setAlpha(alpha);
			paint.setColorFilter(colorFilter);
			Paint.FontMetrics metrics = paint.getFontMetrics();
			float x = bounds.exactCenterX();
			float y = bounds.exactCenterY() - (metrics.ascent + metrics.descent) / 2f;
			canvas.drawText(glyph, x, y, paint);
		}

		@Override
		protected boolean onStateChange(int[] state) {
			int nextColor = tint.getColorForState(state, tint.getDefaultColor());
			if (nextColor == currentColor) {
				return false;
			}
			currentColor = nextColor;
			invalidateSelf();
			return true;
		}

		@Override
		public boolean isStateful() {
			return tint != null && tint.isStateful();
		}

		@Override
		public int getIntrinsicWidth() {
			return intrinsicSize;
		}

		@Override
		public int getIntrinsicHeight() {
			return intrinsicSize;
		}

		@Override
		public void setAlpha(int alpha) {
			this.alpha = alpha;
			invalidateSelf();
		}

		@Override
		public void setColorFilter(ColorFilter colorFilter) {
			this.colorFilter = colorFilter;
			invalidateSelf();
		}

		@Override
		public void setTintList(ColorStateList tint) {
			this.tint = tint == null ? ColorStateList.valueOf(Color.WHITE) : tint;
			onStateChange(getState());
			invalidateSelf();
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}
	}
}
