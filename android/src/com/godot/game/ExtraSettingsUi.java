package com.godot.game;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.shape.ShapeAppearanceModel;

public final class ExtraSettingsUi {
	public static final int COLOR_BACKGROUND = Color.rgb(15, 17, 23);
	public static final int COLOR_SURFACE = Color.rgb(25, 29, 38);
	public static final int COLOR_SURFACE_CONTAINER = Color.rgb(31, 35, 46);
	public static final int COLOR_SURFACE_VARIANT = Color.rgb(42, 47, 61);
	public static final int COLOR_OUTLINE = Color.rgb(102, 109, 125);
	public static final int COLOR_ON_SURFACE = Color.rgb(241, 244, 250);
	public static final int COLOR_ON_SURFACE_VARIANT = Color.rgb(194, 199, 211);
	public static final int COLOR_MUTED = Color.rgb(143, 150, 165);
	public static final int COLOR_PRIMARY = Color.rgb(166, 211, 183);
	public static final int COLOR_ON_PRIMARY = Color.rgb(0, 56, 30);
	public static final int COLOR_PRIMARY_CONTAINER = Color.rgb(31, 79, 49);
	public static final int COLOR_ON_PRIMARY_CONTAINER = Color.rgb(201, 238, 211);
	public static final int COLOR_SECONDARY_CONTAINER = Color.rgb(53, 58, 71);
	public static final int COLOR_WARNING = Color.rgb(255, 201, 111);
	public static final int COLOR_ERROR = Color.rgb(255, 180, 171);

	private ExtraSettingsUi() {
	}

	public static int dp(Context context, float value) {
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
	}

	public static LinearLayout vertical(Context context) {
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		return layout;
	}

