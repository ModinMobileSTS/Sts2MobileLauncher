package com.godot.game;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.PopupMenu;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ModsPage {
	private static final int FILTER_GROUP_ID = 2000;
	private static final int FILTER_ALL_ID = 2001;
	private static final int FILTER_ENABLED_ID = 2002;
	private static final int FILTER_DISABLED_ID = 2003;
	private static final int FILTER_LIBRARIES_ID = 2004;
	private static final int FILTER_MISSING_ID = 2005;

	private static final int PROFILE_APPLY_GROUP_ID = 3000;
	private static final int PROFILE_CREATE_ID = 3001;
	private static final int PROFILE_DELETE_ACTIVE_ID = 3002;
	private static final int PROFILE_ITEM_BASE_ID = 3100;

	private static final String MOD_GROUP_UNGROUPED = "__root__";
	private static final String MOD_GROUP_CORE = "core";
	private static final String MOD_GROUP_CONTENT = "content";

	private static final int SORT_GROUP_ID = 5000;
	private static final int SORT_INSTALLED_ID = 5001;
	private static final int SORT_NAME_ID = 5002;

	private static final String SORT_INSTALLED = "installed";
	private static final String SORT_NAME = "name";
	private static final int TAG_EXPANDED_STATE = 0x53544D45;
	private static int retainedChipScrollX;

	private final Context context;
	private final ExtraSettingsRepository repository;
	private final ExtraSettingsActions actions;

	private final Set<String> selectedModIds = new HashSet<>();
	private final Set<String> expandedModIds = new HashSet<>();
	private final Set<String> fullDescriptionModIds = new HashSet<>();
	private final Set<String> collapsedGroupIds = new HashSet<>();
	private final List<ExtraSettingsRepository.ModEntry> currentFilteredMods = new ArrayList<>();
	private final Map<String, ModGroupBucket> renderedBuckets = new LinkedHashMap<>();
	private ScrollView scrollView;
	private LinearLayout rootContent;
	private LinearLayout listContainer;
	private MaterialCardView bottomPanelCard;
	private LinearLayout bottomPanelContent;
	private EditText searchInput;
	private HorizontalScrollView chipScrollView;
	private int chipScrollX;
	private boolean suppressChipScrollCapture;
	private String filter = "all";
	private String sortMode = SORT_INSTALLED;
	private boolean bottomPanelVisible;
	private boolean bottomPanelCollapsed;
	private View activeDropGhost;
	private LinearLayout activeDropList;
	private int activeDropIndex = -1;

	public ModsPage(Context context, ExtraSettingsRepository repository, ExtraSettingsActions actions) {
		this.context = context;
		this.repository = repository;
		this.actions = actions;
		this.chipScrollX = retainedChipScrollX;
	}

	public View build() {
		FrameLayout frame = new FrameLayout(context);
		frame.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		scrollView = new ScrollView(context);
		scrollView.setFillViewport(false);
		scrollView.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		rootContent = ExtraSettingsUi.vertical(context);
		rootContent.setPadding(0, ExtraSettingsUi.dp(context, 10), 0, ExtraSettingsUi.dp(context, ExtraSettingsUi.isWideLayout(context) ? 96 : 122));
		if (ExtraSettingsUi.isWideLayout(context)) {
			ExtraSettingsUi.addResponsiveScrollContent(context, scrollView, rootContent);
		} else {
			scrollView.addView(rootContent, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		}

		rootContent.addView(buildTopBar());
		rootContent.addView(buildCompactActions());

		listContainer = ExtraSettingsUi.vertical(context);
		listContainer.setOnDragListener((view, event) -> handleGroupReorderDrag(event));
		int horizontalPadding = ExtraSettingsUi.dp(context, 16);
		listContainer.setPadding(horizontalPadding, ExtraSettingsUi.dp(context, 12), horizontalPadding, 0);
		rootContent.addView(listContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		frame.addView(scrollView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		frame.addView(buildBottomPanel(), bottomPanelParams());
		refreshList();
		return frame;
	}

	private View buildTopBar() {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(ExtraSettingsUi.dp(context, 78));
		row.setPadding(ExtraSettingsUi.dp(context, 16), ExtraSettingsUi.dp(context, 18), ExtraSettingsUi.dp(context, 16), ExtraSettingsUi.dp(context, 14));
		TextView title = ExtraSettingsUi.text(context, context.getString(R.string.tab_mods), 22, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setSingleLine(true);
		title.setEllipsize(TextUtils.TruncateAt.END);
		row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		try {
			JSONObject settings = repository.loadSettingsJson();
			TextView label = ExtraSettingsUi.text(context, context.getString(R.string.mod_master_switch), 16, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
			label.setSingleLine(true);
			label.setEllipsize(TextUtils.TruncateAt.END);
			LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			labelParams.setMarginStart(ExtraSettingsUi.dp(context, 12));
			labelParams.setMarginEnd(ExtraSettingsUi.dp(context, 8));
			row.addView(label, labelParams);
			MaterialSwitch master = new MaterialSwitch(context);
			master.setChecked(repository.isModLoadingEnabled(settings));
			master.setMinWidth(ExtraSettingsUi.dp(context, 52));
			master.setScaleX(1.08f);
			master.setScaleY(1.08f);
			master.setOnCheckedChangeListener((buttonView, isChecked) -> {
				try {
					repository.saveSetting(root -> repository.ensureModSettings(root).put("mods_enabled", isChecked));
				} catch (Exception exception) {
					buttonView.setChecked(!isChecked);
					actions.showError(exception);
				}
			});
			row.addView(master);
		} catch (Exception exception) {
			actions.showError(exception);
		}
		return row;
	}

	private View buildCompactActions() {
		LinearLayout section = ExtraSettingsUi.vertical(context);
		section.setPadding(ExtraSettingsUi.dp(context, 16), 0, ExtraSettingsUi.dp(context, 16), ExtraSettingsUi.dp(context, 12));
		section.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		LinearLayout searchBar = ExtraSettingsUi.horizontal(context);
		searchBar.setGravity(Gravity.CENTER_VERTICAL);
		GradientDrawable searchBackground = new GradientDrawable();
		searchBackground.setColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		searchBackground.setCornerRadius(ExtraSettingsUi.dp(context, 999));
		searchBar.setBackground(searchBackground);
		searchBar.setPadding(ExtraSettingsUi.dp(context, 16), 0, ExtraSettingsUi.dp(context, 12), 0);
		searchBar.addView(ExtraSettingsUi.icon(context, R.drawable.ic_search_24, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 22));

		searchInput = new EditText(context);
		searchInput.setSingleLine(true);
		searchInput.setHint(R.string.mod_search_hint);
		searchInput.setHintTextColor(ExtraSettingsUi.COLOR_MUTED);
		searchInput.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
		searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
		searchInput.setBackgroundColor(Color.TRANSPARENT);
		searchInput.setPadding(ExtraSettingsUi.dp(context, 8), 0, 0, 0);
		searchInput.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) { refreshList(); }
			@Override public void afterTextChanged(Editable s) {}
		});
		searchBar.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
		section.addView(searchBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 48)));

		chipScrollView = new HorizontalScrollView(context);
		chipScrollView.setHorizontalScrollBarEnabled(false);
		chipScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
		LinearLayout chips = ExtraSettingsUi.horizontal(context);
		chips.setGravity(Gravity.CENTER_VERTICAL);
		chips.setPadding(0, 0, ExtraSettingsUi.dp(context, 16), 0);
		chipScrollView.addView(chips, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		int restoreChipScrollX = retainedChipScrollX;

		chips.addView(actionChip(R.string.import_mod, R.drawable.ic_upload_file_24, v -> actions.requestImportMod()));
		addChipGap(chips);
		chips.addView(actionChip(R.string.mod_group_create, R.drawable.ic_folder_24, v -> showCreateGroupDialog()));
		addChipGap(chips);
		chips.addView(actionChip(R.string.mod_profiles_title, R.drawable.ic_layers_24, this::showProfilesMenu));
		addChipGap(chips);
		chips.addView(actionChip(R.string.mod_sort_menu_title, R.drawable.ic_sort_24, this::showSortMenu));
		addChipGap(chips);
		chips.addView(actionChip(R.string.mod_filter_menu_title, R.drawable.ic_tune_24, this::showFilterMenu));

		LinearLayout.LayoutParams chipScrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		chipScrollParams.topMargin = ExtraSettingsUi.dp(context, 12);
		section.addView(chipScrollView, chipScrollParams);
		chipScrollView.post(() -> {
			restoreChipScrollNow(restoreChipScrollX);
			chipScrollView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
				if (suppressChipScrollCapture) {
					return;
				}
				chipScrollX = scrollX;
				retainedChipScrollX = scrollX;
			});
		});

		View divider = new View(context);
		divider.setBackgroundColor(Color.rgb(68, 71, 78));
		LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 1));
		dividerParams.topMargin = ExtraSettingsUi.dp(context, 12);
		section.addView(divider, dividerParams);
		return section;
	}

	private void rememberChipScroll() {
		if (chipScrollView != null && !suppressChipScrollCapture) {
			chipScrollX = chipScrollView.getScrollX();
			retainedChipScrollX = chipScrollX;
		}
	}

	private void restoreChipScrollNow(int scrollX) {
		if (chipScrollView == null) {
			return;
		}
		suppressChipScrollCapture = true;
		chipScrollX = scrollX;
		retainedChipScrollX = scrollX;
		chipScrollView.setScrollX(scrollX);
		chipScrollView.post(() -> {
			if (chipScrollView != null) {
				chipScrollView.setScrollX(scrollX);
			}
			suppressChipScrollCapture = false;
		});
	}

	private void restoreRememberedChipScroll() {
		restoreChipScrollNow(retainedChipScrollX);
	}

	private MaterialButton actionChip(int textRes, int iconRes, java.util.function.Consumer<View> action) {
		MaterialButton chip = new MaterialButton(context);
		chip.setText(textRes);
		chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		chip.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		chip.setBackgroundTintList(ColorStateList.valueOf(ExtraSettingsUi.COLOR_SECONDARY_CONTAINER));
		chip.setRippleColor(ColorStateList.valueOf(Color.argb(72, 201, 238, 211)));
		chip.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(ExtraSettingsUi.dp(context, 8)).build());
		chip.setMinHeight(0);
		chip.setMinWidth(0);
		chip.setInsetTop(0);
		chip.setInsetBottom(0);
		chip.setPadding(ExtraSettingsUi.dp(context, 14), ExtraSettingsUi.dp(context, 7), ExtraSettingsUi.dp(context, 14), ExtraSettingsUi.dp(context, 7));
		if (iconRes != 0) {
			MaterialSymbols.applyButtonIcon(chip, iconRes, ColorStateList.valueOf(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT), 18);
		}
		chip.setOnClickListener(v -> {
			rememberChipScroll();
			action.accept(v);
			restoreRememberedChipScroll();
		});
		return chip;
	}

	private void addChipGap(LinearLayout chips) {
		View gap = new View(context);
		chips.addView(gap, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 8), 1));
	}

	private View buildBottomPanel() {
		bottomPanelCard = ExtraSettingsUi.card(context);
		bottomPanelCard.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		bottomPanelCard.setStrokeColor(ExtraSettingsUi.COLOR_PRIMARY_CONTAINER);
		bottomPanelCard.setStrokeWidth(ExtraSettingsUi.dp(context, 1));
		bottomPanelCard.setAlpha(0f);
		bottomPanelCard.setTranslationY(ExtraSettingsUi.dp(context, 72));
		bottomPanelContent = ExtraSettingsUi.cardContent(context, bottomPanelCard);
		bottomPanelCard.setVisibility(View.GONE);
		return bottomPanelCard;
	}

	private LayoutTransition createSmoothLayoutTransition() {
		LayoutTransition transition = new LayoutTransition();
		transition.setAnimator(LayoutTransition.APPEARING, null);
		transition.setAnimator(LayoutTransition.DISAPPEARING, null);
		transition.setDuration(180);
		return transition;
	}

	private FrameLayout.LayoutParams bottomPanelParams() {
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		int horizontal = ExtraSettingsUi.dp(context, ExtraSettingsUi.isWideLayout(context) ? 28 : 10);
		params.setMargins(horizontal, 0, horizontal, ExtraSettingsUi.isWideLayout(context) ? ExtraSettingsUi.dp(context, 12) : 0);
		return params;
	}

	private void showFilterMenu(View anchor) {
		rememberChipScroll();
		PopupMenu popup = new PopupMenu(context, anchor);
		popup.setForceShowIcon(true);
		popup.setOnDismissListener(menuPopup -> restoreRememberedChipScroll());
		Menu menu = popup.getMenu();
		addFilterMenuItem(menu, FILTER_ALL_ID, R.string.mod_filter_all, R.drawable.ic_layers_24, "all");
		addFilterMenuItem(menu, FILTER_ENABLED_ID, R.string.mod_filter_enabled, R.drawable.ic_check_circle_24, "enabled");
		addFilterMenuItem(menu, FILTER_DISABLED_ID, R.string.mod_filter_disabled, R.drawable.ic_remove_circle_24, "disabled");
		addFilterMenuItem(menu, FILTER_LIBRARIES_ID, R.string.mod_filter_libraries, R.drawable.ic_code_24, "libraries");
		addFilterMenuItem(menu, FILTER_MISSING_ID, R.string.mod_filter_missing_files, R.drawable.ic_error_outline_24, "missing");
		menu.setGroupCheckable(FILTER_GROUP_ID, true, true);
		popup.setOnMenuItemClickListener(item -> {
			String value = filterValueForItem(item.getItemId());
			if (value == null) {
				return false;
			}
			item.setChecked(true);
			setFilter(value);
			return true;
		});
		popup.show();
	}

	private void addFilterMenuItem(Menu menu, int itemId, int titleRes, int iconRes, String value) {
		MenuItem item = menu.add(FILTER_GROUP_ID, itemId, Menu.NONE, titleRes);
		MaterialSymbols.applyMenuIcon(context, item, iconRes, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 24);
		item.setCheckable(true);
		item.setChecked(value.equals(filter));
	}

	private String filterValueForItem(int itemId) {
		if (itemId == FILTER_ENABLED_ID) {
			return "enabled";
		}
		if (itemId == FILTER_DISABLED_ID) {
			return "disabled";
		}
		if (itemId == FILTER_LIBRARIES_ID) {
			return "libraries";
		}
		if (itemId == FILTER_MISSING_ID) {
			return "missing";
		}
		if (itemId == FILTER_ALL_ID) {
			return "all";
		}
		return null;
	}

	private void showSortMenu(View anchor) {
		rememberChipScroll();
		PopupMenu popup = new PopupMenu(context, anchor);
		popup.setForceShowIcon(true);
		popup.setOnDismissListener(menuPopup -> restoreRememberedChipScroll());
		Menu menu = popup.getMenu();
		addSortMenuItem(menu, SORT_INSTALLED_ID, R.string.mod_sort_installed, R.drawable.ic_download_24, SORT_INSTALLED);
		addSortMenuItem(menu, SORT_NAME_ID, R.string.mod_sort_name, R.drawable.ic_text_fields_24, SORT_NAME);
		menu.setGroupCheckable(SORT_GROUP_ID, true, true);
		popup.setOnMenuItemClickListener(item -> {
			String value = sortValueForItem(item.getItemId());
			if (value == null) {
				return false;
			}
			item.setChecked(true);
			sortMode = value;
			rememberChipScroll();
			refreshList();
			return true;
		});
		popup.show();
	}

	private void addSortMenuItem(Menu menu, int itemId, int titleRes, int iconRes, String value) {
		MenuItem item = menu.add(SORT_GROUP_ID, itemId, Menu.NONE, titleRes);
		MaterialSymbols.applyMenuIcon(context, item, iconRes, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 24);
		item.setCheckable(true);
		item.setChecked(value.equals(sortMode));
	}

	private String sortValueForItem(int itemId) {
		if (itemId == SORT_NAME_ID) {
			return SORT_NAME;
		}
		if (itemId == SORT_INSTALLED_ID) {
			return SORT_INSTALLED;
		}
		return null;
	}

	private void showProfilesMenu(View anchor) {
		try {
			JSONObject settings = repository.loadSettingsJson();
			List<ExtraSettingsRepository.ModEntry> mods = repository.listInstalledModManifests();
			ExtraSettingsRepository.ModProfileState state = repository.loadModProfileState(settings, mods);
			Map<Integer, ExtraSettingsRepository.ModProfile> profileItems = new LinkedHashMap<>();
			ExtraSettingsRepository.ModProfile activeProfile = null;
			rememberChipScroll();
			PopupMenu popup = new PopupMenu(context, anchor);
			popup.setForceShowIcon(true);
			popup.setOnDismissListener(menuPopup -> restoreRememberedChipScroll());
			Menu menu = popup.getMenu();
			MaterialSymbols.applyMenuIcon(context, menu.add(Menu.NONE, PROFILE_CREATE_ID, 0, R.string.mod_profile_save_current), R.drawable.ic_add_circle_24, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 24);
			for (int i = 0; i < state.profiles.size(); i++) {
				ExtraSettingsRepository.ModProfile profile = state.profiles.get(i);
				boolean active = state.activeProfileId.equals(profile.id);
				if (active) {
					activeProfile = profile;
				}
				int itemId = PROFILE_ITEM_BASE_ID + i;
				MenuItem item = menu.add(PROFILE_APPLY_GROUP_ID, itemId, i + 10, profile.name);
				MaterialSymbols.applyMenuIcon(context, item, active ? R.drawable.ic_check_circle_24 : R.drawable.ic_layers_24, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 24);
				item.setCheckable(true);
				item.setChecked(active);
				profileItems.put(itemId, profile);
			}
			menu.setGroupCheckable(PROFILE_APPLY_GROUP_ID, true, true);
			if (activeProfile != null && !"default".equals(activeProfile.id)) {
				MaterialSymbols.applyMenuIcon(context, menu.add(Menu.NONE, PROFILE_DELETE_ACTIVE_ID, 1000, R.string.mod_profile_delete_active), R.drawable.ic_delete_24, ExtraSettingsUi.COLOR_ERROR, 24);
			}
			ExtraSettingsRepository.ModProfile finalActiveProfile = activeProfile;
			popup.setOnMenuItemClickListener(item -> {
				if (item.getItemId() == PROFILE_CREATE_ID) {
					showCreateProfileDialog();
					return true;
				}
				if (item.getItemId() == PROFILE_DELETE_ACTIVE_ID && finalActiveProfile != null) {
					confirmDeleteProfile(finalActiveProfile);
					return true;
				}
				ExtraSettingsRepository.ModProfile profile = profileItems.get(item.getItemId());
				if (profile != null) {
					applyProfile(profile.id);
					return true;
				}
				return false;
			});
			popup.show();
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private void applyProfile(String profileId) {
		try {
			repository.applyModProfile(profileId);
			selectedModIds.clear();
			rememberChipScroll();
			refreshList();
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private void setFilter(String value) {
		filter = value;
		selectedModIds.clear();
		rememberChipScroll();
		refreshList();
	}

	private void updateSelectionActionsPanel() {
		if (bottomPanelCard == null || bottomPanelContent == null) {
			return;
		}
		bottomPanelContent.removeAllViews();
		if (selectedModIds.isEmpty()) {
			bottomPanelCollapsed = false;
			hideBottomPanel();
			return;
		}
		showBottomPanel();
		LinearLayout handleRow = ExtraSettingsUi.horizontal(context);
		handleRow.setGravity(Gravity.CENTER);
		MaterialButton collapseToggle = ExtraSettingsUi.iconButton(context, bottomPanelCollapsed ? R.drawable.ic_expand_less_24 : R.drawable.ic_expand_more_24);
		collapseToggle.setContentDescription(context.getString(bottomPanelCollapsed ? R.string.mod_selection_panel_expand : R.string.mod_selection_panel_collapse));
		collapseToggle.setOnClickListener(v -> {
			bottomPanelCollapsed = !bottomPanelCollapsed;
			updateSelectionActionsPanel();
		});
		handleRow.addView(collapseToggle, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 52), ExtraSettingsUi.dp(context, 30)));
		bottomPanelContent.addView(handleRow);
		TextView selectedTitle = ExtraSettingsUi.sectionTitle(context, context.getString(R.string.mod_selected_count_format, selectedModIds.size()));
		if (bottomPanelCollapsed) {
			selectedTitle.setGravity(Gravity.CENTER);
			ExtraSettingsUi.addSmallSpacing(bottomPanelContent, selectedTitle);
			return;
		}
		ExtraSettingsUi.addSmallSpacing(bottomPanelContent, selectedTitle);

		LinearLayout selectionRow = ExtraSettingsUi.horizontal(context);
		MaterialButton selectRange = ExtraSettingsUi.outlineButton(context, R.string.mod_select_range, R.drawable.ic_list_24);
		MaterialButton invert = ExtraSettingsUi.outlineButton(context, R.string.mod_invert_selection, R.drawable.ic_compare_arrows_24);
		MaterialButton selectAll = ExtraSettingsUi.outlineButton(context, R.string.mod_select_all, R.drawable.ic_check_circle_24);
		selectRange.setOnClickListener(v -> selectRangeBetweenSelected());
		invert.setOnClickListener(v -> invertVisibleSelection());
		selectAll.setOnClickListener(v -> selectAllVisibleMods());
		selectionRow.addView(selectRange, weightedButtonParams(0));
		selectionRow.addView(invert, weightedButtonParams(8));
		selectionRow.addView(selectAll, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(bottomPanelContent, selectionRow);

		LinearLayout batchRow = ExtraSettingsUi.horizontal(context);
		MaterialButton enableSelected = ExtraSettingsUi.tonalButton(context, R.string.mod_batch_enable, R.drawable.ic_check_circle_24);
		MaterialButton disableSelected = ExtraSettingsUi.outlineButton(context, R.string.mod_batch_disable, R.drawable.ic_remove_circle_24);
		MaterialButton deleteSelected = ExtraSettingsUi.outlineButton(context, R.string.mod_batch_delete, R.drawable.ic_delete_24);
		enableSelected.setOnClickListener(v -> batchSetEnabled(true));
		disableSelected.setOnClickListener(v -> batchSetEnabled(false));
		deleteSelected.setOnClickListener(v -> confirmBatchDelete());
		batchRow.addView(enableSelected, weightedButtonParams(0));
		batchRow.addView(disableSelected, weightedButtonParams(8));
		batchRow.addView(deleteSelected, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(bottomPanelContent, batchRow);

		MaterialButton clearSelection = ExtraSettingsUi.outlineButton(context, R.string.mod_clear_selection, R.drawable.ic_close_24);
		clearSelection.setOnClickListener(v -> {
			selectedModIds.clear();
			refreshList();
		});
		ExtraSettingsUi.addSmallSpacing(bottomPanelContent, clearSelection);
	}

	private void showBottomPanel() {
		if (bottomPanelCard == null) {
			return;
		}
		bottomPanelCard.bringToFront();
		if (bottomPanelVisible) {
			return;
		}
		bottomPanelVisible = true;
		bottomPanelCard.animate().cancel();
		bottomPanelCard.setVisibility(View.VISIBLE);
		bottomPanelCard.setAlpha(0f);
		bottomPanelCard.setTranslationY(ExtraSettingsUi.dp(context, 72));
		bottomPanelCard.animate().alpha(1f).translationY(0f).setDuration(180).start();
	}

	private void hideBottomPanel() {
		if (bottomPanelCard == null || !bottomPanelVisible) {
			if (bottomPanelCard != null) {
				bottomPanelCard.setVisibility(View.GONE);
			}
			return;
		}
		bottomPanelVisible = false;
		bottomPanelCard.animate().cancel();
		bottomPanelCard.animate()
			.alpha(0f)
			.translationY(ExtraSettingsUi.dp(context, 72))
			.setDuration(160)
			.withEndAction(() -> {
				if (!bottomPanelVisible) {
					bottomPanelCard.setVisibility(View.GONE);
				}
			})
			.start();
	}

	private LinearLayout.LayoutParams weightedButtonParams(int marginStartDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(context, marginStartDp));
		return params;
	}

	private void refreshList() {
		if (listContainer == null) {
			updateSelectionActionsPanel();
			return;
		}
		int scrollY = scrollView == null ? 0 : scrollView.getScrollY();
		listContainer.removeAllViews();
		renderedBuckets.clear();
		currentFilteredMods.clear();
		try {
			JSONObject settings = repository.loadSettingsJson();
			List<ExtraSettingsRepository.ModEntry> allMods = repository.listInstalledModManifests();
			List<ExtraSettingsRepository.ModEntry> filtered = filterMods(settings, allMods);
			sortMods(filtered);
			currentFilteredMods.addAll(filtered);
			Set<String> installedIds = new HashSet<>();
			for (ExtraSettingsRepository.ModEntry entry : allMods) {
				installedIds.add(entry.modId);
			}
			selectedModIds.retainAll(installedIds);
			expandedModIds.retainAll(installedIds);
			if (allMods.isEmpty()) {
				ExtraSettingsUi.addCardSpacing(listContainer, emptyCard(R.string.status_no_mods));
				updateSelectionActionsPanel();
				return;
			}
			if (filtered.isEmpty()) {
				ExtraSettingsUi.addCardSpacing(listContainer, emptyCard(R.string.mod_no_filter_results));
				updateSelectionActionsPanel();
				return;
			}
			for (ModGroupBucket bucket : buildModGroups(filtered)) {
				if (bucket.entries.isEmpty() && !bucket.userCreated) {
					continue;
				}
				addGroupSpacing(listContainer, buildGroupView(settings, bucket));
			}
			updateSelectionActionsPanel();
		} catch (Exception exception) {
			ExtraSettingsUi.addCardSpacing(listContainer, errorCard(exception));
			updateSelectionActionsPanel();
		} finally {
			restoreScrollY(scrollY);
		}
	}

	private void restoreScrollY(int scrollY) {
		if (scrollView == null || scrollY <= 0) {
			return;
		}
		scrollView.post(() -> scrollView.setScrollY(scrollY));
	}

	private List<ExtraSettingsRepository.ModEntry> filterMods(JSONObject settings, List<ExtraSettingsRepository.ModEntry> mods) throws Exception {
		String query = searchInput == null || searchInput.getText() == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
		List<ExtraSettingsRepository.ModEntry> result = new ArrayList<>();
		for (ExtraSettingsRepository.ModEntry entry : mods) {
			boolean enabled = !repository.isModDisabled(settings, entry);
			boolean missingFiles = isMissingPayload(entry);
			if ("enabled".equals(filter) && !enabled) {
				continue;
			}
			if ("disabled".equals(filter) && enabled) {
				continue;
			}
			if ("libraries".equals(filter) && !isLibraryLike(entry)) {
				continue;
			}
			if ("missing".equals(filter) && !missingFiles) {
				continue;
			}
			String haystack = (entry.displayName + " " + entry.modId + " " + entry.pckName + " " + entry.version + " " + entry.authors + " " + entry.description + " " + entry.category + " " + entry.relativePath + " " + TextUtils.join(" ", entry.dependencies)).toLowerCase(Locale.ROOT);
			if (!query.isEmpty() && !haystack.contains(query)) {
				continue;
			}
			result.add(entry);
		}
		return result;
	}

	private void sortMods(List<ExtraSettingsRepository.ModEntry> mods) {
		if (SORT_NAME.equals(sortMode)) {
			mods.sort(Comparator.comparing(entry -> entry.displayName == null ? "" : entry.displayName, String::compareToIgnoreCase));
			return;
		}
		mods.sort((first, second) -> Long.compare(second.manifestFile.lastModified(), first.manifestFile.lastModified()));
	}

	private void sortGroupsBySavedOrder(List<ModGroupBucket> buckets) {
		List<String> order = repository.loadModGroupOrder();
		if (order.isEmpty()) {
			return;
		}
		buckets.sort((first, second) -> Integer.compare(orderIndex(order, first.id), orderIndex(order, second.id)));
	}

	private void sortBucketEntriesBySavedOrder(ModGroupBucket bucket) {
		List<String> order = repository.loadModOrder(bucket.id);
		if (order.isEmpty()) {
			return;
		}
		bucket.entries.sort((first, second) -> Integer.compare(orderIndex(order, first.modId), orderIndex(order, second.modId)));
	}

	private int orderIndex(List<String> order, String value) {
		int index = order.indexOf(value);
		return index < 0 ? Integer.MAX_VALUE : index;
	}

	private List<ModGroupBucket> buildModGroups(List<ExtraSettingsRepository.ModEntry> mods) {
		LinkedHashMap<String, ModGroupBucket> groups = new LinkedHashMap<>();
		List<String> userGroups = repository.listModGroups();
		Set<String> userGroupNames = new LinkedHashSet<>(userGroups);
		putGroup(groups, MOD_GROUP_CORE, context.getString(R.string.mod_group_core), false);
		putGroup(groups, MOD_GROUP_CONTENT, context.getString(R.string.mod_group_content), false);
		for (String groupName : userGroups) {
			String groupId = normalizeGroupId(groupName);
			String label = groupLabel(groupId, groupName);
			putGroup(groups, groupId, label, true);
		}
		for (ExtraSettingsRepository.ModEntry entry : mods) {
			String groupId = groupIdForEntry(entry, userGroupNames);
			ModGroupBucket bucket = groups.get(groupId);
			if (bucket == null) {
				bucket = putGroup(groups, groupId, groupLabel(groupId, groupId), true);
			}
			bucket.entries.add(entry);
		}
		List<ModGroupBucket> buckets = new ArrayList<>(groups.values());
		for (ModGroupBucket bucket : buckets) {
			sortBucketEntriesBySavedOrder(bucket);
		}
		sortGroupsBySavedOrder(buckets);
		return buckets;
	}

	private ModGroupBucket putGroup(LinkedHashMap<String, ModGroupBucket> groups, String id, String label, boolean userCreated) {
		ModGroupBucket bucket = groups.get(id);
		if (bucket == null) {
			bucket = new ModGroupBucket(id, label, userCreated);
			groups.put(id, bucket);
		} else if (userCreated) {
			bucket.userCreated = true;
		}
		return bucket;
	}

	private String groupIdForEntry(ExtraSettingsRepository.ModEntry entry, Set<String> userGroupNames) {
		String top = topLevelDirectory(entry.relativePath);
		if (!TextUtils.isEmpty(top)) {
			String normalizedTop = normalizeGroupId(top);
			if (MOD_GROUP_CORE.equals(normalizedTop) || MOD_GROUP_CONTENT.equals(normalizedTop) || userGroupNames.contains(top)) {
				return normalizedTop;
			}
		}
		return isLibraryLike(entry) ? MOD_GROUP_CORE : MOD_GROUP_CONTENT;
	}

	private String normalizeGroupId(String value) {
		if (TextUtils.isEmpty(value)) {
			return MOD_GROUP_UNGROUPED;
		}
		String trimmed = value.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if ("core".equals(lower) || "libraries".equals(lower) || "library".equals(lower)) {
			return MOD_GROUP_CORE;
		}
		if ("content".equals(lower)) {
			return MOD_GROUP_CONTENT;
		}
		return trimmed;
	}

	private String groupLabel(String groupId, String fallback) {
		if (MOD_GROUP_CORE.equals(groupId)) {
			return context.getString(R.string.mod_group_core);
		}
		if (MOD_GROUP_CONTENT.equals(groupId)) {
			return context.getString(R.string.mod_group_content);
		}
		if (MOD_GROUP_UNGROUPED.equals(groupId)) {
			return context.getString(R.string.mod_group_ungrouped);
		}
		return fallback;
	}

	private String topLevelDirectory(String relativePath) {
		if (TextUtils.isEmpty(relativePath)) {
			return "";
		}
		String normalized = relativePath.replace('\\', '/');
		int slash = normalized.indexOf('/');
		if (slash <= 0) {
			return "";
		}
		return normalized.substring(0, slash);
	}

	private View buildGroupView(JSONObject settings, ModGroupBucket bucket) throws Exception {
		renderedBuckets.put(bucket.id, bucket);
		LinearLayout group = ExtraSettingsUi.vertical(context);
		group.setTag("group:" + bucket.id);
		boolean collapsed = collapsedGroupIds.contains(bucket.id);

		LinearLayout header = ExtraSettingsUi.horizontal(context);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 8), ExtraSettingsUi.dp(context, 0), ExtraSettingsUi.dp(context, 8));
		TextView title = ExtraSettingsUi.text(context, context.getString(R.string.mod_group_header_format, bucket.label, bucket.entries.size()), 14, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, Typeface.BOLD);
		header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		MaterialButton expand = ExtraSettingsUi.iconButton(context, collapsed ? R.drawable.ic_expand_more_24 : R.drawable.ic_expand_less_24);
		expand.setContentDescription(context.getString(collapsed ? R.string.mod_group_expand : R.string.mod_group_collapse));
		header.addView(expand, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 40), ExtraSettingsUi.dp(context, 40)));
		group.addView(header);

		LinearLayout groupList = ExtraSettingsUi.vertical(context);
		groupList.setTag("group_list:" + bucket.id);
		groupList.setLayoutTransition(createSmoothLayoutTransition());
		groupList.setMinimumHeight(ExtraSettingsUi.dp(context, 18));
		attachGroupDragTarget(groupList, bucket);
		for (ExtraSettingsRepository.ModEntry entry : bucket.entries) {
			addModItemSpacing(groupList, modCard(settings, bucket, entry));
		}
		groupList.setVisibility(collapsed ? View.GONE : View.VISIBLE);
		group.addView(groupList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		expand.setOnClickListener(v -> toggleGroupCollapsed(group, groupList, expand, bucket.id));
		header.setOnLongClickListener(v -> startGroupDrag(v, group, bucket));
		return group;
	}

	private void toggleGroupCollapsed(ViewGroup transitionRoot, View groupList, MaterialButton expand, String groupId) {
		boolean collapsed;
		if (collapsedGroupIds.contains(groupId)) {
			collapsedGroupIds.remove(groupId);
			collapsed = false;
		} else {
			collapsedGroupIds.add(groupId);
			collapsed = true;
		}
		AutoTransition transition = new AutoTransition();
		transition.setDuration(220);
		TransitionManager.beginDelayedTransition(transitionRoot, transition);
		groupList.setVisibility(collapsed ? View.GONE : View.VISIBLE);
		MaterialSymbols.applyButtonIcon(expand, collapsed ? R.drawable.ic_expand_more_24 : R.drawable.ic_expand_less_24, ColorStateList.valueOf(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT), 24);
	}

	private void addGroupSpacing(LinearLayout parent, View child) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = parent.getChildCount() == 0 ? 0 : ExtraSettingsUi.dp(context, 16);
		parent.addView(child, params);
	}

	private void addModItemSpacing(LinearLayout parent, View child) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = parent.getChildCount() == 0 ? 0 : ExtraSettingsUi.dp(context, 8);
		parent.addView(child, params);
	}

	private void attachGroupDragTarget(View target, ModGroupBucket bucket) {
		target.setOnDragListener((view, event) -> handleModDragOverGroupList((LinearLayout) view, bucket, event, event.getY()));
	}

	private boolean handleModDragOverGroupList(LinearLayout targetList, ModGroupBucket bucket, DragEvent event, float yInList) {
		DragState state = asDragState(event.getLocalState());
		if (state == null || state.type != DragState.TYPE_MOD || !selectedModIds.isEmpty()) {
			return event.getAction() == DragEvent.ACTION_DRAG_STARTED;
		}
		switch (event.getAction()) {
			case DragEvent.ACTION_DRAG_STARTED:
				return true;
			case DragEvent.ACTION_DRAG_LOCATION:
			case DragEvent.ACTION_DRAG_ENTERED:
				showModDropGhost(targetList, bucket, calculateDropIndex(targetList, yInList));
				return true;
			case DragEvent.ACTION_DRAG_EXITED:
				return true;
			case DragEvent.ACTION_DROP:
				int index = activeDropList == targetList ? activeDropIndex : calculateDropIndex(targetList, yInList);
				removeDropGhosts(rootContent);
				moveModToGroup(state.entry, state.sourceBucket, bucket, index);
				return true;
			case DragEvent.ACTION_DRAG_ENDED:
				removeDropGhosts(rootContent);
				return true;
			default:
				return true;
		}
	}

	private boolean handleGroupReorderDrag(DragEvent event) {
		DragState state = asDragState(event.getLocalState());
		if (state != null && state.type == DragState.TYPE_MOD && !selectedModIds.isEmpty()) {
			return event.getAction() == DragEvent.ACTION_DRAG_STARTED;
		}
		if (state != null && state.type == DragState.TYPE_MOD) {
			ModGroupBucket bucket = findBucketByListY(event.getY());
			LinearLayout targetList = bucket == null ? null : findGroupListView(bucket.id);
			if (targetList != null) {
				float yInList = event.getY() - targetList.getTop();
				View parent = targetList;
				while (parent.getParent() instanceof View && parent.getParent() != listContainer) {
					parent = (View) parent.getParent();
					yInList -= parent.getTop();
				}
				return handleModDragOverGroupList(targetList, bucket, event, yInList);
			}
		}
		if (state == null || state.type != DragState.TYPE_GROUP || !selectedModIds.isEmpty()) {
			return event.getAction() == DragEvent.ACTION_DRAG_STARTED;
		}
		switch (event.getAction()) {
			case DragEvent.ACTION_DRAG_STARTED:
				return true;
			case DragEvent.ACTION_DRAG_LOCATION:
			case DragEvent.ACTION_DRAG_ENTERED:
				showGroupDropGhost(calculateDropIndex(listContainer, event.getY()));
				return true;
			case DragEvent.ACTION_DROP:
				int index = activeDropList == listContainer ? activeDropIndex : calculateDropIndex(listContainer, event.getY());
				removeDropGhosts(rootContent);
				reorderGroup(state.sourceBucket, index);
				return true;
			case DragEvent.ACTION_DRAG_ENDED:
				removeDropGhosts(rootContent);
				return true;
			default:
				return true;
		}
	}

	private DragState asDragState(Object localState) {
		return localState instanceof DragState ? (DragState) localState : null;
	}

	private ModGroupBucket findBucketByListY(float y) {
		for (int i = 0; i < listContainer.getChildCount(); i++) {
			View groupView = listContainer.getChildAt(i);
			Object tag = groupView.getTag();
			if (!(tag instanceof String) || !((String) tag).startsWith("group:")) {
				continue;
			}
			if (y >= groupView.getTop() && y <= groupView.getBottom()) {
				return renderedBuckets.get(((String) tag).substring("group:".length()));
			}
		}
		return null;
	}

	private LinearLayout findGroupListView(String groupId) {
		for (int i = 0; i < listContainer.getChildCount(); i++) {
			View groupView = listContainer.getChildAt(i);
			Object tag = groupView.getTag();
			if (!(tag instanceof String) || !("group:" + groupId).equals(tag) || !(groupView instanceof ViewGroup)) {
				continue;
			}
			ViewGroup group = (ViewGroup) groupView;
			for (int j = 0; j < group.getChildCount(); j++) {
				View child = group.getChildAt(j);
				Object childTag = child.getTag();
				if (child instanceof LinearLayout && ("group_list:" + groupId).equals(childTag)) {
					return (LinearLayout) child;
				}
			}
		}
		return null;
	}

	private int calculateDropIndex(LinearLayout list, float y) {
		int index = 0;
		for (int i = 0; i < list.getChildCount(); i++) {
			View child = list.getChildAt(i);
			if ("mod_drop_ghost".equals(child.getTag())) {
				continue;
			}
			if (y > child.getTop() + child.getHeight() / 2f) {
				index++;
			}
		}
		return Math.max(0, Math.min(index, childCountWithoutGhost(list)));
	}

	private int childCountWithoutGhost(LinearLayout list) {
		int count = 0;
		for (int i = 0; i < list.getChildCount(); i++) {
			if (!"mod_drop_ghost".equals(list.getChildAt(i).getTag())) {
				count++;
			}
		}
		return count;
	}

	private void showModDropGhost(LinearLayout targetList, ModGroupBucket bucket, int logicalIndex) {
		if (activeDropList == targetList && activeDropIndex == logicalIndex && activeDropGhost != null) {
			return;
		}
		removeDropGhosts(rootContent);
		activeDropList = targetList;
		activeDropIndex = logicalIndex;
		activeDropGhost = createDropGhost(56);
		targetList.addView(activeDropGhost, physicalIndexForLogicalIndex(targetList, logicalIndex), ghostParams(56));
	}

	private void showGroupDropGhost(int logicalIndex) {
		if (activeDropList == listContainer && activeDropIndex == logicalIndex && activeDropGhost != null) {
			return;
		}
		removeDropGhosts(rootContent);
		activeDropList = listContainer;
		activeDropIndex = logicalIndex;
		activeDropGhost = createDropGhost(68);
		listContainer.addView(activeDropGhost, physicalIndexForLogicalIndex(listContainer, logicalIndex), ghostParams(68));
	}

	private int physicalIndexForLogicalIndex(LinearLayout list, int logicalIndex) {
		int logical = 0;
		for (int i = 0; i < list.getChildCount(); i++) {
			if ("mod_drop_ghost".equals(list.getChildAt(i).getTag())) {
				continue;
			}
			if (logical == logicalIndex) {
				return i;
			}
			logical++;
		}
		return list.getChildCount();
	}

	private View createDropGhost(int heightDp) {
		View ghost = new View(context);
		ghost.setTag("mod_drop_ghost");
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(Color.argb(92, Color.red(ExtraSettingsUi.COLOR_PRIMARY_CONTAINER), Color.green(ExtraSettingsUi.COLOR_PRIMARY_CONTAINER), Color.blue(ExtraSettingsUi.COLOR_PRIMARY_CONTAINER)));
		bg.setCornerRadius(ExtraSettingsUi.dp(context, 12));
		bg.setStroke(ExtraSettingsUi.dp(context, 1), ExtraSettingsUi.COLOR_PRIMARY, ExtraSettingsUi.dp(context, 5), ExtraSettingsUi.dp(context, 4));
		ghost.setBackground(bg);
		ghost.setAlpha(0.55f);
		return ghost;
	}

	private LinearLayout.LayoutParams ghostParams(int heightDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, heightDp));
		params.topMargin = ExtraSettingsUi.dp(context, 8);
		return params;
	}

	private void removeDropGhosts(View view) {
		if (!(view instanceof ViewGroup)) {
			return;
		}
		ViewGroup group = (ViewGroup) view;
		for (int i = group.getChildCount() - 1; i >= 0; i--) {
			View child = group.getChildAt(i);
			if ("mod_drop_ghost".equals(child.getTag())) {
				group.removeViewAt(i);
			} else {
				removeDropGhosts(child);
			}
		}
		activeDropGhost = null;
		activeDropList = null;
		activeDropIndex = -1;
	}

	private void moveModToGroup(ExtraSettingsRepository.ModEntry entry, ModGroupBucket sourceBucket, ModGroupBucket targetBucket, int targetIndex) {
		if (entry == null || targetBucket == null) {
			return;
		}
		int adjustedTargetIndex = targetIndex;
		if (sourceBucket != null && sourceBucket.id.equals(targetBucket.id)) {
			for (int i = 0; i < targetBucket.entries.size(); i++) {
				if (targetBucket.entries.get(i).modId.equals(entry.modId)) {
					if (i < adjustedTargetIndex) {
						adjustedTargetIndex--;
					}
					break;
				}
			}
		}
		List<String> targetOrder = new ArrayList<>();
		for (ExtraSettingsRepository.ModEntry mod : targetBucket.entries) {
			if (!mod.modId.equals(entry.modId)) {
				targetOrder.add(mod.modId);
			}
		}
		int clamped = Math.max(0, Math.min(adjustedTargetIndex, targetOrder.size()));
		targetOrder.add(clamped, entry.modId);
		repository.saveModOrder(targetBucket.id, targetOrder);
		if (sourceBucket != null && !sourceBucket.id.equals(targetBucket.id)) {
			List<String> sourceOrder = new ArrayList<>();
			for (ExtraSettingsRepository.ModEntry mod : sourceBucket.entries) {
				if (!mod.modId.equals(entry.modId)) {
					sourceOrder.add(mod.modId);
				}
			}
			repository.saveModOrder(sourceBucket.id, sourceOrder);
		}
		String targetGroup = targetBucket.id;
		actions.runAsyncOperation(context.getString(R.string.status_busy_move_mod_group), () -> {
			if (sourceBucket == null || !sourceBucket.id.equals(targetBucket.id)) {
				repository.moveModToGroup(entry, targetGroup);
			}
			return context.getString(R.string.status_move_mod_group_done, targetBucket.label);
		});
	}

	private void reorderGroup(ModGroupBucket movedBucket, int targetIndex) {
		if (movedBucket == null) {
			return;
		}
		int adjustedTargetIndex = targetIndex;
		List<String> currentOrder = new ArrayList<>();
		for (int i = 0; i < listContainer.getChildCount(); i++) {
			View child = listContainer.getChildAt(i);
			Object tag = child.getTag();
			if (tag instanceof String && ((String) tag).startsWith("group:")) {
				currentOrder.add(((String) tag).substring("group:".length()));
			}
		}
		int oldIndex = currentOrder.indexOf(movedBucket.id);
		if (oldIndex >= 0 && oldIndex < adjustedTargetIndex) {
			adjustedTargetIndex--;
		}
		List<String> order = new ArrayList<>();
		for (int i = 0; i < listContainer.getChildCount(); i++) {
			View child = listContainer.getChildAt(i);
			Object tag = child.getTag();
			if (tag instanceof String && ((String) tag).startsWith("group:")) {
				String id = ((String) tag).substring("group:".length());
				if (!id.equals(movedBucket.id)) {
					order.add(id);
				}
			}
		}
		int clamped = Math.max(0, Math.min(adjustedTargetIndex, order.size()));
		order.add(clamped, movedBucket.id);
		repository.saveModGroupOrder(order);
		refreshList();
	}

	private View modCard(JSONObject settings, ModGroupBucket bucket, ExtraSettingsRepository.ModEntry entry) throws Exception {
		boolean enabled = !repository.isModDisabled(settings, entry);
		final boolean[] enabledState = new boolean[] { enabled };
		boolean selected = selectedModIds.contains(entry.modId);
		boolean expanded = expandedModIds.contains(entry.modId);
		MaterialCardView card = ExtraSettingsUi.clickableCard(context);
		card.setRadius(ExtraSettingsUi.dp(context, 12));
		card.setUseCompatPadding(false);
		applyModCardStyle(card, enabled, selected);

		LinearLayout content = ExtraSettingsUi.vertical(context);
		content.setPadding(ExtraSettingsUi.dp(context, 12), 0, ExtraSettingsUi.dp(context, 12), 0);
		card.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		LinearLayout header = ExtraSettingsUi.horizontal(context);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(0, ExtraSettingsUi.dp(context, 12), 0, ExtraSettingsUi.dp(context, 12));

		ImageView handle = ExtraSettingsUi.icon(context, R.drawable.ic_drag_indicator_24, ExtraSettingsUi.COLOR_OUTLINE, 24);
		handle.setPadding(ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 4));
		LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 32), ExtraSettingsUi.dp(context, 40));
		header.addView(handle, handleParams);

		LinearLayout textColumn = ExtraSettingsUi.vertical(context);
		textColumn.setGravity(Gravity.CENTER_VERTICAL);
		TextView title = ExtraSettingsUi.text(context, emptyToDash(entry.displayName), 16, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setSingleLine(true);
		title.setEllipsize(TextUtils.TruncateAt.END);
		textColumn.addView(title);
		TextView meta = ExtraSettingsUi.caption(context, compactMeta(entry));
		meta.setSingleLine(true);
		meta.setEllipsize(TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		metaParams.topMargin = ExtraSettingsUi.dp(context, 2);
		textColumn.addView(meta, metaParams);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 12));
		header.addView(textColumn, textParams);

		MaterialSwitch switchView = new MaterialSwitch(context);
		switchView.setChecked(enabled);
		switchView.setEnabled(selectedModIds.isEmpty());
		switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
			try {
				repository.saveSetting(root -> repository.setModDisabled(root, entry.modId, !isChecked));
				enabledState[0] = isChecked;
				applyModCardStyle(card, isChecked, selectedModIds.contains(entry.modId));
			} catch (Exception exception) {
				buttonView.setChecked(!isChecked);
				actions.showError(exception);
			}
		});
		LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		switchParams.setMarginStart(ExtraSettingsUi.dp(context, 10));
		header.addView(switchView, switchParams);
		content.addView(header);

		LinearLayout details = buildExpandedDetails(card, entry, enabledState);
		content.addView(details);
		setDetailsExpandedImmediately(details, expanded);

		View.OnClickListener itemClick = v -> {
			if (!selectedModIds.isEmpty()) {
				toggleSelected(entry.modId);
			} else {
				toggleCardExpanded(content, details, entry.modId);
			}
		};
		card.setOnClickListener(itemClick);
		handle.setOnLongClickListener(v -> startModDrag(v, content, details, card, bucket, entry));
		handle.setOnClickListener(v -> {
			if (!selectedModIds.isEmpty()) {
				toggleSelected(entry.modId);
			}
		});
		card.setOnLongClickListener(v -> {
			toggleSelected(entry.modId);
			return true;
		});
		return card;
	}

	private LinearLayout buildExpandedDetails(MaterialCardView card, ExtraSettingsRepository.ModEntry entry, boolean[] enabledState) {
		LinearLayout wrapper = ExtraSettingsUi.vertical(context);
		wrapper.setPadding(0, 0, 0, ExtraSettingsUi.dp(context, 16));
		DashedDivider divider = new DashedDivider(context);
		wrapper.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 1)));

		LinearLayout details = ExtraSettingsUi.vertical(context);
		details.setPadding(ExtraSettingsUi.dp(context, 36), ExtraSettingsUi.dp(context, 8), 0, 0);
		TextView description = ExtraSettingsUi.text(context, TextUtils.isEmpty(entry.description) ? context.getString(R.string.mod_description_empty) : entry.description, 13, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
		description.setLineSpacing(ExtraSettingsUi.dp(context, 2), 1.0f);
		boolean longDescription = isLongDescription(entry.description);
		boolean showFullDescription = fullDescriptionModIds.contains(entry.modId);
		if (longDescription && !showFullDescription) {
			description.setMaxLines(10);
			description.setEllipsize(TextUtils.TruncateAt.END);
		}
		details.addView(description);
		if (longDescription && !showFullDescription) {
			TextView more = ExtraSettingsUi.text(context, context.getString(R.string.mod_description_show_more), 13, ExtraSettingsUi.COLOR_PRIMARY, Typeface.BOLD);
			more.setPadding(0, ExtraSettingsUi.dp(context, 6), 0, 0);
			more.setOnClickListener(v -> {
				fullDescriptionModIds.add(entry.modId);
				animateHeightMutation(wrapper, () -> {
					description.setMaxLines(Integer.MAX_VALUE);
					description.setEllipsize(null);
					more.setVisibility(View.GONE);
				});
			});
			details.addView(more);
		}
		addDetailRow(details, R.drawable.ic_code_24, displayCategory(entry));
		addDetailRow(details, R.drawable.ic_article_24, entry.relativePath);
		addDetailRow(details, R.drawable.ic_person_24, context.getString(R.string.mod_detail_author) + ": " + emptyToDash(entry.authors));
		addDetailRow(details, R.drawable.ic_layers_24, context.getString(R.string.mod_detail_dependencies) + ": " + dependenciesText(entry));

		LinearLayout actionsRow = ExtraSettingsUi.horizontal(context);
		actionsRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
		MaterialButton select = detailIconButton(R.drawable.ic_check_circle_24, selectedModIds.contains(entry.modId) ? R.string.mod_action_unselect : R.string.mod_action_select);
		MaterialButton info = detailIconButton(R.drawable.ic_info_24, R.string.mod_action_info);
		MaterialButton delete = detailIconButton(R.drawable.ic_delete_24, R.string.delete);
		select.setOnClickListener(v -> toggleSelected(entry.modId));
		info.setOnClickListener(v -> showModDetails(entry, enabledState[0]));
		delete.setOnClickListener(v -> confirmDelete(entry));
		actionsRow.addView(select);
		actionsRow.addView(info);
		actionsRow.addView(delete);
		LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		actionParams.topMargin = ExtraSettingsUi.dp(context, 12);
		details.addView(actionsRow, actionParams);
		wrapper.addView(details);
		return wrapper;
	}

	private void toggleCardExpanded(ViewGroup transitionRoot, View details, String modId) {
		boolean expanded;
		if (expandedModIds.contains(modId)) {
			expandedModIds.remove(modId);
			expanded = false;
		} else {
			expandedModIds.add(modId);
			expanded = true;
		}
		animateDetailsVisibility(details, expanded);
	}

	private void setDetailsExpandedImmediately(View details, boolean expanded) {
		cancelDetailsAnimation(details);
		ViewGroup.LayoutParams params = details.getLayoutParams();
		if (params != null) {
			params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
			details.setLayoutParams(params);
		}
		details.setVisibility(expanded ? View.VISIBLE : View.GONE);
	}

	private void animateDetailsVisibility(View details, boolean expanded) {
		cancelDetailsAnimation(details);
		int startHeight;
		int endHeight;
		if (expanded) {
			details.setVisibility(View.VISIBLE);
			endHeight = measureWrapContentHeight(details);
			startHeight = 0;
			ViewGroup.LayoutParams params = details.getLayoutParams();
			if (params != null) {
				params.height = 0;
				details.setLayoutParams(params);
			}
		} else {
			startHeight = details.getHeight();
			if (startHeight <= 0) {
				startHeight = measureWrapContentHeight(details);
			}
			endHeight = 0;
		}
		ValueAnimator animator = ValueAnimator.ofInt(startHeight, endHeight);
		animator.setDuration(220);
		animator.addUpdateListener(animation -> {
			ViewGroup.LayoutParams params = details.getLayoutParams();
			if (params != null) {
				params.height = (Integer) animation.getAnimatedValue();
				details.setLayoutParams(params);
			}
		});
		animator.addListener(new android.animation.AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(android.animation.Animator animation) {
				if (details.getTag(TAG_EXPANDED_STATE) != animator) {
					return;
				}
				details.setTag(TAG_EXPANDED_STATE, null);
				ViewGroup.LayoutParams params = details.getLayoutParams();
				if (params != null) {
					params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
					details.setLayoutParams(params);
				}
				details.setVisibility(expanded ? View.VISIBLE : View.GONE);
			}
		});
		details.setTag(TAG_EXPANDED_STATE, animator);
		animator.start();
	}

	private void cancelDetailsAnimation(View details) {
		Object tag = details.getTag(TAG_EXPANDED_STATE);
		if (tag instanceof ValueAnimator) {
			((ValueAnimator) tag).cancel();
			details.setTag(TAG_EXPANDED_STATE, null);
		}
	}

	private int measureWrapContentHeight(View view) {
		View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
		int width = parent == null ? view.getWidth() : parent.getWidth();
		if (width <= 0 && scrollView != null) {
			width = scrollView.getWidth() - ExtraSettingsUi.dp(context, 32);
		}
		if (width <= 0) {
			width = context.getResources().getDisplayMetrics().widthPixels - ExtraSettingsUi.dp(context, 32);
		}
		int widthSpec = View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY);
		int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
		view.measure(widthSpec, heightSpec);
		return Math.max(1, view.getMeasuredHeight());
	}

	private void animateHeightMutation(View container, Runnable mutation) {
		cancelDetailsAnimation(container);
		int startHeight = container.getHeight() > 0 ? container.getHeight() : measureWrapContentHeight(container);
		mutation.run();
		int endHeight = measureWrapContentHeight(container);
		ViewGroup.LayoutParams params = container.getLayoutParams();
		if (params != null) {
			params.height = startHeight;
			container.setLayoutParams(params);
		}
		ValueAnimator animator = ValueAnimator.ofInt(startHeight, endHeight);
		animator.setDuration(220);
		animator.addUpdateListener(animation -> {
			ViewGroup.LayoutParams updateParams = container.getLayoutParams();
			if (updateParams != null) {
				updateParams.height = (Integer) animation.getAnimatedValue();
				container.setLayoutParams(updateParams);
			}
		});
		animator.addListener(new android.animation.AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(android.animation.Animator animation) {
				if (container.getTag(TAG_EXPANDED_STATE) != animator) {
					return;
				}
				container.setTag(TAG_EXPANDED_STATE, null);
				ViewGroup.LayoutParams endParams = container.getLayoutParams();
				if (endParams != null) {
					endParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
					container.setLayoutParams(endParams);
				}
			}
		});
		container.setTag(TAG_EXPANDED_STATE, animator);
		animator.start();
	}

	private boolean isLongDescription(String description) {
		if (TextUtils.isEmpty(description)) {
			return false;
		}
		int lineBreaks = 0;
		for (int i = 0; i < description.length(); i++) {
			if (description.charAt(i) == '\n') {
				lineBreaks++;
			}
		}
		return lineBreaks >= 10 || description.length() > 520;
	}

	private boolean startModDrag(View handle, ViewGroup transitionRoot, View details, View card, ModGroupBucket bucket, ExtraSettingsRepository.ModEntry entry) {
		if (!selectedModIds.isEmpty()) {
			return false;
		}
		if (expandedModIds.contains(entry.modId)) {
			expandedModIds.remove(entry.modId);
			setDetailsExpandedImmediately(details, false);
		}
		performDragHaptic(handle);
		ClipData clipData = ClipData.newPlainText("mod_id", entry.modId);
		View.DragShadowBuilder shadowBuilder = new View.DragShadowBuilder(card);
		DragState dragState = DragState.forMod(entry, bucket);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			return handle.startDragAndDrop(clipData, shadowBuilder, dragState, 0);
		}
		return handle.startDrag(clipData, shadowBuilder, dragState, 0);
	}

	private boolean startGroupDrag(View handle, View groupView, ModGroupBucket bucket) {
		if (!selectedModIds.isEmpty()) {
			return false;
		}
		performDragHaptic(handle);
		ClipData clipData = ClipData.newPlainText("mod_group", bucket.id);
		View.DragShadowBuilder shadowBuilder = new View.DragShadowBuilder(groupView);
		DragState dragState = DragState.forGroup(bucket);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			return handle.startDragAndDrop(clipData, shadowBuilder, dragState, 0);
		}
		return handle.startDrag(clipData, shadowBuilder, dragState, 0);
	}

	private void performDragHaptic(View view) {
		view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
		try {
			Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
			if (vibrator == null || !vibrator.hasVibrator()) {
				return;
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
			} else {
				vibrator.vibrate(35);
			}
		} catch (Exception ignored) {
		}
	}

	private MaterialButton detailIconButton(int iconRes, int descriptionRes) {
		MaterialButton button = ExtraSettingsUi.iconButton(context, iconRes);
		button.setContentDescription(context.getString(descriptionRes));
		button.setIconTint(ColorStateList.valueOf(ExtraSettingsUi.COLOR_PRIMARY));
		return button;
	}

	private void addDetailRow(LinearLayout parent, int iconRes, String value) {
		if (TextUtils.isEmpty(value)) {
			return;
		}
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_MUTED, 16));
		TextView text = ExtraSettingsUi.caption(context, value);
		text.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 6));
		row.addView(text, textParams);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(context, 6);
		parent.addView(row, params);
	}

	private String compactMeta(ExtraSettingsRepository.ModEntry entry) {
		List<String> parts = new ArrayList<>();
		if (!TextUtils.isEmpty(entry.version)) {
			parts.add("v" + entry.version.replaceFirst("^[vV]", ""));
		}
		if (!TextUtils.isEmpty(entry.authors)) {
			parts.add(entry.authors);
		}
		if (parts.isEmpty()) {
			parts.add(entry.modId);
		}
		return TextUtils.join("  •  ", parts);
	}

	private String dependenciesText(ExtraSettingsRepository.ModEntry entry) {
		return entry.dependencies.isEmpty() ? "—" : TextUtils.join(", ", entry.dependencies);
	}

	private void applyModCardStyle(MaterialCardView card, boolean enabled, boolean selected) {
		int background = selected ? Color.rgb(30, 50, 39) : ExtraSettingsUi.COLOR_SURFACE_CONTAINER;
		int border = selected ? ExtraSettingsUi.COLOR_PRIMARY : (enabled ? Color.rgb(72, 104, 84) : ExtraSettingsUi.COLOR_OUTLINE);
		card.setCardBackgroundColor(background);
		card.setStrokeWidth(ExtraSettingsUi.dp(context, selected ? 2 : 1));
		card.setStrokeColor(border);
	}

	private void toggleSelected(String modId) {
		boolean enteringSelectionMode = selectedModIds.isEmpty() && !selectedModIds.contains(modId);
		if (selectedModIds.contains(modId)) {
			selectedModIds.remove(modId);
		} else {
			selectedModIds.add(modId);
		}
		if (enteringSelectionMode) {
			expandedModIds.clear();
		}
		refreshList();
	}

	private String emptyToDash(String value) {
		return TextUtils.isEmpty(value) ? "—" : value;
	}

	private String displayCategory(ExtraSettingsRepository.ModEntry entry) {
		if (!TextUtils.isEmpty(entry.category)) {
			return entry.category;
		}
		if (isMissingPayload(entry)) {
			return context.getString(R.string.mod_category_missing_files);
		}
		if (isLibraryLike(entry)) {
			return context.getString(R.string.mod_category_library);
		}
		return context.getString(R.string.mod_category_content);
	}

	private boolean isMissingPayload(ExtraSettingsRepository.ModEntry entry) {
		return !entry.hasPck && !entry.hasDll;
	}

	private boolean isLibraryLike(ExtraSettingsRepository.ModEntry entry) {
		String probe = (entry.modId + " " + entry.displayName + " " + entry.category + " " + entry.relativePath).toLowerCase(Locale.ROOT);
		return probe.contains("lib") || probe.contains("library") || probe.contains("api") || (entry.hasDll && !entry.hasPck);
	}

	private View emptyCard(int textRes) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.setGravity(Gravity.CENTER_HORIZONTAL);
		content.addView(ExtraSettingsUi.iconCircle(context, R.drawable.ic_extension_24, ExtraSettingsUi.COLOR_SECONDARY_CONTAINER, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, textRes));
		return card;
	}

	private View errorCard(Exception exception) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_error_outline_24, R.string.error_operation_failed, 0, null));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, exception.getMessage() == null ? exception.toString() : exception.getMessage()));
		return card;
	}

	private void showModDetails(ExtraSettingsRepository.ModEntry entry, boolean enabled) {
		StringBuilder message = new StringBuilder();
		appendLine(message, context.getString(R.string.mod_detail_status), enabled ? context.getString(R.string.mod_enabled) : context.getString(R.string.mod_disabled));
		appendLine(message, "ID", entry.modId);
		appendLine(message, context.getString(R.string.mod_detail_category), displayCategory(entry));
		appendLine(message, context.getString(R.string.mod_detail_version), entry.version);
		appendLine(message, context.getString(R.string.mod_detail_author), entry.authors);
		appendLine(message, context.getString(R.string.mod_detail_files), context.getString(R.string.mod_detail_files_format, entry.hasPck ? "PCK" : "—", entry.hasDll ? "DLL" : "—"));
		appendLine(message, context.getString(R.string.mod_detail_dependencies), entry.dependencies.isEmpty() ? "—" : TextUtils.join(", ", entry.dependencies));
		appendLine(message, context.getString(R.string.mod_detail_path), entry.relativePath);
		if (!TextUtils.isEmpty(entry.description)) {
			message.append('\n').append(entry.description);
		}
		new MaterialAlertDialogBuilder(context)
			.setTitle(entry.displayName)
			.setMessage(message.toString().trim())
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}

	private void appendLine(StringBuilder builder, String label, String value) {
		builder.append(label).append(": ").append(emptyToDash(value)).append('\n');
	}

	private void showCreateProfileDialog() {
		EditText input = new EditText(context);
		input.setHint(R.string.mod_profile_name_hint);
		input.setSingleLine(true);
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.mod_profile_create_title)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				try {
					repository.createModProfileFromCurrent(input.getText() == null ? "" : input.getText().toString());
					selectedModIds.clear();
					rememberChipScroll();
					refreshList();
				} catch (Exception exception) {
					actions.showError(exception);
				}
			})
			.show();
	}

	private void confirmDeleteProfile(ExtraSettingsRepository.ModProfile profile) {
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.mod_profile_delete_title)
			.setMessage(context.getString(R.string.mod_profile_delete_message, profile.name))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				try {
					repository.deleteModProfile(profile.id);
					selectedModIds.clear();
					rememberChipScroll();
					refreshList();
				} catch (Exception exception) {
					actions.showError(exception);
				}
			})
			.show();
	}

	private void confirmDelete(ExtraSettingsRepository.ModEntry entry) {
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.confirm_delete_mod_title)
			.setMessage(context.getString(R.string.confirm_delete_mod_message) + "\n\n" + entry.relativePath)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				try {
					repository.deleteMod(entry);
					selectedModIds.remove(entry.modId);
					expandedModIds.remove(entry.modId);
					fullDescriptionModIds.remove(entry.modId);
					refreshList();
				} catch (Exception exception) {
					actions.showError(exception);
				}
			})
			.show();
	}

	private void batchSetEnabled(boolean enabled) {
		try {
			List<ExtraSettingsRepository.ModEntry> selected = selectedEntries();
			if (selected.isEmpty()) {
				actions.showMessage(context.getString(R.string.mod_batch_empty));
				return;
			}
			repository.setModsEnabled(selected, enabled);
			selectedModIds.clear();
			refreshList();
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private void confirmBatchDelete() {
		try {
			List<ExtraSettingsRepository.ModEntry> selected = selectedEntries();
			if (selected.isEmpty()) {
				actions.showMessage(context.getString(R.string.mod_batch_empty));
				return;
			}
			new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.mod_batch_delete)
				.setMessage(context.getString(R.string.mod_batch_delete_confirm, selected.size()))
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					try {
						repository.deleteMods(selected);
						for (ExtraSettingsRepository.ModEntry entry : selected) {
							selectedModIds.remove(entry.modId);
							expandedModIds.remove(entry.modId);
							fullDescriptionModIds.remove(entry.modId);
						}
						refreshList();
					} catch (Exception exception) {
						actions.showError(exception);
					}
				})
				.show();
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private void selectAllVisibleMods() {
		for (ExtraSettingsRepository.ModEntry entry : currentFilteredMods) {
			selectedModIds.add(entry.modId);
		}
		refreshList();
	}

	private void invertVisibleSelection() {
		for (ExtraSettingsRepository.ModEntry entry : currentFilteredMods) {
			if (selectedModIds.contains(entry.modId)) {
				selectedModIds.remove(entry.modId);
			} else {
				selectedModIds.add(entry.modId);
			}
		}
		refreshList();
	}

	private void selectRangeBetweenSelected() {
		int first = -1;
		int last = -1;
		for (int i = 0; i < currentFilteredMods.size(); i++) {
			if (selectedModIds.contains(currentFilteredMods.get(i).modId)) {
				if (first < 0) {
					first = i;
				}
				last = i;
			}
		}
		if (first < 0 || last <= first) {
			actions.showMessage(context.getString(R.string.mod_select_range_need_two));
			return;
		}
		for (int i = first; i <= last; i++) {
			selectedModIds.add(currentFilteredMods.get(i).modId);
		}
		refreshList();
	}

	private List<ExtraSettingsRepository.ModEntry> selectedEntries() {
		List<ExtraSettingsRepository.ModEntry> result = new ArrayList<>();
		for (ExtraSettingsRepository.ModEntry entry : repository.listInstalledModManifests()) {
			if (selectedModIds.contains(entry.modId)) {
				result.add(entry);
			}
		}
		return result;
	}

	private void showCreateGroupDialog() {
		EditText input = new EditText(context);
		input.setHint(R.string.mod_group_name_hint);
		input.setSingleLine(true);
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.mod_group_create)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				try {
					String name = input.getText() == null ? "" : input.getText().toString();
					if (TextUtils.isEmpty(name.trim())) {
						actions.showMessage(context.getString(R.string.mod_group_name_required));
						return;
					}
					repository.createModGroup(name);
					rememberChipScroll();
					refreshList();
				} catch (Exception exception) {
					actions.showError(exception);
				}
			})
			.show();
	}

	private static final class ModGroupBucket {
		final String id;
		final String label;
		boolean userCreated;
		final List<ExtraSettingsRepository.ModEntry> entries = new ArrayList<>();

		ModGroupBucket(String id, String label, boolean userCreated) {
			this.id = id;
			this.label = label;
			this.userCreated = userCreated;
		}
	}

	private static final class DragState {
		static final int TYPE_MOD = 1;
		static final int TYPE_GROUP = 2;
		final int type;
		final ExtraSettingsRepository.ModEntry entry;
		final ModGroupBucket sourceBucket;

		private DragState(int type, ExtraSettingsRepository.ModEntry entry, ModGroupBucket sourceBucket) {
			this.type = type;
			this.entry = entry;
			this.sourceBucket = sourceBucket;
		}

		static DragState forMod(ExtraSettingsRepository.ModEntry entry, ModGroupBucket sourceBucket) {
			return new DragState(TYPE_MOD, entry, sourceBucket);
		}

		static DragState forGroup(ModGroupBucket sourceBucket) {
			return new DragState(TYPE_GROUP, null, sourceBucket);
		}
	}

	private static final class DashedDivider extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

		DashedDivider(Context context) {
			super(context);
			paint.setColor(ExtraSettingsUi.COLOR_OUTLINE);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(ExtraSettingsUi.dp(context, 1));
			paint.setPathEffect(new android.graphics.DashPathEffect(new float[] { ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 4) }, 0));
		}

		@Override
		protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);
			canvas.drawLine(0, getHeight() / 2f, getWidth(), getHeight() / 2f, paint);
		}
	}
}
