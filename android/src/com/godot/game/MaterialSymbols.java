package com.godot.game;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.MenuItem;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.button.MaterialButton;

public final class MaterialSymbols {
	private MaterialSymbols() {
	}

	public static Drawable drawable(Context context, int iconRes, int tint, int sizeDp) {
		return drawable(context, iconRes, ColorStateList.valueOf(tint), sizeDp);
	}

	public static Drawable drawable(Context context, String glyph, int tint, int sizeDp) {
		return drawable(context, glyph, ColorStateList.valueOf(tint), sizeDp);
	}

	public static Drawable drawable(Context context, int iconRes, ColorStateList tint, int sizeDp) {
		int vectorRes = vectorForDrawable(iconRes);
		return sizedDrawable(context, vectorRes != 0 ? vectorRes : iconRes, tint, sizeDp);
	}

	public static Drawable drawable(Context context, String glyph, ColorStateList tint, int sizeDp) {
		int vectorRes = vectorForGlyph(glyph);
		if (vectorRes == 0) {
			return null;
		}
		return sizedDrawable(context, vectorRes, tint, sizeDp);
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

	private static Drawable sizedDrawable(Context context, int drawableRes, ColorStateList tint, int sizeDp) {
		if (context == null || drawableRes == 0) {
			return null;
		}
		Drawable drawable = AppCompatResources.getDrawable(context, drawableRes);
		if (drawable == null) {
			return null;
		}
		drawable = DrawableCompat.wrap(drawable.mutate());
		if (tint != null) {
			DrawableCompat.setTintList(drawable, tint);
		}
		return new SizedDrawable(drawable, ExtraSettingsUi.dp(context, sizeDp));
	}

	private static int vectorForDrawable(int iconRes) {
		String glyph = glyphForDrawable(iconRes);
		return glyph == null ? 0 : vectorForGlyph(glyph);
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

	private static int vectorForGlyph(String glyph) {
		if ("add_circle".equals(glyph)) return R.drawable.ic_ms_add_circle_24;
		if ("arrow_forward".equals(glyph)) return R.drawable.ic_ms_arrow_forward_24;
		if ("aspect_ratio".equals(glyph)) return R.drawable.ic_ms_aspect_ratio_24;
		if ("auto_awesome".equals(glyph)) return R.drawable.ic_ms_auto_awesome_24;
		if ("badge".equals(glyph)) return R.drawable.ic_ms_badge_24;
		if ("block".equals(glyph)) return R.drawable.ic_ms_block_24;
		if ("blur_on".equals(glyph)) return R.drawable.ic_ms_blur_on_24;
		if ("bolt".equals(glyph)) return R.drawable.ic_ms_bolt_24;
		if ("build".equals(glyph)) return R.drawable.ic_ms_build_24;
		if ("check_circle".equals(glyph)) return R.drawable.ic_ms_check_circle_24;
		if ("chevron_right".equals(glyph)) return R.drawable.ic_ms_chevron_right_24;
		if ("close".equals(glyph)) return R.drawable.ic_ms_close_24;
		if ("cloud_done".equals(glyph)) return R.drawable.ic_ms_cloud_done_24;
		if ("cloud_sync".equals(glyph)) return R.drawable.ic_ms_cloud_sync_24;
		if ("code".equals(glyph)) return R.drawable.ic_ms_code_24;
		if ("compare_arrows".equals(glyph)) return R.drawable.ic_ms_compare_arrows_24;
		if ("dashboard".equals(glyph)) return R.drawable.ic_ms_dashboard_24;
		if ("delete".equals(glyph)) return R.drawable.ic_ms_delete_24;
		if ("desktop_windows".equals(glyph)) return R.drawable.ic_ms_desktop_windows_24;
		if ("drag_indicator".equals(glyph)) return R.drawable.ic_ms_drag_indicator_24;
		if ("edit".equals(glyph)) return R.drawable.ic_ms_edit_24;
		if ("error".equals(glyph)) return R.drawable.ic_ms_error_24;
		if ("expand_less".equals(glyph)) return R.drawable.ic_ms_expand_less_24;
		if ("expand_more".equals(glyph)) return R.drawable.ic_ms_expand_more_24;
		if ("extension".equals(glyph)) return R.drawable.ic_ms_extension_24;
		if ("file_download".equals(glyph)) return R.drawable.ic_ms_file_download_24;
		if ("file_upload".equals(glyph)) return R.drawable.ic_ms_file_upload_24;
		if ("folder_open".equals(glyph)) return R.drawable.ic_ms_folder_open_24;
		if ("gamepad".equals(glyph)) return R.drawable.ic_ms_gamepad_24;
		if ("gesture".equals(glyph)) return R.drawable.ic_ms_gesture_24;
		if ("groups".equals(glyph)) return R.drawable.ic_ms_groups_24;
		if ("high_quality".equals(glyph)) return R.drawable.ic_ms_high_quality_24;
		if ("info".equals(glyph)) return R.drawable.ic_ms_info_24;
		if ("keyboard".equals(glyph)) return R.drawable.ic_ms_keyboard_24;
		if ("layers".equals(glyph)) return R.drawable.ic_ms_layers_24;
		if ("list".equals(glyph)) return R.drawable.ic_ms_list_24;
		if ("lock_open".equals(glyph)) return R.drawable.ic_ms_lock_open_24;
		if ("login".equals(glyph)) return R.drawable.ic_ms_login_24;
		if ("mood".equals(glyph)) return R.drawable.ic_ms_mood_24;
		if ("more_vert".equals(glyph)) return R.drawable.ic_ms_more_vert_24;
		if ("open_in_new".equals(glyph)) return R.drawable.ic_ms_open_in_new_24;
		if ("person".equals(glyph)) return R.drawable.ic_ms_person_24;
		if ("phone_android".equals(glyph)) return R.drawable.ic_ms_phone_android_24;
		if ("play_arrow".equals(glyph)) return R.drawable.ic_ms_play_arrow_24;
		if ("receipt_long".equals(glyph)) return R.drawable.ic_ms_receipt_long_24;
		if ("remove_circle".equals(glyph)) return R.drawable.ic_ms_remove_circle_24;
		if ("restart_alt".equals(glyph)) return R.drawable.ic_ms_restart_alt_24;
		if ("rocket_launch".equals(glyph)) return R.drawable.ic_ms_rocket_launch_24;
		if ("save".equals(glyph)) return R.drawable.ic_ms_save_24;
		if ("search".equals(glyph)) return R.drawable.ic_ms_search_24;
		if ("settings".equals(glyph)) return R.drawable.ic_ms_settings_24;
		if ("sort".equals(glyph)) return R.drawable.ic_ms_sort_24;
		if ("speed".equals(glyph)) return R.drawable.ic_ms_speed_24;
		if ("stadia_controller".equals(glyph)) return R.drawable.ic_ms_stadia_controller_24;
		if ("sync".equals(glyph)) return R.drawable.ic_ms_sync_24;
		if ("text_fields".equals(glyph)) return R.drawable.ic_ms_text_fields_24;
		if ("touch_app".equals(glyph)) return R.drawable.ic_ms_touch_app_24;
		if ("tune".equals(glyph)) return R.drawable.ic_ms_tune_24;
		if ("unarchive".equals(glyph)) return R.drawable.ic_ms_unarchive_24;
		if ("volume_up".equals(glyph)) return R.drawable.ic_ms_volume_up_24;
		if ("zoom_in".equals(glyph)) return R.drawable.ic_ms_zoom_in_24;
		return 0;
	}

	private static final class SizedDrawable extends Drawable implements Drawable.Callback {
		private final Drawable wrapped;
		private final int sizePx;

		SizedDrawable(Drawable wrapped, int sizePx) {
			this.wrapped = wrapped;
			this.sizePx = sizePx;
			wrapped.setCallback(this);
		}

		@Override
		public void draw(Canvas canvas) {
			wrapped.setBounds(getBounds());
			wrapped.draw(canvas);
		}

		@Override
		protected void onBoundsChange(android.graphics.Rect bounds) {
			wrapped.setBounds(bounds);
		}

		@Override
		protected boolean onStateChange(int[] state) {
			return wrapped.setState(state);
		}

		@Override
		public boolean isStateful() {
			return wrapped.isStateful();
		}

		@Override
		public int getIntrinsicWidth() {
			return sizePx;
		}

		@Override
		public int getIntrinsicHeight() {
			return sizePx;
		}

		@Override
		public void setAlpha(int alpha) {
			wrapped.setAlpha(alpha);
		}

		@Override
		public void setColorFilter(ColorFilter colorFilter) {
			wrapped.setColorFilter(colorFilter);
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}

		@Override
		public void invalidateDrawable(Drawable who) {
			invalidateSelf();
		}

		@Override
		public void scheduleDrawable(Drawable who, Runnable what, long when) {
			scheduleSelf(what, when);
		}

		@Override
		public void unscheduleDrawable(Drawable who, Runnable what) {
			unscheduleSelf(what);
		}
	}
}