	public static LinearLayout horizontal(Context context) {
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.HORIZONTAL);
		layout.setGravity(Gravity.CENTER_VERTICAL);
		return layout;
	}

	public static TextView title(Context context, int stringRes) {
		TextView view = text(context, stringRes, 28, COLOR_ON_SURFACE, Typeface.BOLD);
		view.setLineSpacing(dp(context, 2), 1.0f);
		return view;
	}

	public static TextView title(Context context, String text) {
		TextView view = text(context, text, 28, COLOR_ON_SURFACE, Typeface.BOLD);
		view.setLineSpacing(dp(context, 2), 1.0f);
		return view;
	}

	public static TextView sectionTitle(Context context, int stringRes) {
		return text(context, stringRes, 18, COLOR_ON_SURFACE, Typeface.BOLD);
	}

	public static TextView sectionTitle(Context context, String value) {
		return text(context, value, 18, COLOR_ON_SURFACE, Typeface.BOLD);
	}

	public static TextView label(Context context, int stringRes) {
		return text(context, stringRes, 15, COLOR_ON_SURFACE, Typeface.BOLD);
	}

	public static TextView body(Context context, int stringRes) {
		TextView view = text(context, stringRes, 14, COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
		view.setLineSpacing(dp(context, 2), 1.0f);
		return view;
	}

	public static TextView body(Context context, String value) {
		TextView view = text(context, value, 14, COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
		view.setLineSpacing(dp(context, 2), 1.0f);
		return view;
	}

	public static TextView caption(Context context, String value) {
		return text(context, value, 12, COLOR_MUTED, Typeface.NORMAL);
	}

	public static TextView text(Context context, int stringRes, float sp, int color, int style) {
		return text(context, context.getString(stringRes), sp, color, style);
	}

	public static TextView text(Context context, String value, float sp, int color, int style) {
		TextView view = new TextView(context);
		view.setText(value);
		view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		view.setTextColor(color);
		view.setTypeface(Typeface.DEFAULT, style);
		return view;
	}

	public static MaterialCardView card(Context context) {
		MaterialCardView card = new MaterialCardView(context);
		card.setCardBackgroundColor(COLOR_SURFACE_CONTAINER);
		card.setStrokeColor(COLOR_OUTLINE);
		card.setStrokeWidth(dp(context, 1));
		card.setRadius(dp(context, 24));
		card.setUseCompatPadding(false);
		card.setClickable(false);
		return card;
	}

	public static MaterialCardView clickableCard(Context context) {
		MaterialCardView card = card(context);
		card.setClickable(true);
		card.setFocusable(true);
		card.setRippleColor(ColorStateList.valueOf(Color.argb(72, 201, 238, 211)));
		applyRipple(card);
		return card;
	}

	public static LinearLayout cardContent(Context context, MaterialCardView card) {
		LinearLayout content = vertical(context);
		int pad = dp(context, 18);
		content.setPadding(pad, pad, pad, pad);
		card.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return content;
	}

	public static void addCardSpacing(LinearLayout parent, View child) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = parent.getChildCount() == 0 ? 0 : dp(parent.getContext(), 14);
		parent.addView(child, params);
	}

	public static void addSmallSpacing(LinearLayout parent, View child) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = parent.getChildCount() == 0 ? 0 : dp(parent.getContext(), 8);
		parent.addView(child, params);
	}

	public static View spacer(Context context, int dp) {
		View view = new View(context);
		view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, dp)));
		return view;
	}

	public static View divider(Context context) {
		View divider = new View(context);
		divider.setBackgroundColor(Color.rgb(55, 61, 76));
		divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
		return divider;
	}

	public static ImageView icon(Context context, int iconRes, int tint, int sizeDp) {
		ImageView image = new ImageView(context);
		image.setImageResource(iconRes);
		image.setColorFilter(tint);
		int size = dp(context, sizeDp);
		image.setLayoutParams(new LinearLayout.LayoutParams(size, size));
		return image;
	}

	public static View iconCircle(Context context, int iconRes, int circleColor, int iconTint) {
		LinearLayout holder = new LinearLayout(context);
		holder.setGravity(Gravity.CENTER);
		GradientDrawable bg = new GradientDrawable();
		bg.setShape(GradientDrawable.OVAL);
		bg.setColor(circleColor);
		holder.setBackground(bg);
		int size = dp(context, 48);
		holder.setLayoutParams(new LinearLayout.LayoutParams(size, size));
		ImageView icon = icon(context, iconRes, iconTint, 26);
		holder.addView(icon);
		return holder;
	}

	public static LinearLayout iconTitleRow(Context context, int iconRes, int titleRes, int subtitleRes, View trailing) {
		LinearLayout row = horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(iconCircle(context, iconRes, COLOR_PRIMARY_CONTAINER, COLOR_ON_PRIMARY_CONTAINER));

		LinearLayout texts = vertical(context);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(dp(context, 14));
		row.addView(texts, textParams);
		texts.addView(sectionTitle(context, titleRes));
		if (subtitleRes != 0) {
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			params.topMargin = dp(context, 4);
			texts.addView(body(context, subtitleRes), params);
		}
		if (trailing != null) {
			row.addView(trailing);
		}
		return row;
	}

	public static MaterialButton iconButton(Context context, int iconRes) {
		MaterialButton button = new MaterialButton(context);
		button.setIconResource(iconRes);
		button.setIconTint(ColorStateList.valueOf(COLOR_ON_SURFACE_VARIANT));
		button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
		button.setText("");
		button.setMinWidth(0);
		button.setMinHeight(0);
		button.setInsetTop(0);
		button.setInsetBottom(0);
		button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
		button.setRippleColor(ColorStateList.valueOf(Color.argb(72, 201, 238, 211)));
		button.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(dp(context, 20)).build());
		button.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
		button.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
		return button;
	}

	public static MaterialButton filledButton(Context context, int textRes, int iconRes) {
		MaterialButton button = new MaterialButton(context);
		button.setText(textRes);
		button.setTextColor(COLOR_ON_PRIMARY);
		button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		button.setBackgroundTintList(ColorStateList.valueOf(COLOR_PRIMARY));
		button.setRippleColor(ColorStateList.valueOf(Color.argb(96, 255, 255, 255)));
		button.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(dp(context, 18)).build());
		button.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
		if (iconRes != 0) {
			button.setIconResource(iconRes);
			button.setIconTint(ColorStateList.valueOf(COLOR_ON_PRIMARY));
		}
		return button;
	}

	public static MaterialButton tonalButton(Context context, int textRes, int iconRes) {
		MaterialButton button = filledButton(context, textRes, iconRes);
		button.setTextColor(COLOR_ON_PRIMARY_CONTAINER);
		if (iconRes != 0) {
			button.setIconTint(ColorStateList.valueOf(COLOR_ON_PRIMARY_CONTAINER));
		}
		button.setBackgroundTintList(ColorStateList.valueOf(COLOR_PRIMARY_CONTAINER));
		return button;
	}

	public static MaterialButton outlineButton(Context context, int textRes, int iconRes) {
		MaterialButton button = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
		button.setText(textRes);
		button.setTextColor(COLOR_PRIMARY);
		button.setStrokeColor(ColorStateList.valueOf(COLOR_PRIMARY));
		button.setStrokeWidth(dp(context, 1));
		button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
		button.setRippleColor(ColorStateList.valueOf(Color.argb(72, 201, 238, 211)));
		button.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(dp(context, 18)).build());
		if (iconRes != 0) {
			button.setIconResource(iconRes);
			button.setIconTint(ColorStateList.valueOf(COLOR_PRIMARY));
		}
		return button;
	}

	public static MaterialCardView choiceCard(Context context, int iconRes, int titleRes, int subtitleRes, boolean selected) {
		MaterialCardView card = clickableCard(context);
		card.setCheckable(true);
		LinearLayout content = cardContent(context, card);
		content.setGravity(Gravity.CENTER_HORIZONTAL);
		content.addView(iconCircle(context, iconRes, selected ? COLOR_PRIMARY : COLOR_SECONDARY_CONTAINER, selected ? COLOR_ON_PRIMARY : COLOR_ON_SURFACE_VARIANT));
		TextView title = sectionTitle(context, titleRes);
		title.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		titleParams.topMargin = dp(context, 12);
		content.addView(title, titleParams);
		if (subtitleRes != 0) {
			TextView body = body(context, subtitleRes);
			body.setGravity(Gravity.CENTER);
			LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			bodyParams.topMargin = dp(context, 6);
			content.addView(body, bodyParams);
		}
		setChoiceSelected(card, selected);
		return card;
	}

	public static void setChoiceSelected(MaterialCardView card, boolean selected) {
		card.setChecked(selected);
		card.setStrokeWidth(dp(card.getContext(), selected ? 2 : 1));
		card.setStrokeColor(selected ? COLOR_PRIMARY : COLOR_OUTLINE);
		card.setCardBackgroundColor(selected ? Color.rgb(30, 50, 39) : COLOR_SURFACE_CONTAINER);
	}

	public static void addWeightedCardsRow(Context context, LinearLayout parent, MaterialCardView... cards) {
		LinearLayout row = horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		for (int i = 0; i < cards.length; i++) {
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			if (i > 0) {
				params.setMarginStart(dp(context, 10));
			}
			row.addView(cards[i], params);
		}
		addSmallSpacing(parent, row);
	}

	public static void applyRipple(View view) {
		TypedValue out = new TypedValue();
		if (view.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true) && out.resourceId != 0) {
			view.setForeground(AppCompatResources.getDrawable(view.getContext(), out.resourceId));
		}
	}

	public static void showInfoDialog(Context context, int titleRes, int messageRes) {
		new MaterialAlertDialogBuilder(context)
			.setTitle(titleRes)
			.setMessage(messageRes)
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}
}
