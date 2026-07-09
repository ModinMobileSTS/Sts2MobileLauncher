package com.godot.game;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.graphics.drawable.ColorDrawable;
import android.text.SpannableString;

import androidx.appcompat.widget.PopupMenu;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

	private static final int TYPE_GROUP = 1;
	private static final int TYPE_MOD = 2;
	private static final int TYPE_EMPTY = 3;
	private static final int TYPE_ERROR = 4;
	private static final int TYPE_GHOST = 5;

	private static final Object PAYLOAD_SELECTION = "selection";
	private static final Object PAYLOAD_EXPAND = "expand";
	private static final Object PAYLOAD_SELECTION_MODE = "selection_mode";

	private final Context context;
	private final ExtraSettingsRepository repository;
	private final ExtraSettingsActions actions;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final Set<String> selectedModIds = new HashSet<>();
	private final Set<String> expandedModIds = new HashSet<>();
	private final Set<String> fullDescriptionModIds = new HashSet<>();
	private final Set<String> collapsedGroupIds = new HashSet<>();
	private final List<ExtraSettingsRepository.ModEntry> currentFilteredMods = new ArrayList<>();
	private final List<ExtraSettingsRepository.ModEntry> currentAllMods = new ArrayList<>();
	private final List<ModGroupBucket> currentBuckets = new ArrayList<>();
	private final List<ListItem> listItems = new ArrayList<>();
	private final Map<String, List<ModIssue>> issuesByModId = new LinkedHashMap<>();
	private final List<ModIssue> currentIssues = new ArrayList<>();
	private final Map<String, String> modNotesById = new HashMap<>();

	private RecyclerView recyclerView;
	private ModsListAdapter adapter;
	private MaterialCardView bottomPanelCard;
	private LinearLayout bottomPanelContent;
	private EditText searchInput;
	private HorizontalScrollView chipScrollView;
	private View warningAction;
	private TextView warningCountView;
	private int chipScrollX;
	private boolean suppressChipScrollCapture;
	private String filter = "all";
	private String sortMode = SORT_INSTALLED;
	private boolean bottomPanelVisible;
	private boolean bottomPanelCollapsed;
	private JSONObject cachedSettings;
	private Runnable pendingSearchRefresh;
	private String dragGhostGroupId;
	private int dragGhostIndex = -1;
	private boolean dragGhostForGroup;
	private BottomSheetDialog groupManageSheet;
	private BottomSheetDialog issuesSheet;
	private String currentGameVersion = "";

	public ModsPage(Context context, ExtraSettingsRepository repository, ExtraSettingsActions actions) {
		this.context = context;
		this.repository = repository;
		this.actions = actions;
		this.chipScrollX = retainedChipScrollX;
	}

	public View build() {
		FrameLayout frame = new FrameLayout(context);
		frame.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		LinearLayout column = ExtraSettingsUi.vertical(context);
		column.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		column.addView(buildTopBar());
		column.addView(buildCompactActions());

		recyclerView = new RecyclerView(context);
		recyclerView.setLayoutManager(new LinearLayoutManager(context));
		recyclerView.setClipToPadding(false);
		int horizontalPadding = ExtraSettingsUi.dp(context, 16);
		int bottomPad = ExtraSettingsUi.dp(context, ExtraSettingsUi.isWideLayout(context) ? 96 : 122);
		recyclerView.setPadding(horizontalPadding, ExtraSettingsUi.dp(context, 8), horizontalPadding, bottomPad);
		recyclerView.setHasFixedSize(false);
		recyclerView.setItemAnimator(null);
		adapter = new ModsListAdapter();
		recyclerView.setAdapter(adapter);
		recyclerView.setOnDragListener((view, event) -> handleRecyclerDrag(event));
		column.addView(recyclerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		frame.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		frame.addView(buildBottomPanel(), bottomPanelParams());
		refreshList();
		return frame;
	}

	private View buildTopBar() {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(ExtraSettingsUi.dp(context, 78));
		row.setPadding(ExtraSettingsUi.dp(context, 16), ExtraSettingsUi.dp(context, 18), ExtraSettingsUi.dp(context, 16), ExtraSettingsUi.dp(context, 14));
		SystemBarInsetsHelper.applySystemBarPadding(row, true, false, false, false);

		LinearLayout titleCluster = ExtraSettingsUi.horizontal(context);
		titleCluster.setGravity(Gravity.CENTER_VERTICAL);
		TextView title = ExtraSettingsUi.text(context, context.getString(R.string.tab_mods), 22, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setSingleLine(true);
		title.setEllipsize(TextUtils.TruncateAt.END);
		titleCluster.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		LinearLayout warningChip = ExtraSettingsUi.horizontal(context);
		warningChip.setGravity(Gravity.CENTER_VERTICAL);
		warningChip.setPadding(ExtraSettingsUi.dp(context, 8), ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 10), ExtraSettingsUi.dp(context, 4));
		GradientDrawable warningBg = new GradientDrawable();
		warningBg.setColor(Color.argb(40, Color.red(ExtraSettingsUi.COLOR_WARNING), Color.green(ExtraSettingsUi.COLOR_WARNING), Color.blue(ExtraSettingsUi.COLOR_WARNING)));
		warningBg.setCornerRadius(ExtraSettingsUi.dp(context, 999));
		warningChip.setBackground(warningBg);
		warningChip.setClickable(true);
		warningChip.setFocusable(true);
		TypedValue outValue = new TypedValue();
		if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
			warningChip.setForeground(context.getDrawable(outValue.resourceId));
		}
		ImageView warningIcon = ExtraSettingsUi.icon(context, R.drawable.ic_error_outline_24, ExtraSettingsUi.COLOR_WARNING, 18);
		warningChip.addView(warningIcon);
		warningCountView = ExtraSettingsUi.text(context, "", 13, ExtraSettingsUi.COLOR_WARNING, Typeface.BOLD);
		warningCountView.setSingleLine(true);
		LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		countParams.setMarginStart(ExtraSettingsUi.dp(context, 4));
		warningChip.addView(warningCountView, countParams);
		warningChip.setOnClickListener(v -> showModIssuesSheet());
		warningChip.setContentDescription(context.getString(R.string.mod_issues_title));
		warningChip.setVisibility(View.GONE);
		warningAction = warningChip;
		LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		warningParams.setMarginStart(ExtraSettingsUi.dp(context, 8));
		titleCluster.addView(warningChip, warningParams);

		row.addView(titleCluster, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
					cachedSettings = repository.loadSettingsJson();
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
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) {
				if (pendingSearchRefresh != null) {
					mainHandler.removeCallbacks(pendingSearchRefresh);
				}
				pendingSearchRefresh = () -> refreshList();
				mainHandler.postDelayed(pendingSearchRefresh, 150);
			}
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
		chips.addView(actionChip(R.string.workshop_open, R.drawable.ic_steam_24, v -> actions.openSteamWorkshop()));
		addChipGap(chips);
		chips.addView(actionChip(R.string.mod_group_manage, R.drawable.ic_folder_24, v -> showGroupManageSheet()));
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
		SystemBarInsetsHelper.applySystemBarPadding(bottomPanelCard, false, false, true, false);
		bottomPanelCard.setVisibility(View.GONE);
		return bottomPanelCard;
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

		LinearLayout clearRow = ExtraSettingsUi.horizontal(context);
		MaterialButton moveTo = ExtraSettingsUi.tonalButton(context, R.string.mod_group_move_to, R.drawable.ic_folder_24);
		MaterialButton clearSelection = ExtraSettingsUi.outlineButton(context, R.string.mod_clear_selection, R.drawable.ic_close_24);
		moveTo.setOnClickListener(v -> showMoveSelectedToGroupDialog());
		clearSelection.setOnClickListener(v -> {
			selectedModIds.clear();
			notifySelectionModeChanged();
			updateSelectionActionsPanel();
		});
		clearRow.addView(moveTo, weightedButtonParams(0));
		clearRow.addView(clearSelection, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(bottomPanelContent, clearRow);
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
		clearDragGhost(false);
		currentFilteredMods.clear();
		currentAllMods.clear();
		currentBuckets.clear();
		listItems.clear();
		try {
			cachedSettings = repository.loadSettingsJson();
			modNotesById.clear();
			modNotesById.putAll(repository.loadAllModNotes());
			currentGameVersion = resolveCurrentGameVersion();
			List<ExtraSettingsRepository.ModEntry> allMods = repository.listInstalledModManifests();
			currentAllMods.addAll(allMods);
			rebuildModIssues(allMods);
			List<ExtraSettingsRepository.ModEntry> filtered = filterMods(cachedSettings, allMods);
			sortMods(filtered);
			currentFilteredMods.addAll(filtered);
			Set<String> installedIds = new HashSet<>();
			for (ExtraSettingsRepository.ModEntry entry : allMods) {
				installedIds.add(entry.modId);
			}
			selectedModIds.retainAll(installedIds);
			expandedModIds.retainAll(installedIds);
			if (allMods.isEmpty()) {
				listItems.add(ListItem.empty(R.string.status_no_mods));
			} else if (filtered.isEmpty()) {
				listItems.add(ListItem.empty(R.string.mod_no_filter_results));
			} else {
				List<ModGroupBucket> buckets = buildModGroups(filtered);
				currentBuckets.addAll(buckets);
				for (ModGroupBucket bucket : buckets) {
					if (bucket.entries.isEmpty() && !bucket.userCreated) {
						continue;
					}
					boolean collapsed = collapsedGroupIds.contains(bucket.id);
					listItems.add(ListItem.group(bucket, collapsed));
					if (!collapsed) {
						for (ExtraSettingsRepository.ModEntry entry : bucket.entries) {
							listItems.add(ListItem.mod(bucket.id, entry));
						}
					}
				}
			}
			submitListPreservingScroll(new ArrayList<>(listItems));
			updateSelectionActionsPanel();
			updateWarningBadge();
		} catch (Exception exception) {
			listItems.clear();
			listItems.add(ListItem.error(exception));
			submitListPreservingScroll(new ArrayList<>(listItems));
			updateSelectionActionsPanel();
			issuesByModId.clear();
			currentIssues.clear();
			updateWarningBadge();
		}
	}

	/** Rebuild flat list items from in-memory buckets without reloading manifests from disk. */
	private void rebuildListItemsFromBuckets() {
		clearDragGhost(false);
		listItems.clear();
		currentFilteredMods.clear();
		for (ModGroupBucket bucket : currentBuckets) {
			if (bucket.entries.isEmpty() && !bucket.userCreated) {
				continue;
			}
			boolean collapsed = collapsedGroupIds.contains(bucket.id);
			listItems.add(ListItem.group(bucket, collapsed));
			if (!collapsed) {
				for (ExtraSettingsRepository.ModEntry entry : bucket.entries) {
					listItems.add(ListItem.mod(bucket.id, entry));
					currentFilteredMods.add(entry);
				}
			} else {
				currentFilteredMods.addAll(bucket.entries);
			}
		}
		if (listItems.isEmpty()) {
			listItems.add(ListItem.empty(R.string.mod_no_filter_results));
		}
		// Order-sensitive checks (dependency load order) must track drag reorder.
		if (!currentAllMods.isEmpty()) {
			rebuildModIssues(currentAllMods);
			updateWarningBadge();
		}
		submitListPreservingScroll(new ArrayList<>(listItems));
	}

	private void submitListPreservingScroll(List<ListItem> items) {
		if (adapter == null) {
			return;
		}
		int firstPos = RecyclerView.NO_POSITION;
		int offset = 0;
		LinearLayoutManager layoutManager = null;
		if (recyclerView != null && recyclerView.getLayoutManager() instanceof LinearLayoutManager) {
			layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
			firstPos = layoutManager.findFirstVisibleItemPosition();
			View firstChild = layoutManager.findViewByPosition(firstPos);
			if (firstChild != null) {
				offset = firstChild.getTop() - recyclerView.getPaddingTop();
			}
		}
		adapter.submit(items);
		if (layoutManager != null && firstPos != RecyclerView.NO_POSITION && firstPos >= 0) {
			final int restorePos = firstPos;
			final int restoreOffset = offset;
			final LinearLayoutManager lm = layoutManager;
			recyclerView.post(() -> {
				int safePos = Math.min(restorePos, Math.max(0, adapter.getItemCount() - 1));
				lm.scrollToPositionWithOffset(safePos, restoreOffset);
			});
		}
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
			String note = noteFor(entry.modId);
			String haystack = (entry.displayName + " " + note + " " + entry.modId + " " + entry.pckName + " " + entry.version + " " + entry.authors + " " + entry.description + " " + entry.category + " " + entry.relativePath + " " + TextUtils.join(" ", entry.dependencyLabels)).toLowerCase(Locale.ROOT);
			if (!query.isEmpty() && !haystack.contains(query)) {
				continue;
			}
			result.add(entry);
		}
		return result;
	}

	private void sortMods(List<ExtraSettingsRepository.ModEntry> mods) {
		if (SORT_NAME.equals(sortMode)) {
			mods.sort(Comparator.comparing(this::displayNameFor, String::compareToIgnoreCase));
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
		Map<String, String> groupAssignments = repository.loadModGroupAssignments();
		putGroup(groups, MOD_GROUP_CORE, context.getString(R.string.mod_group_core), false);
		putGroup(groups, MOD_GROUP_CONTENT, context.getString(R.string.mod_group_content), false);
		for (String groupName : userGroups) {
			String groupId = normalizeGroupId(groupName);
			String label = groupLabel(groupId, groupName);
			putGroup(groups, groupId, label, true);
		}
		for (ExtraSettingsRepository.ModEntry entry : mods) {
			String groupId = groupIdForEntry(entry, userGroupNames, groupAssignments);
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

	private String groupIdForEntry(ExtraSettingsRepository.ModEntry entry, Set<String> userGroupNames, Map<String, String> groupAssignments) {
		String assignedGroup = groupAssignments == null ? "" : groupAssignments.get(entry.modId);
		if (!TextUtils.isEmpty(assignedGroup)) {
			return normalizeGroupId(assignedGroup);
		}
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
		if (MOD_GROUP_UNGROUPED.equals(groupId) || TextUtils.isEmpty(groupId)) {
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

	private void toggleGroupCollapsed(String groupId) {
		if (collapsedGroupIds.contains(groupId)) {
			collapsedGroupIds.remove(groupId);
		} else {
			collapsedGroupIds.add(groupId);
		}
		refreshList();
	}

	private void collapseAllGroupsForReorder() {
		for (ModGroupBucket bucket : currentBuckets) {
			collapsedGroupIds.add(bucket.id);
		}
		// Also collapse any other known group headers currently in items.
		for (ListItem item : listItems) {
			if (item.type == TYPE_GROUP && item.bucket != null) {
				collapsedGroupIds.add(item.bucket.id);
			}
		}
		refreshList();
	}

	// region Drag

	private boolean handleRecyclerDrag(DragEvent event) {
		DragState state = asDragState(event.getLocalState());
		if (state != null && !selectedModIds.isEmpty()) {
			return event.getAction() == DragEvent.ACTION_DRAG_STARTED;
		}
		if (state == null) {
			return false;
		}
		if (state.type == DragState.TYPE_MOD) {
			return handleModDrag(event, state);
		}
		if (state.type == DragState.TYPE_GROUP) {
			return handleGroupDrag(event, state);
		}
		return false;
	}

	private boolean handleModDrag(DragEvent event, DragState state) {
		switch (event.getAction()) {
			case DragEvent.ACTION_DRAG_STARTED:
				return true;
			case DragEvent.ACTION_DRAG_LOCATION:
			case DragEvent.ACTION_DRAG_ENTERED: {
				DropTarget target = resolveModDropTarget(event.getX(), event.getY());
				if (target != null) {
					showDragGhost(target.groupId, target.index, false);
				}
				return true;
			}
			case DragEvent.ACTION_DROP: {
				DropTarget target = resolveModDropTarget(event.getX(), event.getY());
				clearDragGhost(true);
				if (target != null) {
					ModGroupBucket bucket = findBucket(target.groupId);
					ModGroupBucket source = state.sourceBucket;
					moveModToGroup(state.entry, source, bucket, target.index);
				}
				return true;
			}
			case DragEvent.ACTION_DRAG_ENDED:
			case DragEvent.ACTION_DRAG_EXITED:
				if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
					clearDragGhost(true);
				}
				return true;
			default:
				return true;
		}
	}

	private boolean handleGroupDrag(DragEvent event, DragState state) {
		switch (event.getAction()) {
			case DragEvent.ACTION_DRAG_STARTED:
				return true;
			case DragEvent.ACTION_DRAG_LOCATION:
			case DragEvent.ACTION_DRAG_ENTERED: {
				int index = resolveGroupDropIndex(event.getY());
				showDragGhost(null, index, true);
				return true;
			}
			case DragEvent.ACTION_DROP: {
				int index = dragGhostForGroup && dragGhostIndex >= 0 ? dragGhostIndex : resolveGroupDropIndex(event.getY());
				clearDragGhost(true);
				reorderGroup(state.sourceBucket, index);
				return true;
			}
			case DragEvent.ACTION_DRAG_ENDED:
				clearDragGhost(true);
				return true;
			default:
				return true;
		}
	}

	private static final class DropTarget {
		final String groupId;
		final int index;

		DropTarget(String groupId, int index) {
			this.groupId = groupId;
			this.index = index;
		}
	}

	private DropTarget resolveModDropTarget(float x, float y) {
		View child = recyclerView.findChildViewUnder(x, y);
		if (child == null) {
			// Fallback: last visible group
			if (currentBuckets.isEmpty()) {
				return null;
			}
			ModGroupBucket last = currentBuckets.get(currentBuckets.size() - 1);
			return new DropTarget(last.id, last.entries.size());
		}
		int position = recyclerView.getChildAdapterPosition(child);
		if (position == RecyclerView.NO_POSITION || position >= listItems.size()) {
			return null;
		}
		ListItem item = listItems.get(position);
		float midY = child.getTop() + child.getHeight() / 2f;
		if (item.type == TYPE_GROUP && item.bucket != null) {
			if (collapsedGroupIds.contains(item.bucket.id)) {
				return new DropTarget(item.bucket.id, item.bucket.entries.size());
			}
			return new DropTarget(item.bucket.id, 0);
		}
		if (item.type == TYPE_MOD && item.entry != null) {
			int indexInGroup = indexOfModInBucket(item.groupId, item.entry.modId);
			if (indexInGroup < 0) {
				indexInGroup = 0;
			}
			if (y > midY) {
				indexInGroup++;
			}
			return new DropTarget(item.groupId, indexInGroup);
		}
		if (item.type == TYPE_GHOST) {
			return new DropTarget(item.groupId, item.ghostIndex);
		}
		return null;
	}

	private int resolveGroupDropIndex(float y) {
		int groupCount = 0;
		for (ListItem item : listItems) {
			if (item.type == TYPE_GROUP) {
				groupCount++;
			}
		}
		int index = 0;
		for (int i = 0; i < listItems.size(); i++) {
			ListItem item = listItems.get(i);
			if (item.type != TYPE_GROUP) {
				continue;
			}
			RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(i);
			if (holder == null) {
				continue;
			}
			View child = holder.itemView;
			if (y > child.getTop() + child.getHeight() / 2f) {
				index++;
			}
		}
		return Math.max(0, Math.min(index, groupCount));
	}

	private int indexOfModInBucket(String groupId, String modId) {
		ModGroupBucket bucket = findBucket(groupId);
		if (bucket == null) {
			return -1;
		}
		for (int i = 0; i < bucket.entries.size(); i++) {
			if (bucket.entries.get(i).modId.equals(modId)) {
				return i;
			}
		}
		return -1;
	}

	private ModGroupBucket findBucket(String groupId) {
		for (ModGroupBucket bucket : currentBuckets) {
			if (bucket.id.equals(groupId)) {
				return bucket;
			}
		}
		return null;
	}

	private void showDragGhost(String groupId, int index, boolean forGroup) {
		if (dragGhostForGroup == forGroup
			&& ((groupId == null && dragGhostGroupId == null) || (groupId != null && groupId.equals(dragGhostGroupId)))
			&& dragGhostIndex == index) {
			return;
		}
		clearDragGhost(false);
		dragGhostGroupId = groupId;
		dragGhostIndex = index;
		dragGhostForGroup = forGroup;
		// Rebuild list items with ghost inserted without reloading from disk.
		List<ListItem> withGhost = new ArrayList<>();
		if (forGroup) {
			int groupSeen = 0;
			boolean inserted = false;
			for (ListItem item : listItems) {
				if (item.type == TYPE_GHOST) {
					continue;
				}
				if (item.type == TYPE_GROUP) {
					if (!inserted && groupSeen == index) {
						withGhost.add(ListItem.ghost(null, index, true));
						inserted = true;
					}
					groupSeen++;
				}
				withGhost.add(item);
			}
			if (!inserted) {
				withGhost.add(ListItem.ghost(null, index, true));
			}
		} else {
			boolean inserted = false;
			int modsInTarget = 0;
			for (ListItem item : listItems) {
				if (item.type == TYPE_GHOST) {
					continue;
				}
				if (item.type == TYPE_GROUP && item.bucket != null && item.bucket.id.equals(groupId)) {
					withGhost.add(item);
					if (collapsedGroupIds.contains(groupId)) {
						// collapsed: ghost after header
						if (!inserted && index >= 0) {
							withGhost.add(ListItem.ghost(groupId, index, false));
							inserted = true;
						}
					}
					continue;
				}
				if (item.type == TYPE_MOD && groupId != null && groupId.equals(item.groupId)) {
					if (!inserted && modsInTarget == index) {
						withGhost.add(ListItem.ghost(groupId, index, false));
						inserted = true;
					}
					modsInTarget++;
					withGhost.add(item);
					continue;
				}
				withGhost.add(item);
			}
			if (!inserted && groupId != null) {
				// append at end of group block if not found
				List<ListItem> rebuilt = new ArrayList<>();
				boolean groupSeen = false;
				boolean placed = false;
				for (ListItem item : withGhost) {
					if (item.type == TYPE_GROUP && item.bucket != null && item.bucket.id.equals(groupId)) {
						groupSeen = true;
						rebuilt.add(item);
						continue;
					}
					if (groupSeen && !placed && (item.type == TYPE_GROUP || item.type == TYPE_EMPTY || item.type == TYPE_ERROR)) {
						rebuilt.add(ListItem.ghost(groupId, index, false));
						placed = true;
					}
					rebuilt.add(item);
				}
				if (!placed) {
					rebuilt.add(ListItem.ghost(groupId, index, false));
				}
				withGhost = rebuilt;
			}
		}
		listItems.clear();
		listItems.addAll(withGhost);
		// Ghost updates should not fight the user's scroll position.
		if (adapter != null) {
			adapter.submit(new ArrayList<>(listItems));
		}
	}

	private void clearDragGhost(boolean notify) {
		boolean had = false;
		for (int i = listItems.size() - 1; i >= 0; i--) {
			if (listItems.get(i).type == TYPE_GHOST) {
				listItems.remove(i);
				had = true;
			}
		}
		dragGhostGroupId = null;
		dragGhostIndex = -1;
		dragGhostForGroup = false;
		if (had && notify && adapter != null) {
			adapter.submit(new ArrayList<>(listItems));
		}
	}

	private DragState asDragState(Object localState) {
		return localState instanceof DragState ? (DragState) localState : null;
	}

	private void moveModToGroup(ExtraSettingsRepository.ModEntry entry, ModGroupBucket sourceBucket, ModGroupBucket targetBucket, int targetIndex) {
		if (entry == null || targetBucket == null) {
			return;
		}
		try {
			boolean sameGroup = sourceBucket != null && sourceBucket.id.equals(targetBucket.id);
			int adjustedTargetIndex = targetIndex;
			if (sameGroup) {
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
			if (!sameGroup) {
				if (sourceBucket != null) {
					List<String> sourceOrder = new ArrayList<>();
					for (ExtraSettingsRepository.ModEntry mod : sourceBucket.entries) {
						if (!mod.modId.equals(entry.modId)) {
							sourceOrder.add(mod.modId);
						}
					}
					repository.saveModOrder(sourceBucket.id, sourceOrder);
				}
				repository.moveModToGroup(entry, targetBucket.id);
			}

			// Optimistic local reorder: keep scroll and avoid full page rebuild.
			applyLocalModMove(entry, sourceBucket, targetBucket, clamped);
			rebuildListItemsFromBuckets();
			if (!sameGroup) {
				actions.showMessage(context.getString(R.string.status_move_mod_group_done, targetBucket.label));
			}
		} catch (Exception exception) {
			actions.showError(exception);
			refreshList();
		}
	}

	private void applyLocalModMove(ExtraSettingsRepository.ModEntry entry, ModGroupBucket sourceBucket, ModGroupBucket targetBucket, int targetIndex) {
		if (entry == null || targetBucket == null) {
			return;
		}
		if (sourceBucket != null) {
			sourceBucket.entries.removeIf(mod -> mod.modId.equals(entry.modId));
		} else {
			for (ModGroupBucket bucket : currentBuckets) {
				bucket.entries.removeIf(mod -> mod.modId.equals(entry.modId));
			}
		}
		// Avoid duplicate if already present.
		targetBucket.entries.removeIf(mod -> mod.modId.equals(entry.modId));
		int clamped = Math.max(0, Math.min(targetIndex, targetBucket.entries.size()));
		targetBucket.entries.add(clamped, entry);
	}

	private void reorderGroup(ModGroupBucket movedBucket, int targetIndex) {
		if (movedBucket == null) {
			return;
		}
		List<ModGroupBucket> visible = new ArrayList<>();
		for (ModGroupBucket bucket : currentBuckets) {
			if (bucket.entries.isEmpty() && !bucket.userCreated) {
				continue;
			}
			visible.add(bucket);
		}
		int oldIndex = -1;
		for (int i = 0; i < visible.size(); i++) {
			if (visible.get(i).id.equals(movedBucket.id)) {
				oldIndex = i;
				break;
			}
		}
		if (oldIndex < 0) {
			return;
		}
		int adjustedTargetIndex = targetIndex;
		if (oldIndex < adjustedTargetIndex) {
			adjustedTargetIndex--;
		}
		ModGroupBucket removed = visible.remove(oldIndex);
		int clamped = Math.max(0, Math.min(adjustedTargetIndex, visible.size()));
		visible.add(clamped, removed);

		List<String> order = new ArrayList<>();
		for (ModGroupBucket bucket : visible) {
			order.add(bucket.id);
		}
		repository.saveModGroupOrder(order);

		// Reorder full currentBuckets to match saved order (including empty system groups).
		Map<String, ModGroupBucket> byId = new LinkedHashMap<>();
		for (ModGroupBucket bucket : currentBuckets) {
			byId.put(bucket.id, bucket);
		}
		List<ModGroupBucket> reordered = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String id : order) {
			ModGroupBucket bucket = byId.get(id);
			if (bucket != null && seen.add(id)) {
				reordered.add(bucket);
			}
		}
		for (ModGroupBucket bucket : currentBuckets) {
			if (seen.add(bucket.id)) {
				reordered.add(bucket);
			}
		}
		currentBuckets.clear();
		currentBuckets.addAll(reordered);
		rebuildListItemsFromBuckets();
	}

	private boolean startModDrag(View handle, View card, ModGroupBucket bucket, ExtraSettingsRepository.ModEntry entry) {
		if (!selectedModIds.isEmpty()) {
			return false;
		}
		if (expandedModIds.contains(entry.modId)) {
			expandedModIds.remove(entry.modId);
			int pos = findModAdapterPosition(entry.modId);
			if (pos >= 0) {
				adapter.notifyItemChanged(pos, PAYLOAD_EXPAND);
			}
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

	private boolean startGroupDrag(View handle, View headerView, ModGroupBucket bucket) {
		if (!selectedModIds.isEmpty()) {
			return false;
		}
		performDragHaptic(handle);
		collapseAllGroupsForReorder();
		recyclerView.post(() -> {
			View shadowView = headerView;
			int pos = findGroupAdapterPosition(bucket.id);
			if (pos >= 0) {
				RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(pos);
				if (holder != null) {
					shadowView = holder.itemView;
				}
			}
			ClipData clipData = ClipData.newPlainText("mod_group", bucket.id);
			View.DragShadowBuilder shadowBuilder = new View.DragShadowBuilder(shadowView);
			DragState dragState = DragState.forGroup(bucket);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				handle.startDragAndDrop(clipData, shadowBuilder, dragState, 0);
			} else {
				handle.startDrag(clipData, shadowBuilder, dragState, 0);
			}
		});
		return true;
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

	// endregion

	// region Selection

	private void toggleSelected(String modId) {
		boolean wasEmpty = selectedModIds.isEmpty();
		if (selectedModIds.contains(modId)) {
			selectedModIds.remove(modId);
		} else {
			selectedModIds.add(modId);
		}
		boolean nowEmpty = selectedModIds.isEmpty();
		if (wasEmpty && !nowEmpty) {
			expandedModIds.clear();
			notifySelectionModeChanged();
		} else if (!wasEmpty && nowEmpty) {
			notifySelectionModeChanged();
		} else {
			int pos = findModAdapterPosition(modId);
			if (pos >= 0) {
				adapter.notifyItemChanged(pos, PAYLOAD_SELECTION);
			}
		}
		updateSelectionActionsPanel();
	}

	private void notifySelectionModeChanged() {
		if (adapter != null) {
			adapter.notifyItemRangeChanged(0, adapter.getItemCount(), PAYLOAD_SELECTION_MODE);
		}
	}

	private void notifySelectionOnly() {
		if (adapter != null) {
			adapter.notifyItemRangeChanged(0, adapter.getItemCount(), PAYLOAD_SELECTION);
		}
	}

	private int findModAdapterPosition(String modId) {
		for (int i = 0; i < listItems.size(); i++) {
			ListItem item = listItems.get(i);
			if (item.type == TYPE_MOD && item.entry != null && modId.equals(item.entry.modId)) {
				return i;
			}
		}
		return -1;
	}

	private int findGroupAdapterPosition(String groupId) {
		for (int i = 0; i < listItems.size(); i++) {
			ListItem item = listItems.get(i);
			if (item.type == TYPE_GROUP && item.bucket != null && groupId.equals(item.bucket.id)) {
				return i;
			}
		}
		return -1;
	}

	private void selectAllVisibleMods() {
		for (ExtraSettingsRepository.ModEntry entry : currentFilteredMods) {
			selectedModIds.add(entry.modId);
		}
		notifySelectionModeChanged();
		updateSelectionActionsPanel();
	}

	private void invertVisibleSelection() {
		boolean wasEmpty = selectedModIds.isEmpty();
		for (ExtraSettingsRepository.ModEntry entry : currentFilteredMods) {
			if (selectedModIds.contains(entry.modId)) {
				selectedModIds.remove(entry.modId);
			} else {
				selectedModIds.add(entry.modId);
			}
		}
		if (wasEmpty != selectedModIds.isEmpty()) {
			if (!selectedModIds.isEmpty()) {
				expandedModIds.clear();
			}
			notifySelectionModeChanged();
		} else {
			notifySelectionOnly();
		}
		updateSelectionActionsPanel();
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
		notifySelectionOnly();
		updateSelectionActionsPanel();
	}

	// endregion

	// region Group manage + move dialogs

	private void showGroupManageSheet() {
		if (groupManageSheet != null) {
			try {
				groupManageSheet.dismiss();
			} catch (Exception ignored) {
			}
		}
		BottomSheetDialog dialog = new BottomSheetDialog(context);
		dialog.setOnShowListener(unused -> {
			Window window = dialog.getWindow();
			if (window != null) {
				window.setDimAmount(0.56f);
			}
		});
		groupManageSheet = dialog;
		dialog.setContentView(buildGroupManageContent(dialog));
		dialog.show();
	}

	private View buildGroupManageContent(BottomSheetDialog dialog) {
		LinearLayout root = ExtraSettingsUi.vertical(context);
		root.setPadding(ExtraSettingsUi.dp(context, 24), ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 24), ExtraSettingsUi.dp(context, 32));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		float radius = ExtraSettingsUi.dp(context, 28);
		bg.setCornerRadii(new float[] { radius, radius, radius, radius, 0, 0, 0, 0 });
		root.setBackground(bg);

		View handle = new View(context);
		GradientDrawable handleBg = new GradientDrawable();
		handleBg.setColor(ExtraSettingsUi.COLOR_OUTLINE);
		handleBg.setCornerRadius(ExtraSettingsUi.dp(context, 2));
		handle.setBackground(handleBg);
		LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 40), ExtraSettingsUi.dp(context, 4));
		handleParams.gravity = Gravity.CENTER_HORIZONTAL;
		handleParams.bottomMargin = ExtraSettingsUi.dp(context, 16);
		root.addView(handle, handleParams);

		root.addView(ExtraSettingsUi.sectionTitle(context, context.getString(R.string.mod_group_manage)));

		LinearLayout createRow = ExtraSettingsUi.horizontal(context);
		createRow.setGravity(Gravity.CENTER_VERTICAL);
		createRow.setPadding(0, ExtraSettingsUi.dp(context, 12), 0, ExtraSettingsUi.dp(context, 8));
		EditText nameInput = new EditText(context);
		nameInput.setHint(R.string.mod_group_name_hint);
		nameInput.setSingleLine(true);
		nameInput.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		nameInput.setHintTextColor(ExtraSettingsUi.COLOR_MUTED);
		createRow.addView(nameInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		MaterialButton createBtn = ExtraSettingsUi.tonalButton(context, R.string.mod_group_create, R.drawable.ic_add_circle_24);
		LinearLayout.LayoutParams createBtnParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		createBtnParams.setMarginStart(ExtraSettingsUi.dp(context, 8));
		createRow.addView(createBtn, createBtnParams);
		root.addView(createRow);

		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(true);
		LinearLayout list = ExtraSettingsUi.vertical(context);
		scrollView.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 420));
		scrollParams.topMargin = ExtraSettingsUi.dp(context, 4);
		root.addView(scrollView, scrollParams);

		Runnable rebuildList = () -> populateGroupManageList(list, dialog);
		createBtn.setOnClickListener(v -> {
			try {
				String name = nameInput.getText() == null ? "" : nameInput.getText().toString();
				if (TextUtils.isEmpty(name.trim())) {
					actions.showMessage(context.getString(R.string.mod_group_name_required));
					return;
				}
				repository.createModGroup(name);
				nameInput.setText("");
				rebuildList.run();
				refreshList();
			} catch (Exception exception) {
				actions.showError(exception);
			}
		});
		rebuildList.run();
		return root;
	}

	private void populateGroupManageList(LinearLayout list, BottomSheetDialog dialog) {
		list.removeAllViews();
		List<String> groups = repository.listModGroups();
		List<ExtraSettingsRepository.ModEntry> allMods = repository.listInstalledModManifests();
		Set<String> userGroupNames = new LinkedHashSet<>(groups);
		Map<String, String> groupAssignments = repository.loadModGroupAssignments();
		if (groups.isEmpty()) {
			TextView empty = ExtraSettingsUi.body(context, R.string.mod_group_empty_manage);
			empty.setPadding(0, ExtraSettingsUi.dp(context, 12), 0, 0);
			list.addView(empty);
			return;
		}
		for (String groupName : groups) {
			int count = 0;
			for (ExtraSettingsRepository.ModEntry entry : allMods) {
				if (groupName.equals(groupIdForEntry(entry, userGroupNames, groupAssignments))) {
					count++;
				}
			}
			list.addView(buildGroupManageCard(groupName, count, dialog), groupCardParams(list));
		}
	}

	private LinearLayout.LayoutParams groupCardParams(LinearLayout list) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = list.getChildCount() == 0 ? ExtraSettingsUi.dp(context, 8) : ExtraSettingsUi.dp(context, 8);
		return params;
	}

	private View buildGroupManageCard(String groupName, int count, BottomSheetDialog dialog) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(context);
		card.setRadius(ExtraSettingsUi.dp(context, 12));
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		card.setStrokeWidth(ExtraSettingsUi.dp(context, 1));
		card.setStrokeColor(ExtraSettingsUi.COLOR_OUTLINE);
		card.setClickable(false);
		card.setFocusable(false);

		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 10), ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 10));
		card.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		LinearLayout textCol = ExtraSettingsUi.vertical(context);
		TextView title = ExtraSettingsUi.text(context, groupName, 16, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setSingleLine(true);
		title.setEllipsize(TextUtils.TruncateAt.END);
		textCol.addView(title);
		TextView meta = ExtraSettingsUi.caption(context, context.getString(R.string.mod_group_count_format, count));
		meta.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		metaParams.topMargin = ExtraSettingsUi.dp(context, 2);
		textCol.addView(meta, metaParams);
		row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		MaterialButton moveBtn = ExtraSettingsUi.iconButton(context, R.drawable.ic_folder_24);
		moveBtn.setContentDescription(context.getString(R.string.mod_group_move_to));
		moveBtn.setIconTint(ColorStateList.valueOf(ExtraSettingsUi.COLOR_PRIMARY));
		moveBtn.setOnClickListener(v -> showPickModsForGroupDialog(groupName, dialog));
		row.addView(moveBtn, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 40), ExtraSettingsUi.dp(context, 40)));

		MaterialButton renameBtn = ExtraSettingsUi.iconButton(context, R.drawable.ic_edit_24);
		renameBtn.setContentDescription(context.getString(R.string.mod_group_rename));
		renameBtn.setIconTint(ColorStateList.valueOf(ExtraSettingsUi.COLOR_PRIMARY));
		renameBtn.setOnClickListener(v -> showRenameGroupDialog(groupName, dialog));
		row.addView(renameBtn, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 40), ExtraSettingsUi.dp(context, 40)));

		MaterialButton deleteBtn = ExtraSettingsUi.iconButton(context, R.drawable.ic_delete_24);
		deleteBtn.setContentDescription(context.getString(R.string.mod_group_delete));
		deleteBtn.setIconTint(ColorStateList.valueOf(ExtraSettingsUi.COLOR_ERROR));
		deleteBtn.setOnClickListener(v -> confirmDeleteGroup(groupName, dialog));
		row.addView(deleteBtn, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 40), ExtraSettingsUi.dp(context, 40)));
		return card;
	}

	private void showRenameGroupDialog(String groupName, BottomSheetDialog parentSheet) {
		EditText input = new EditText(context);
		input.setHint(R.string.mod_group_name_hint);
		input.setSingleLine(true);
		input.setText(groupName);
		input.setSelection(groupName.length());
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.mod_group_rename_title)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (d, which) -> {
				String name = input.getText() == null ? "" : input.getText().toString();
				if (TextUtils.isEmpty(name.trim())) {
					actions.showMessage(context.getString(R.string.mod_group_name_required));
					return;
				}
				actions.runAsyncOperation(context.getString(R.string.status_busy_rename_mod_group), () -> {
					String renamed = repository.renameModGroup(groupName, name);
					return context.getString(R.string.status_rename_mod_group_done, renamed);
				});
				if (parentSheet != null) {
					parentSheet.dismiss();
				}
			})
			.show();
	}

	private void confirmDeleteGroup(String groupName, BottomSheetDialog parentSheet) {
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.mod_group_delete_title)
			.setMessage(context.getString(R.string.mod_group_delete_message, groupName))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (d, which) -> {
				actions.runAsyncOperation(context.getString(R.string.status_busy_delete_mod_group), () -> {
					repository.deleteModGroup(groupName);
					return context.getString(R.string.status_delete_mod_group_done);
				});
				if (parentSheet != null) {
					parentSheet.dismiss();
				}
			})
			.show();
	}

	private void showPickModsForGroupDialog(String targetGroupName, BottomSheetDialog parentSheet) {
		List<ExtraSettingsRepository.ModEntry> allMods = repository.listInstalledModManifests();
		Set<String> userGroups = new LinkedHashSet<>(repository.listModGroups());
		Map<String, String> groupAssignments = repository.loadModGroupAssignments();
		Set<String> checked = new HashSet<>();
		for (ExtraSettingsRepository.ModEntry entry : allMods) {
			if (targetGroupName.equals(groupIdForEntry(entry, userGroups, groupAssignments))) {
				checked.add(entry.modId);
			}
		}

		LinearLayout root = ExtraSettingsUi.vertical(context);
		root.setPadding(ExtraSettingsUi.dp(context, 8), ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 8), 0);
		EditText search = new EditText(context);
		search.setHint(R.string.mod_group_search_mods_hint);
		search.setSingleLine(true);
		search.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		search.setHintTextColor(ExtraSettingsUi.COLOR_MUTED);
		root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		ScrollView scrollView = new ScrollView(context);
		LinearLayout list = ExtraSettingsUi.vertical(context);
		scrollView.addView(list);
		LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 360));
		scrollParams.topMargin = ExtraSettingsUi.dp(context, 8);
		root.addView(scrollView, scrollParams);

		Runnable rebuild = () -> {
			list.removeAllViews();
			String query = search.getText() == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
			for (ExtraSettingsRepository.ModEntry entry : allMods) {
				String displayName = displayNameFor(entry);
				String haystack = (displayName + " " + entry.displayName + " " + entry.modId + " " + entry.authors).toLowerCase(Locale.ROOT);
				if (!query.isEmpty() && !haystack.contains(query)) {
					continue;
				}
				CheckBox checkBox = new CheckBox(context);
				checkBox.setText(emptyToDash(displayName) + "\n" + entry.modId);
				checkBox.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
				checkBox.setChecked(checked.contains(entry.modId));
				checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
					if (isChecked) {
						checked.add(entry.modId);
					} else {
						checked.remove(entry.modId);
					}
				});
				list.addView(checkBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			}
		};
		search.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) { rebuild.run(); }
			@Override public void afterTextChanged(Editable s) {}
		});
		rebuild.run();

		new MaterialAlertDialogBuilder(context)
			.setTitle(context.getString(R.string.mod_group_move_mods_title, targetGroupName))
			.setView(root)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (d, which) -> {
				List<ExtraSettingsRepository.ModEntry> toMove = new ArrayList<>();
				for (ExtraSettingsRepository.ModEntry entry : allMods) {
					if (checked.contains(entry.modId) && !targetGroupName.equals(groupIdForEntry(entry, userGroups, groupAssignments))) {
						toMove.add(entry);
					}
				}
				if (toMove.isEmpty()) {
					actions.showMessage(context.getString(R.string.mod_group_move_none_selected));
					return;
				}
				if (parentSheet != null) {
					parentSheet.dismiss();
				}
				batchMoveModsToGroup(toMove, targetGroupName);
			})
			.show();
	}

	private void showMoveSelectedToGroupDialog() {
		List<ExtraSettingsRepository.ModEntry> selected = selectedEntries();
		if (selected.isEmpty()) {
			actions.showMessage(context.getString(R.string.mod_batch_empty));
			return;
		}
		List<String> targets = new ArrayList<>();
		targets.add(MOD_GROUP_UNGROUPED);
		targets.addAll(repository.listModGroups());
		if (targets.isEmpty()) {
			actions.showMessage(context.getString(R.string.mod_group_no_targets));
			return;
		}
		String[] labels = new String[targets.size()];
		for (int i = 0; i < targets.size(); i++) {
			String id = targets.get(i);
			labels[i] = MOD_GROUP_UNGROUPED.equals(id) ? context.getString(R.string.mod_group_ungrouped) : id;
		}
		final int[] chosen = { 0 };
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.mod_group_pick_target_title)
			.setSingleChoiceItems(labels, 0, (dialog, which) -> chosen[0] = which)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				String targetId = targets.get(chosen[0]);
				selectedModIds.clear();
				notifySelectionModeChanged();
				updateSelectionActionsPanel();
				batchMoveModsToGroup(selected, targetId);
			})
			.show();
	}

	private void batchMoveModsToGroup(List<ExtraSettingsRepository.ModEntry> entries, String targetGroupId) {
		if (entries == null || entries.isEmpty()) {
			actions.showMessage(context.getString(R.string.mod_group_move_none_selected));
			return;
		}
		try {
			String label = groupLabel(targetGroupId, targetGroupId);
			int count = entries.size();
			List<String> order = new ArrayList<>(repository.loadModOrder(targetGroupId));
			for (ExtraSettingsRepository.ModEntry entry : entries) {
				order.remove(entry.modId);
				order.add(entry.modId);
				repository.moveModToGroup(entry, targetGroupId);
			}
			repository.saveModOrder(targetGroupId, order);
			actions.showMessage(context.getString(R.string.status_batch_move_mods_done, count, label));
			rememberChipScroll();
			refreshList();
		} catch (Exception exception) {
			actions.showError(exception);
			refreshList();
		}
	}

	// endregion

	// region Adapter + cards

	private final class ModsListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		private final List<ListItem> items = new ArrayList<>();

		void submit(List<ListItem> next) {
			items.clear();
			items.addAll(next);
			notifyDataSetChanged();
		}

		@Override
		public int getItemViewType(int position) {
			return items.get(position).type;
		}

		@Override
		public int getItemCount() {
			return items.size();
		}

		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			if (viewType == TYPE_GROUP) {
				return new GroupViewHolder(createGroupHeaderView());
			}
			if (viewType == TYPE_MOD) {
				return new ModViewHolder(createModCardShell());
			}
			if (viewType == TYPE_GHOST) {
				return new GhostViewHolder(createGhostView());
			}
			return new MessageViewHolder(createMessageCard());
		}

		@Override
		public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
			onBindViewHolder(holder, position, new ArrayList<>());
		}

		@Override
		public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, List<Object> payloads) {
			ListItem item = items.get(position);
			if (holder instanceof GroupViewHolder) {
				((GroupViewHolder) holder).bind(item.bucket, item.collapsed);
				return;
			}
			if (holder instanceof ModViewHolder) {
				ModViewHolder modHolder = (ModViewHolder) holder;
				if (!payloads.isEmpty()) {
					boolean selectionRelated = false;
					boolean expandRelated = false;
					for (Object payload : payloads) {
						if (PAYLOAD_SELECTION.equals(payload) || PAYLOAD_SELECTION_MODE.equals(payload)) {
							selectionRelated = true;
						}
						if (PAYLOAD_EXPAND.equals(payload)) {
							expandRelated = true;
						}
					}
					if (selectionRelated) {
						modHolder.applySelectionState(item.entry);
					}
					if (expandRelated) {
						modHolder.applyExpandState(item.entry);
					}
					if (selectionRelated || expandRelated) {
						return;
					}
				}
				modHolder.bind(item.groupId, item.entry);
				return;
			}
			if (holder instanceof GhostViewHolder) {
				((GhostViewHolder) holder).bind(item.groupGhost);
				return;
			}
			if (holder instanceof MessageViewHolder) {
				((MessageViewHolder) holder).bind(item);
			}
		}
	}

	private View createGroupHeaderView() {
		LinearLayout header = ExtraSettingsUi.horizontal(context);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 10), 0, ExtraSettingsUi.dp(context, 10));
		header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		TextView title = ExtraSettingsUi.text(context, "", 14, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, Typeface.BOLD);
		title.setTag("title");
		header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		MaterialButton expand = ExtraSettingsUi.iconButton(context, R.drawable.ic_expand_less_24);
		expand.setTag("expand");
		header.addView(expand, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 40), ExtraSettingsUi.dp(context, 40)));
		return header;
	}

	private View createModCardShell() {
		MaterialCardView card = ExtraSettingsUi.clickableCard(context);
		card.setRadius(ExtraSettingsUi.dp(context, 12));
		card.setUseCompatPadding(false);
		RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = ExtraSettingsUi.dp(context, 8);
		lp.bottomMargin = 0;
		card.setLayoutParams(lp);

		LinearLayout content = ExtraSettingsUi.vertical(context);
		content.setPadding(ExtraSettingsUi.dp(context, 12), 0, ExtraSettingsUi.dp(context, 12), 0);
		card.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		LinearLayout header = ExtraSettingsUi.horizontal(context);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(0, ExtraSettingsUi.dp(context, 12), 0, ExtraSettingsUi.dp(context, 12));
		header.setTag("header");

		ImageView handle = ExtraSettingsUi.icon(context, R.drawable.ic_drag_indicator_24, ExtraSettingsUi.COLOR_OUTLINE, 24);
		handle.setTag("handle");
		handle.setPadding(ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 4));
		header.addView(handle, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 32), ExtraSettingsUi.dp(context, 40)));

		LinearLayout textColumn = ExtraSettingsUi.vertical(context);
		textColumn.setGravity(Gravity.CENTER_VERTICAL);
		TextView title = ExtraSettingsUi.text(context, "", 16, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setTag("title");
		title.setSingleLine(true);
		title.setEllipsize(TextUtils.TruncateAt.END);
		textColumn.addView(title);
		TextView meta = ExtraSettingsUi.caption(context, "");
		meta.setTag("meta");
		meta.setSingleLine(true);
		meta.setEllipsize(TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		metaParams.topMargin = ExtraSettingsUi.dp(context, 2);
		textColumn.addView(meta, metaParams);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 12));
		header.addView(textColumn, textParams);

		MaterialSwitch switchView = new MaterialSwitch(context);
		switchView.setTag("switch");
		LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		switchParams.setMarginStart(ExtraSettingsUi.dp(context, 10));
		header.addView(switchView, switchParams);
		content.addView(header);

		LinearLayout details = ExtraSettingsUi.vertical(context);
		details.setTag("details");
		details.setVisibility(View.GONE);
		content.addView(details);
		return card;
	}

	private View createGhostView() {
		View ghost = new View(context);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(Color.argb(92, Color.red(ExtraSettingsUi.COLOR_PRIMARY_CONTAINER), Color.green(ExtraSettingsUi.COLOR_PRIMARY_CONTAINER), Color.blue(ExtraSettingsUi.COLOR_PRIMARY_CONTAINER)));
		bg.setCornerRadius(ExtraSettingsUi.dp(context, 12));
		bg.setStroke(ExtraSettingsUi.dp(context, 1), ExtraSettingsUi.COLOR_PRIMARY, ExtraSettingsUi.dp(context, 5), ExtraSettingsUi.dp(context, 4));
		ghost.setBackground(bg);
		ghost.setAlpha(0.55f);
		RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 56));
		lp.topMargin = ExtraSettingsUi.dp(context, 8);
		ghost.setLayoutParams(lp);
		return ghost;
	}

	private View createMessageCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = ExtraSettingsUi.dp(context, 8);
		card.setLayoutParams(lp);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.setTag("content");
		return card;
	}

	private final class GroupViewHolder extends RecyclerView.ViewHolder {
		private final TextView title;
		private final MaterialButton expand;
		private ModGroupBucket boundBucket;

		GroupViewHolder(View itemView) {
			super(itemView);
			title = itemView.findViewWithTag("title");
			expand = itemView.findViewWithTag("expand");
		}

		void bind(ModGroupBucket bucket, boolean collapsed) {
			boundBucket = bucket;
			if (bucket == null) {
				return;
			}
			title.setText(context.getString(R.string.mod_group_header_format, bucket.label, bucket.entries.size()));
			MaterialSymbols.applyButtonIcon(expand, collapsed ? R.drawable.ic_expand_more_24 : R.drawable.ic_expand_less_24, ColorStateList.valueOf(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT), 24);
			expand.setContentDescription(context.getString(collapsed ? R.string.mod_group_expand : R.string.mod_group_collapse));
			expand.setOnClickListener(v -> toggleGroupCollapsed(bucket.id));
			itemView.setOnLongClickListener(v -> startGroupDrag(v, itemView, bucket));
		}
	}

	private final class ModViewHolder extends RecyclerView.ViewHolder {
		private final MaterialCardView card;
		private final ImageView handle;
		private final TextView title;
		private final TextView meta;
		private final MaterialSwitch switchView;
		private final LinearLayout details;
		private ExtraSettingsRepository.ModEntry boundEntry;
		private String boundGroupId;
		private final boolean[] enabledState = new boolean[] { true };

		ModViewHolder(View itemView) {
			super(itemView);
			card = (MaterialCardView) itemView;
			handle = itemView.findViewWithTag("handle");
			title = itemView.findViewWithTag("title");
			meta = itemView.findViewWithTag("meta");
			switchView = itemView.findViewWithTag("switch");
			details = itemView.findViewWithTag("details");
		}

		void bind(String groupId, ExtraSettingsRepository.ModEntry entry) {
			boundGroupId = groupId;
			boundEntry = entry;
			if (entry == null) {
				return;
			}
			boolean enabled = cachedSettings != null && !isModDisabledSafe(entry);
			enabledState[0] = enabled;
			boolean selected = selectedModIds.contains(entry.modId);
			boolean expanded = expandedModIds.contains(entry.modId);
			boolean hasIssues = issuesByModId.containsKey(entry.modId);
			bindTitle(entry);
			meta.setText(compactMeta(entry));
			applyModCardStyle(card, enabled, selected, hasIssues);
			switchView.setOnCheckedChangeListener(null);
			switchView.setChecked(enabled);
			switchView.setEnabled(selectedModIds.isEmpty());
			switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
				try {
					repository.saveSetting(root -> repository.setModDisabled(root, entry.modId, !isChecked));
					cachedSettings = repository.loadSettingsJson();
					enabledState[0] = isChecked;
					applyModCardStyle(card, isChecked, selectedModIds.contains(entry.modId), issuesByModId.containsKey(entry.modId));
					// Re-evaluate dependency disabled issues when toggles change.
					rebuildModIssues(currentAllMods.isEmpty() ? repository.listInstalledModManifests() : currentAllMods);
					updateWarningBadge();
					if (adapter != null) {
						adapter.notifyItemRangeChanged(0, adapter.getItemCount(), PAYLOAD_SELECTION);
					}
				} catch (Exception exception) {
					buttonView.setChecked(!isChecked);
					actions.showError(exception);
				}
			});
			rebuildDetails(entry, expanded);
			View.OnClickListener itemClick = v -> {
				if (!selectedModIds.isEmpty()) {
					toggleSelected(entry.modId);
				} else {
					toggleCardExpanded(entry.modId, details);
				}
			};
			card.setOnClickListener(itemClick);
			ModGroupBucket bucket = findBucket(groupId);
			handle.setOnLongClickListener(v -> startModDrag(v, card, bucket, entry));
			handle.setOnClickListener(v -> {
				if (!selectedModIds.isEmpty()) {
					toggleSelected(entry.modId);
				}
			});
			card.setOnLongClickListener(v -> {
				toggleSelected(entry.modId);
				return true;
			});
		}

		void bindTitle(ExtraSettingsRepository.ModEntry entry) {
			title.setText(emptyToDash(displayNameFor(entry)));
		}

		void applySelectionState(ExtraSettingsRepository.ModEntry entry) {
			if (entry == null) {
				return;
			}
			boolean enabled = enabledState[0];
			boolean selected = selectedModIds.contains(entry.modId);
			applyModCardStyle(card, enabled, selected, issuesByModId.containsKey(entry.modId));
			switchView.setEnabled(selectedModIds.isEmpty());
			// Update select button label in details if present
			View select = details.findViewWithTag("select_btn");
			if (select instanceof MaterialButton) {
				((MaterialButton) select).setContentDescription(context.getString(selected ? R.string.mod_action_unselect : R.string.mod_action_select));
			}
		}

		void applyExpandState(ExtraSettingsRepository.ModEntry entry) {
			if (entry == null) {
				return;
			}
			boolean expanded = expandedModIds.contains(entry.modId);
			if (expanded && details.getChildCount() == 0) {
				rebuildDetails(entry, true);
			} else {
				setDetailsExpandedImmediately(details, expanded);
			}
		}

		private void rebuildDetails(ExtraSettingsRepository.ModEntry entry, boolean expanded) {
			cancelDetailsAnimation(details);
			details.removeAllViews();
			details.setPadding(0, 0, 0, ExtraSettingsUi.dp(context, 16));
			DashedDivider divider = new DashedDivider(context);
			details.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 1)));

			LinearLayout body = ExtraSettingsUi.vertical(context);
			body.setPadding(ExtraSettingsUi.dp(context, 36), ExtraSettingsUi.dp(context, 8), 0, 0);
			TextView description = ExtraSettingsUi.text(context, TextUtils.isEmpty(entry.description) ? context.getString(R.string.mod_description_empty) : entry.description, 13, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
			description.setLineSpacing(ExtraSettingsUi.dp(context, 2), 1.0f);
			boolean longDescription = isLongDescription(entry.description);
			boolean showFullDescription = fullDescriptionModIds.contains(entry.modId);
			if (longDescription && !showFullDescription) {
				description.setMaxLines(10);
				description.setEllipsize(TextUtils.TruncateAt.END);
			}
			body.addView(description);
			if (longDescription && !showFullDescription) {
				TextView more = ExtraSettingsUi.text(context, context.getString(R.string.mod_description_show_more), 13, ExtraSettingsUi.COLOR_PRIMARY, Typeface.BOLD);
				more.setPadding(0, ExtraSettingsUi.dp(context, 6), 0, 0);
				more.setOnClickListener(v -> {
					fullDescriptionModIds.add(entry.modId);
					animateHeightMutation(details, () -> {
						description.setMaxLines(Integer.MAX_VALUE);
						description.setEllipsize(null);
						more.setVisibility(View.GONE);
					});
				});
				body.addView(more);
			}
			addDetailRow(body, R.drawable.ic_code_24, displayCategory(entry));
			addPathDetailRow(body, entry);
			addDetailRow(body, R.drawable.ic_person_24, context.getString(R.string.mod_detail_author) + ": " + emptyToDash(entry.authors));
			addDetailRow(body, R.drawable.ic_layers_24, context.getString(R.string.mod_detail_dependencies) + ": " + dependenciesText(entry));
			if (!TextUtils.isEmpty(entry.minGameVersion)) {
				addDetailRow(body, R.drawable.ic_badge_24, context.getString(R.string.mod_detail_min_game_version) + ": " + entry.minGameVersion);
			}
			List<ModIssue> issues = issuesByModId.get(entry.modId);
			if (issues != null && !issues.isEmpty()) {
				for (ModIssue issue : issues) {
					addIssueDetailRow(body, issue.message);
				}
			}

			LinearLayout actionsRow = ExtraSettingsUi.horizontal(context);
			actionsRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
			MaterialButton select = detailIconButton(R.drawable.ic_check_circle_24, selectedModIds.contains(entry.modId) ? R.string.mod_action_unselect : R.string.mod_action_select);
			select.setTag("select_btn");
			MaterialButton note = detailIconButton(R.drawable.ic_edit_24, R.string.mod_note_action);
			MaterialButton info = detailIconButton(R.drawable.ic_info_24, R.string.mod_action_info);
			MaterialButton delete = detailIconButton(R.drawable.ic_delete_24, R.string.delete);
			select.setOnClickListener(v -> toggleSelected(entry.modId));
			note.setOnClickListener(v -> showModNoteDialog(entry));
			info.setOnClickListener(v -> showModDetails(entry, enabledState[0]));
			delete.setOnClickListener(v -> confirmDelete(entry));
			actionsRow.addView(select);
			actionsRow.addView(note);
			actionsRow.addView(info);
			actionsRow.addView(delete);
			LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			actionParams.topMargin = ExtraSettingsUi.dp(context, 12);
			body.addView(actionsRow, actionParams);
			details.addView(body);
			setDetailsExpandedImmediately(details, expanded);
		}
	}

	private final class GhostViewHolder extends RecyclerView.ViewHolder {
		GhostViewHolder(View itemView) {
			super(itemView);
		}

		void bind(boolean groupGhost) {
			ViewGroup.LayoutParams params = itemView.getLayoutParams();
			if (params != null) {
				params.height = ExtraSettingsUi.dp(context, groupGhost ? 68 : 56);
				itemView.setLayoutParams(params);
			}
		}
	}

	private final class MessageViewHolder extends RecyclerView.ViewHolder {
		private final LinearLayout content;

		MessageViewHolder(View itemView) {
			super(itemView);
			content = itemView.findViewWithTag("content");
		}

		void bind(ListItem item) {
			content.removeAllViews();
			if (item.type == TYPE_ERROR) {
				content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_error_outline_24, R.string.error_operation_failed, 0, null));
				String message = item.error == null || item.error.getMessage() == null ? String.valueOf(item.error) : item.error.getMessage();
				ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, message));
			} else {
				content.setGravity(Gravity.CENTER_HORIZONTAL);
				content.addView(ExtraSettingsUi.iconCircle(context, R.drawable.ic_extension_24, ExtraSettingsUi.COLOR_SECONDARY_CONTAINER, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT));
				ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, item.emptyTextRes));
			}
		}
	}

	private boolean isModDisabledSafe(ExtraSettingsRepository.ModEntry entry) {
		try {
			return cachedSettings != null && repository.isModDisabled(cachedSettings, entry);
		} catch (Exception ignored) {
			return false;
		}
	}

	private void toggleCardExpanded(String modId, View details) {
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
		if (width <= 0 && recyclerView != null) {
			width = recyclerView.getWidth() - ExtraSettingsUi.dp(context, 32);
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

	private void addPathDetailRow(LinearLayout parent, ExtraSettingsRepository.ModEntry entry) {
		if (entry == null || TextUtils.isEmpty(entry.relativePath)) {
			return;
		}
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.icon(context, R.drawable.ic_article_24, ExtraSettingsUi.COLOR_MUTED, 16));
		TextView text = ExtraSettingsUi.caption(context, entry.relativePath);
		text.setTextColor(ExtraSettingsUi.COLOR_PRIMARY);
		SpannableString spannable = new SpannableString(entry.relativePath);
		spannable.setSpan(new UnderlineSpan(), 0, spannable.length(), 0);
		text.setText(spannable);
		text.setClickable(true);
		text.setFocusable(true);
		text.setOnClickListener(v -> openModFolder(entry));
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 6));
		row.addView(text, textParams);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(context, 6);
		parent.addView(row, params);
	}

	private void addIssueDetailRow(LinearLayout parent, String message) {
		if (TextUtils.isEmpty(message)) {
			return;
		}
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.icon(context, R.drawable.ic_error_outline_24, ExtraSettingsUi.COLOR_WARNING, 16));
		TextView text = ExtraSettingsUi.caption(context, message);
		text.setTextColor(ExtraSettingsUi.COLOR_WARNING);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 6));
		row.addView(text, textParams);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(context, 6);
		parent.addView(row, params);
	}

	private String compactMeta(ExtraSettingsRepository.ModEntry entry) {
		List<String> parts = new ArrayList<>();
		if (!TextUtils.isEmpty(noteFor(entry.modId)) && !TextUtils.isEmpty(entry.displayName)) {
			parts.add(entry.displayName);
		}
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
		return entry.dependencyLabels.isEmpty() ? "—" : TextUtils.join(", ", entry.dependencyLabels);
	}

	private void applyModCardStyle(MaterialCardView card, boolean enabled, boolean selected, boolean hasIssues) {
		int background;
		int border;
		if (selected) {
			background = Color.rgb(30, 50, 39);
			border = ExtraSettingsUi.COLOR_PRIMARY;
		} else if (hasIssues) {
			background = Color.rgb(48, 40, 24);
			border = ExtraSettingsUi.COLOR_WARNING;
		} else {
			background = ExtraSettingsUi.COLOR_SURFACE_CONTAINER;
			border = enabled ? Color.rgb(72, 104, 84) : ExtraSettingsUi.COLOR_OUTLINE;
		}
		card.setCardBackgroundColor(background);
		card.setStrokeWidth(ExtraSettingsUi.dp(context, selected || hasIssues ? 2 : 1));
		card.setStrokeColor(border);
	}

	// endregion

	private String emptyToDash(String value) {
		return TextUtils.isEmpty(value) ? "—" : value;
	}

	private String noteFor(String modId) {
		if (TextUtils.isEmpty(modId)) {
			return "";
		}
		String note = modNotesById.get(modId);
		return note == null ? "" : note.trim();
	}

	private String displayNameFor(ExtraSettingsRepository.ModEntry entry) {
		if (entry == null) {
			return "";
		}
		String note = noteFor(entry.modId);
		return TextUtils.isEmpty(note) ? (entry.displayName == null ? "" : entry.displayName) : note;
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

	private void showModDetails(ExtraSettingsRepository.ModEntry entry, boolean enabled) {
		StringBuilder message = new StringBuilder();
		String note = noteFor(entry.modId);
		if (!TextUtils.isEmpty(note)) {
			appendLine(message, context.getString(R.string.mod_note_action), note);
		}
		appendLine(message, context.getString(R.string.mod_detail_status), enabled ? context.getString(R.string.mod_enabled) : context.getString(R.string.mod_disabled));
		appendLine(message, "ID", entry.modId);
		appendLine(message, context.getString(R.string.mod_detail_category), displayCategory(entry));
		appendLine(message, context.getString(R.string.mod_detail_version), entry.version);
		appendLine(message, context.getString(R.string.mod_detail_min_game_version), entry.minGameVersion);
		appendLine(message, context.getString(R.string.mod_detail_author), entry.authors);
		appendLine(message, context.getString(R.string.mod_detail_files), context.getString(R.string.mod_detail_files_format,
			entry.declaredHasPck ? (entry.hasPck ? "PCK" : "PCK✗") : (entry.hasPck ? "PCK?" : "—"),
			entry.declaredHasDll ? (entry.hasDll ? "DLL" : "DLL✗") : (entry.hasDll ? "DLL?" : "—")));
		appendLine(message, context.getString(R.string.mod_detail_dependencies), entry.dependencyLabels.isEmpty() ? "—" : TextUtils.join(", ", entry.dependencyLabels));
		appendLine(message, context.getString(R.string.mod_detail_path), entry.relativePath);
		List<ModIssue> issues = issuesByModId.get(entry.modId);
		if (issues != null && !issues.isEmpty()) {
			message.append('\n').append(context.getString(R.string.mod_issues_title)).append(":\n");
			for (ModIssue issue : issues) {
				message.append("• ").append(issue.message).append('\n');
			}
		}
		if (!TextUtils.isEmpty(entry.description)) {
			message.append('\n').append(entry.description);
		}
		new MaterialAlertDialogBuilder(context)
			.setTitle(displayNameFor(entry))
			.setMessage(message.toString().trim())
			.setNeutralButton(R.string.mod_detail_open_folder, (dialog, which) -> openModFolder(entry))
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}

	private void showModNoteDialog(ExtraSettingsRepository.ModEntry entry) {
		EditText input = new EditText(context);
		input.setHint(R.string.mod_note_hint);
		input.setSingleLine(true);
		input.setText(noteFor(entry.modId));
		input.setSelection(input.getText() == null ? 0 : input.getText().length());
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.mod_note_title)
			.setMessage(entry.displayName + "\n" + entry.modId)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				try {
					String note = input.getText() == null ? "" : input.getText().toString();
					repository.setModNote(entry.modId, note);
					if (TextUtils.isEmpty(note.trim())) {
						modNotesById.remove(entry.modId);
						actions.showMessage(context.getString(R.string.mod_note_cleared));
					} else {
						modNotesById.put(entry.modId, note.trim());
						actions.showMessage(context.getString(R.string.mod_note_saved));
					}
					refreshList();
				} catch (Exception exception) {
					actions.showError(exception);
				}
			})
			.show();
	}

	private void openModFolder(ExtraSettingsRepository.ModEntry entry) {
		if (entry == null) {
			return;
		}
		File folder = entry.directory();
		if (folder == null || !folder.isDirectory()) {
			folder = entry.manifestFile;
		}
		try {
			context.startActivity(FileBrowserActivity.createIntent(context, folder));
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private void updateWarningBadge() {
		if (warningAction == null || warningCountView == null) {
			return;
		}
		int problemModCount = issuesByModId.size();
		if (problemModCount <= 0) {
			warningAction.setVisibility(View.GONE);
			return;
		}
		warningAction.setVisibility(View.VISIBLE);
		warningCountView.setText(context.getString(R.string.mod_issues_count_format, problemModCount));
		warningAction.setContentDescription(context.getString(R.string.mod_issues_summary_format, problemModCount));
	}

	private String resolveCurrentGameVersion() {
		try {
			LaunchProfileManager.GamePayload payload = new LaunchProfileManager(context).getSelectedPayload();
			if (payload != null && !TextUtils.isEmpty(payload.version)) {
				return payload.version.trim();
			}
		} catch (Exception ignored) {
		}
		return "";
	}

	private void rebuildModIssues(List<ExtraSettingsRepository.ModEntry> allMods) {
		issuesByModId.clear();
		currentIssues.clear();
		if (allMods == null || allMods.isEmpty()) {
			return;
		}
		// Only surface issues for enabled MODs; disabled ones are intentionally inactive.
		List<ExtraSettingsRepository.ModEntry> enabledMods = new ArrayList<>();
		Map<String, ExtraSettingsRepository.ModEntry> byId = new HashMap<>();
		Map<String, ExtraSettingsRepository.ModEntry> byPckName = new HashMap<>();
		for (ExtraSettingsRepository.ModEntry entry : allMods) {
			byId.put(entry.modId, entry);
			if (!TextUtils.isEmpty(entry.pckName)) {
				byPckName.put(entry.pckName, entry);
			}
			if (!isModDisabledSafe(entry)) {
				enabledMods.add(entry);
			}
		}
		if (enabledMods.isEmpty()) {
			return;
		}

		// Check only the flat ModSettings.ModList order saved for the game.
		// Missing entries have no explicit manual order, and UI groups must not imply one.
		Map<String, Integer> orderIndex = loadRuntimeManualOrderIndex();

		for (ExtraSettingsRepository.ModEntry entry : enabledMods) {
			List<ModIssue> issues = new ArrayList<>();

			if (entry.declaredHasPck && !entry.hasPck) {
				issues.add(new ModIssue(entry.modId, displayNameFor(entry), ModIssue.TYPE_MISSING_PCK,
					context.getString(R.string.mod_issue_missing_pck, entry.modId)));
			}
			if (entry.declaredHasDll && !entry.hasDll) {
				issues.add(new ModIssue(entry.modId, displayNameFor(entry), ModIssue.TYPE_MISSING_DLL,
					context.getString(R.string.mod_issue_missing_dll, entry.modId)));
			}

			if (!TextUtils.isEmpty(entry.minGameVersion)) {
				if (TextUtils.isEmpty(currentGameVersion)) {
					issues.add(new ModIssue(entry.modId, displayNameFor(entry), ModIssue.TYPE_GAME_VERSION,
						context.getString(R.string.mod_issue_min_game_version_unknown, entry.minGameVersion)));
				} else if (compareVersions(currentGameVersion, entry.minGameVersion) < 0) {
					issues.add(new ModIssue(entry.modId, displayNameFor(entry), ModIssue.TYPE_GAME_VERSION,
						context.getString(R.string.mod_issue_min_game_version, entry.minGameVersion, currentGameVersion)));
				}
			}

			for (ExtraSettingsRepository.ModDependency dependency : entry.dependencies) {
				if (TextUtils.isEmpty(dependency.id)) {
					continue;
				}
				ExtraSettingsRepository.ModEntry depEntry = findInstalledDependency(dependency.id, byId, byPckName);
				if (depEntry == null) {
					issues.add(new ModIssue(
						entry.modId,
						displayNameFor(entry),
						ModIssue.TYPE_DEPENDENCY,
						context.getString(R.string.mod_issue_missing_dependency, dependency.displayLabel()),
						dependency.id
					));
					continue;
				}
				boolean depDisabled = isModDisabledSafe(depEntry);
				if (depDisabled) {
					issues.add(new ModIssue(
						entry.modId,
						displayNameFor(entry),
						ModIssue.TYPE_DEPENDENCY_DISABLED,
						context.getString(R.string.mod_issue_dependency_disabled, dependency.id),
						depEntry.modId
					));
					// Disabled deps are not part of the active load order.
					continue;
				}
				if (!TextUtils.isEmpty(dependency.minVersion)
					&& !TextUtils.isEmpty(depEntry.version)
					&& compareVersions(depEntry.version, dependency.minVersion) < 0) {
					issues.add(new ModIssue(
						entry.modId,
						displayNameFor(entry),
						ModIssue.TYPE_DEPENDENCY,
						context.getString(R.string.mod_issue_dependency_version, dependency.id, depEntry.version, dependency.minVersion),
						depEntry.modId
					));
				}
				Integer selfIndex = orderIndex.get(entry.modId);
				Integer depIndex = orderIndex.get(depEntry.modId);
				if (selfIndex != null && depIndex != null && depIndex > selfIndex) {
					issues.add(new ModIssue(
						entry.modId,
						displayNameFor(entry),
						ModIssue.TYPE_LOAD_ORDER,
						context.getString(R.string.mod_issue_load_order, depEntry.modId),
						depEntry.modId
					));
				}
			}

			if (!issues.isEmpty()) {
				issuesByModId.put(entry.modId, issues);
				currentIssues.addAll(issues);
			}
		}
	}

	private ExtraSettingsRepository.ModEntry findInstalledDependency(
		String dependencyId,
		Map<String, ExtraSettingsRepository.ModEntry> byId,
		Map<String, ExtraSettingsRepository.ModEntry> byPckName
	) {
		if (TextUtils.isEmpty(dependencyId)) {
			return null;
		}
		ExtraSettingsRepository.ModEntry entry = byId.get(dependencyId);
		if (entry != null) {
			return entry;
		}
		entry = byPckName.get(dependencyId);
		if (entry != null) {
			return entry;
		}
		// Case-insensitive fallback for loosely authored manifests.
		for (Map.Entry<String, ExtraSettingsRepository.ModEntry> candidate : byId.entrySet()) {
			if (dependencyId.equalsIgnoreCase(candidate.getKey())) {
				return candidate.getValue();
			}
		}
		for (Map.Entry<String, ExtraSettingsRepository.ModEntry> candidate : byPckName.entrySet()) {
			if (dependencyId.equalsIgnoreCase(candidate.getKey())) {
				return candidate.getValue();
			}
		}
		return null;
	}

	private Map<String, Integer> loadRuntimeManualOrderIndex() {
		Map<String, Integer> orderIndex = new HashMap<>();
		try {
			JSONObject settings = cachedSettings != null ? cachedSettings : repository.loadSettingsJson();
			List<String> order = repository.loadRuntimeModListOrder(settings);
			for (int i = 0; i < order.size(); i++) {
				String id = order.get(i);
				if (!TextUtils.isEmpty(id)) {
					// Match the game's dictionary assignment: later duplicate entries win.
					orderIndex.put(id, i);
				}
			}
		} catch (Exception ignored) {
		}
		return orderIndex;
	}

	private Map<String, Integer> fallbackScanOrderIndex(List<ExtraSettingsRepository.ModEntry> mods) {
		Map<String, Integer> fallbackIndex = new HashMap<>();
		for (int i = 0; i < mods.size(); i++) {
			ExtraSettingsRepository.ModEntry entry = mods.get(i);
			if (entry != null && !TextUtils.isEmpty(entry.modId)) {
				fallbackIndex.putIfAbsent(entry.modId, i);
			}
		}
		return fallbackIndex;
	}

	private Comparator<ExtraSettingsRepository.ModEntry> runtimeManualOrderComparator(Map<String, Integer> manualIndex, Map<String, Integer> fallbackIndex) {
		return Comparator
			.comparingInt((ExtraSettingsRepository.ModEntry entry) -> runtimeManualPriority(entry == null ? "" : entry.modId, manualIndex, fallbackIndex))
			.thenComparing(entry -> entry == null || entry.relativePath == null ? "" : entry.relativePath, String::compareToIgnoreCase);
	}

	private int runtimeManualPriority(String modId, Map<String, Integer> manualIndex, Map<String, Integer> fallbackIndex) {
		Integer manual = manualIndex.get(modId);
		if (manual != null) {
			return manual;
		}
		return 1_000_000 + fallbackIndex.getOrDefault(modId, Integer.MAX_VALUE / 2);
	}

	/**
	 * Loose semantic compare for game/mod versions (leading {@code v} allowed).
	 * Returns negative if left &lt; right, positive if left &gt; right, 0 if equal/unparseable both sides match.
	 */
	private int compareVersions(String left, String right) {
		int[] leftParts = parseVersionParts(left);
		int[] rightParts = parseVersionParts(right);
		if (leftParts == null || rightParts == null) {
			return String.valueOf(left).compareToIgnoreCase(String.valueOf(right));
		}
		int len = Math.max(leftParts.length, rightParts.length);
		for (int i = 0; i < len; i++) {
			int l = i < leftParts.length ? leftParts[i] : 0;
			int r = i < rightParts.length ? rightParts[i] : 0;
			if (l != r) {
				return Integer.compare(l, r);
			}
		}
		return 0;
	}

	private int[] parseVersionParts(String raw) {
		if (TextUtils.isEmpty(raw)) {
			return null;
		}
		String value = raw.trim();
		if (value.startsWith("v") || value.startsWith("V")) {
			value = value.substring(1);
		}
		int cut = value.length();
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '-' || c == '+' || c == ' ') {
				cut = i;
				break;
			}
		}
		value = value.substring(0, cut);
		String[] chunks = value.split("\\.");
		if (chunks.length == 0) {
			return null;
		}
		int[] parts = new int[chunks.length];
		for (int i = 0; i < chunks.length; i++) {
			String chunk = chunks[i].replaceAll("[^0-9].*$", "");
			if (chunk.isEmpty()) {
				return null;
			}
			try {
				parts[i] = Integer.parseInt(chunk);
			} catch (NumberFormatException exception) {
				return null;
			}
		}
		return parts;
	}

	private void showModIssuesSheet() {
		if (issuesSheet != null) {
			try {
				issuesSheet.dismiss();
			} catch (Exception ignored) {
			}
		}
		BottomSheetDialog dialog = new BottomSheetDialog(context);
		issuesSheet = dialog;
		dialog.setContentView(buildModIssuesContent(dialog));
		configureModIssuesBottomSheet(dialog);
		dialog.show();
	}

	/**
	 * Opens at the normal content-height expanded state (not full-screen).
	 * Sheet body is not draggable so list scrolling never dismisses it;
	 * only the top handle can pull down to close or pull up to re-expand.
	 */
	private void configureModIssuesBottomSheet(BottomSheetDialog dialog) {
		dialog.setOnShowListener(unused -> {
			Window window = dialog.getWindow();
			if (window != null) {
				window.setDimAmount(0.56f);
				window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
			}
			FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
			if (bottomSheet != null) {
				// Keep wrap-content so Material sizes to content, not MATCH_PARENT full screen.
				ViewGroup.LayoutParams params = bottomSheet.getLayoutParams();
				if (params != null) {
					params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
					bottomSheet.setLayoutParams(params);
				}
			}
			BottomSheetBehavior<FrameLayout> behavior = dialog.getBehavior();
			behavior.setFitToContents(true);
			behavior.setSkipCollapsed(true);
			behavior.setPeekHeight(BottomSheetBehavior.PEEK_HEIGHT_AUTO);
			behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
			behavior.setDraggable(false);
		});
	}

	private View buildModIssuesContent(BottomSheetDialog dialog) {
		LinearLayout root = ExtraSettingsUi.vertical(context);
		root.setPadding(ExtraSettingsUi.dp(context, 24), ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 24), ExtraSettingsUi.dp(context, 24));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		float radius = ExtraSettingsUi.dp(context, 28);
		bg.setCornerRadii(new float[] { radius, radius, radius, radius, 0, 0, 0, 0 });
		root.setBackground(bg);

		// Large hit target for handle-only drag/dismiss.
		FrameLayout handleTarget = new FrameLayout(context);
		handleTarget.setPadding(0, ExtraSettingsUi.dp(context, 10), 0, ExtraSettingsUi.dp(context, 12));
		View handle = new View(context);
		GradientDrawable handleBg = new GradientDrawable();
		handleBg.setColor(ExtraSettingsUi.COLOR_OUTLINE);
		handleBg.setCornerRadius(ExtraSettingsUi.dp(context, 2));
		handle.setBackground(handleBg);
		handleTarget.addView(handle, new FrameLayout.LayoutParams(ExtraSettingsUi.dp(context, 40), ExtraSettingsUi.dp(context, 4), Gravity.CENTER));
		root.addView(handleTarget, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 36)));
		attachIssuesSheetHandle(handleTarget, dialog);

		root.addView(ExtraSettingsUi.sectionTitle(context, context.getString(R.string.mod_issues_title)));
		if (issuesByModId.isEmpty()) {
			TextView empty = ExtraSettingsUi.body(context, R.string.mod_issues_empty);
			empty.setPadding(0, ExtraSettingsUi.dp(context, 12), 0, 0);
			root.addView(empty);
			return root;
		}

		TextView summary = ExtraSettingsUi.body(context, context.getString(R.string.mod_issues_summary_format, issuesByModId.size()));
		summary.setTextColor(ExtraSettingsUi.COLOR_WARNING);
		LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		summaryParams.bottomMargin = ExtraSettingsUi.dp(context, 8);
		root.addView(summary, summaryParams);

		boolean hasOrderIssue = false;
		boolean hasDisabledDep = false;
		for (ModIssue issue : currentIssues) {
			if (ModIssue.TYPE_LOAD_ORDER.equals(issue.type)) {
				hasOrderIssue = true;
			}
			if (ModIssue.TYPE_DEPENDENCY_DISABLED.equals(issue.type)) {
				hasDisabledDep = true;
			}
		}
		LinearLayout actions = ExtraSettingsUi.vertical(context);
		LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		actionsParams.bottomMargin = ExtraSettingsUi.dp(context, 8);
		if (hasOrderIssue && hasDisabledDep) {
			MaterialButton fixAll = ExtraSettingsUi.tonalButton(context, R.string.mod_issues_auto_fix_all, R.drawable.ic_build_24);
			fixAll.setOnClickListener(v -> runIssueAutoFix(dialog, true, true));
			actions.addView(fixAll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		}
		if (hasDisabledDep) {
			MaterialButton enableDeps = ExtraSettingsUi.tonalButton(context, R.string.mod_issues_auto_enable_deps, R.drawable.ic_check_circle_24);
			LinearLayout.LayoutParams enableParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			enableParams.topMargin = ExtraSettingsUi.dp(context, 8);
			enableDeps.setOnClickListener(v -> runIssueAutoFix(dialog, true, false));
			actions.addView(enableDeps, enableParams);
		}
		if (hasOrderIssue) {
			MaterialButton fixOrder = ExtraSettingsUi.tonalButton(context, R.string.mod_issues_auto_fix_order, R.drawable.ic_sort_24);
			LinearLayout.LayoutParams fixParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			fixParams.topMargin = ExtraSettingsUi.dp(context, 8);
			fixOrder.setOnClickListener(v -> runIssueAutoFix(dialog, false, true));
			actions.addView(fixOrder, fixParams);
		}
		if (actions.getChildCount() > 0) {
			root.addView(actions, actionsParams);
		}

		NestedScrollView scrollView = new NestedScrollView(context);
		scrollView.setFillViewport(false);
		scrollView.setNestedScrollingEnabled(true);
		// Content gestures must never be treated as sheet drag.
		scrollView.setOnTouchListener((view, event) -> {
			view.getParent().requestDisallowInterceptTouchEvent(true);
			return false;
		});
		LinearLayout list = ExtraSettingsUi.vertical(context);
		for (Map.Entry<String, List<ModIssue>> entry : issuesByModId.entrySet()) {
			List<ModIssue> issues = entry.getValue();
			if (issues == null || issues.isEmpty()) {
				continue;
			}
			ExtraSettingsRepository.ModEntry modEntry = findCurrentModEntry(entry.getKey());
			View card = buildIssueCard(entry.getKey(), modEntry, issues);
			LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			cardParams.topMargin = ExtraSettingsUi.dp(context, 8);
			list.addView(card, cardParams);
		}
		scrollView.addView(list, new NestedScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		// Cap scroll height so sheet stays content-expanded and can still scroll long lists.
		int maxScroll = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.55f);
		LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		scrollView.setMinimumHeight(0);
		root.addView(scrollView, scrollParams);
		scrollView.post(() -> {
			if (scrollView.getChildCount() == 0) {
				return;
			}
			View child = scrollView.getChildAt(0);
			int contentHeight = child.getMeasuredHeight();
			ViewGroup.LayoutParams lp = scrollView.getLayoutParams();
			if (lp != null) {
				lp.height = Math.min(Math.max(contentHeight, 1), maxScroll);
				scrollView.setLayoutParams(lp);
			}
		});
		return root;
	}

	private View buildIssueCard(String modId, ExtraSettingsRepository.ModEntry modEntry, List<ModIssue> issues) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		card.setRadius(ExtraSettingsUi.dp(context, 12));
		card.setCardBackgroundColor(Color.rgb(54, 33, 31));
		card.setStrokeColor(ExtraSettingsUi.COLOR_ERROR);
		card.setStrokeWidth(ExtraSettingsUi.dp(context, 3));

		LinearLayout content = ExtraSettingsUi.vertical(context);
		content.setPadding(ExtraSettingsUi.dp(context, 14), ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 14), ExtraSettingsUi.dp(context, 12));
		card.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		String titleText = issues.isEmpty() ? modId : issues.get(0).displayName;
		TextView title = ExtraSettingsUi.text(context, emptyToDash(titleText), 16, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setSingleLine(true);
		title.setEllipsize(TextUtils.TruncateAt.END);
		content.addView(title);

		TextView meta = ExtraSettingsUi.caption(context, modEntry == null ? modId : compactMeta(modEntry));
		meta.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		meta.setSingleLine(true);
		meta.setEllipsize(TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		metaParams.topMargin = ExtraSettingsUi.dp(context, 2);
		content.addView(meta, metaParams);

		for (int i = 0; i < issues.size(); i++) {
			content.addView(buildIssueWarningRow(issues.get(i)), issueRowParams(i == 0));
		}
		return card;
	}

	private LinearLayout.LayoutParams issueRowParams(boolean first) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(context, first ? 10 : 6);
		return params;
	}

	private View buildIssueWarningRow(ModIssue issue) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.TOP);
		ImageView icon = ExtraSettingsUi.icon(context, R.drawable.ic_error_outline_24, ExtraSettingsUi.COLOR_ERROR, 16);
		LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 16), ExtraSettingsUi.dp(context, 16));
		iconParams.topMargin = ExtraSettingsUi.dp(context, 1);
		row.addView(icon, iconParams);

		TextView text = ExtraSettingsUi.text(context, "", 13, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.NORMAL);
		text.setLineSpacing(ExtraSettingsUi.dp(context, 2), 1.0f);
		text.setText(highlightIssueMessage(issue));
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 7));
		row.addView(text, textParams);
		return row;
	}

	private CharSequence highlightIssueMessage(ModIssue issue) {
		String message = issue == null ? "" : issue.message;
		SpannableStringBuilder builder = new SpannableStringBuilder(message);
		String key = issueHighlightKey(issue);
		if (TextUtils.isEmpty(key) || TextUtils.isEmpty(message)) {
			return builder;
		}
		String lowerMessage = message.toLowerCase(Locale.ROOT);
		String lowerKey = key.toLowerCase(Locale.ROOT);
		int start = lowerMessage.indexOf(lowerKey);
		while (start >= 0) {
			int end = start + key.length();
			builder.setSpan(new ForegroundColorSpan(ExtraSettingsUi.COLOR_ERROR), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			start = lowerMessage.indexOf(lowerKey, end);
		}
		return builder;
	}

	private String issueHighlightKey(ModIssue issue) {
		if (issue == null) {
			return "";
		}
		if (!TextUtils.isEmpty(issue.relatedModId)) {
			return issue.relatedModId;
		}
		if (ModIssue.TYPE_MISSING_PCK.equals(issue.type) || ModIssue.TYPE_MISSING_DLL.equals(issue.type)) {
			return issue.modId;
		}
		return "";
	}

	private ExtraSettingsRepository.ModEntry findCurrentModEntry(String modId) {
		if (TextUtils.isEmpty(modId)) {
			return null;
		}
		for (ExtraSettingsRepository.ModEntry entry : currentAllMods) {
			if (entry != null && modId.equals(entry.modId)) {
				return entry;
			}
		}
		return null;
	}

	/** Handle-only sheet control: pull down to dismiss, pull up to re-expand. */
	private void attachIssuesSheetHandle(View handleTarget, BottomSheetDialog dialog) {
		final float[] downRawY = new float[1];
		int dismissDistance = ExtraSettingsUi.dp(context, 36);
		int expandDistance = ExtraSettingsUi.dp(context, 24);
		handleTarget.setOnTouchListener((view, event) -> {
			BottomSheetBehavior<FrameLayout> behavior = dialog.getBehavior();
			switch (event.getActionMasked()) {
				case MotionEvent.ACTION_DOWN:
					downRawY[0] = event.getRawY();
					view.getParent().requestDisallowInterceptTouchEvent(true);
					return true;
				case MotionEvent.ACTION_MOVE:
					return true;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					view.getParent().requestDisallowInterceptTouchEvent(false);
					float delta = event.getRawY() - downRawY[0];
					if (delta >= dismissDistance) {
						dialog.dismiss();
					} else if (delta <= -expandDistance) {
						behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
					}
					return true;
				default:
					return true;
			}
		});
	}

	private void runIssueAutoFix(BottomSheetDialog dialog, boolean enableDeps, boolean fixOrder) {
		try {
			String message;
			if (enableDeps && fixOrder) {
				int enabled = autoEnableDisabledDependencies();
				autoFixLoadOrder();
				message = context.getString(R.string.mod_issues_auto_fix_all_done)
					+ (enabled > 0 ? " " + context.getString(R.string.mod_issues_auto_enable_deps_done, enabled) : "");
			} else if (enableDeps) {
				int enabled = autoEnableDisabledDependencies();
				message = context.getString(R.string.mod_issues_auto_enable_deps_done, enabled);
			} else {
				autoFixLoadOrder();
				message = context.getString(R.string.mod_issues_auto_fix_done);
			}
			dialog.dismiss();
			actions.showMessage(message);
			refreshList();
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	/** Enables installed dependencies that are currently disabled. Returns count enabled. */
	private int autoEnableDisabledDependencies() throws Exception {
		Set<String> toEnable = new LinkedHashSet<>();
		for (ModIssue issue : currentIssues) {
			if (ModIssue.TYPE_DEPENDENCY_DISABLED.equals(issue.type) && !TextUtils.isEmpty(issue.relatedModId)) {
				toEnable.add(issue.relatedModId);
			}
		}
		if (toEnable.isEmpty()) {
			// Re-scan in case issue list is stale.
			List<ExtraSettingsRepository.ModEntry> allMods = repository.listInstalledModManifests();
			Map<String, ExtraSettingsRepository.ModEntry> byId = new HashMap<>();
			Map<String, ExtraSettingsRepository.ModEntry> byPckName = new HashMap<>();
			for (ExtraSettingsRepository.ModEntry entry : allMods) {
				byId.put(entry.modId, entry);
				if (!TextUtils.isEmpty(entry.pckName)) {
					byPckName.put(entry.pckName, entry);
				}
			}
			for (ExtraSettingsRepository.ModEntry entry : allMods) {
				if (isModDisabledSafe(entry)) {
					continue;
				}
				for (ExtraSettingsRepository.ModDependency dependency : entry.dependencies) {
					ExtraSettingsRepository.ModEntry dep = findInstalledDependency(dependency.id, byId, byPckName);
					if (dep != null && isModDisabledSafe(dep)) {
						toEnable.add(dep.modId);
					}
				}
			}
		}
		if (toEnable.isEmpty()) {
			return 0;
		}
		repository.saveSetting(root -> {
			for (String modId : toEnable) {
				repository.setModDisabled(root, modId, false);
			}
		});
		cachedSettings = repository.loadSettingsJson();
		return toEnable.size();
	}

	/**
	 * Rewrites the flat runtime MOD order used by the game's ModSettings.ModList.
	 * UI groups remain only presentation/file organization and are not part of runtime sorting.
	 */
	private void autoFixLoadOrder() throws Exception {
		List<ExtraSettingsRepository.ModEntry> allMods = repository.listInstalledModManifests();
		if (allMods.isEmpty()) {
			return;
		}
		List<ExtraSettingsRepository.ModEntry> enabled = new ArrayList<>();
		List<ExtraSettingsRepository.ModEntry> disabled = new ArrayList<>();
		Map<String, ExtraSettingsRepository.ModEntry> byId = new HashMap<>();
		Map<String, ExtraSettingsRepository.ModEntry> byPckName = new HashMap<>();
		for (ExtraSettingsRepository.ModEntry entry : allMods) {
			byId.put(entry.modId, entry);
			if (!TextUtils.isEmpty(entry.pckName)) {
				byPckName.put(entry.pckName, entry);
			}
			if (isModDisabledSafe(entry)) {
				disabled.add(entry);
			} else {
				enabled.add(entry);
			}
		}
		if (enabled.isEmpty()) {
			return;
		}

		Map<String, Integer> manualIndex = loadRuntimeManualOrderIndex();
		Map<String, Integer> fallbackIndex = fallbackScanOrderIndex(allMods);
		List<String> sortedEnabledIds = computeRuntimeTopoSortedIds(enabled, byId, byPckName, manualIndex, fallbackIndex);
		List<ExtraSettingsRepository.ModEntry> orderedEntries = new ArrayList<>();
		Set<String> added = new LinkedHashSet<>();
		for (String id : sortedEnabledIds) {
			ExtraSettingsRepository.ModEntry entry = byId.get(id);
			if (entry != null && added.add(entry.modId)) {
				orderedEntries.add(entry);
			}
		}
		enabled.sort(runtimeManualOrderComparator(manualIndex, fallbackIndex));
		for (ExtraSettingsRepository.ModEntry entry : enabled) {
			if (added.add(entry.modId)) {
				orderedEntries.add(entry);
			}
		}
		disabled.sort(runtimeManualOrderComparator(manualIndex, fallbackIndex));
		for (ExtraSettingsRepository.ModEntry entry : disabled) {
			if (added.add(entry.modId)) {
				orderedEntries.add(entry);
			}
		}
		repository.saveRuntimeModListOrder(orderedEntries);
		cachedSettings = repository.loadSettingsJson();
	}

	private List<String> computeRuntimeTopoSortedIds(
		List<ExtraSettingsRepository.ModEntry> enabled,
		Map<String, ExtraSettingsRepository.ModEntry> byId,
		Map<String, ExtraSettingsRepository.ModEntry> byPckName,
		Map<String, Integer> manualIndex,
		Map<String, Integer> fallbackIndex
	) {
		Map<String, Integer> indegree = new HashMap<>();
		Map<String, List<String>> dependents = new HashMap<>();
		for (ExtraSettingsRepository.ModEntry entry : enabled) {
			indegree.put(entry.modId, 0);
			dependents.put(entry.modId, new ArrayList<>());
		}
		for (ExtraSettingsRepository.ModEntry entry : enabled) {
			for (ExtraSettingsRepository.ModDependency dependency : entry.dependencies) {
				ExtraSettingsRepository.ModEntry depEntry = findInstalledDependency(dependency.id, byId, byPckName);
				if (depEntry == null || isModDisabledSafe(depEntry) || !indegree.containsKey(depEntry.modId)) {
					continue;
				}
				indegree.put(entry.modId, indegree.get(entry.modId) + 1);
				dependents.get(depEntry.modId).add(entry.modId);
			}
		}
		Comparator<String> priorityComparator = Comparator.comparingInt(id -> runtimeManualPriority(id, manualIndex, fallbackIndex));
		List<String> ready = new ArrayList<>();
		for (ExtraSettingsRepository.ModEntry entry : enabled) {
			if (indegree.get(entry.modId) == 0) {
				ready.add(entry.modId);
			}
		}
		ready.sort(priorityComparator);
		List<String> sorted = new ArrayList<>();
		while (!ready.isEmpty()) {
			String id = ready.remove(0);
			sorted.add(id);
			List<String> newly = new ArrayList<>();
			for (String dependent : dependents.get(id)) {
				int value = indegree.get(dependent) - 1;
				indegree.put(dependent, value);
				if (value == 0) {
					newly.add(dependent);
				}
			}
			newly.sort(priorityComparator);
			ready.addAll(newly);
			ready.sort(priorityComparator);
		}
		for (ExtraSettingsRepository.ModEntry entry : enabled) {
			if (!sorted.contains(entry.modId)) {
				sorted.add(entry.modId);
			}
		}
		return sorted;
	}

	private void appendLine(StringBuilder builder, String label, String value) {
		builder.append(label).append(": ").append(emptyToDash(value)).append('\n');
	}

	private static final class ModIssue {
		static final String TYPE_DEPENDENCY = "dependency";
		static final String TYPE_DEPENDENCY_DISABLED = "dependency_disabled";
		static final String TYPE_GAME_VERSION = "game_version";
		static final String TYPE_MISSING_PCK = "missing_pck";
		static final String TYPE_MISSING_DLL = "missing_dll";
		static final String TYPE_LOAD_ORDER = "load_order";

		final String modId;
		final String displayName;
		final String type;
		final String message;
		/** Related MOD id for auto-fix (e.g. disabled dependency to enable). */
		final String relatedModId;

		ModIssue(String modId, String displayName, String type, String message) {
			this(modId, displayName, type, message, "");
		}

		ModIssue(String modId, String displayName, String type, String message, String relatedModId) {
			this.modId = modId;
			this.displayName = displayName;
			this.type = type;
			this.message = message;
			this.relatedModId = relatedModId == null ? "" : relatedModId;
		}
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
			cachedSettings = repository.loadSettingsJson();
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

	private List<ExtraSettingsRepository.ModEntry> selectedEntries() {
		List<ExtraSettingsRepository.ModEntry> result = new ArrayList<>();
		for (ExtraSettingsRepository.ModEntry entry : repository.listInstalledModManifests()) {
			if (selectedModIds.contains(entry.modId)) {
				result.add(entry);
			}
		}
		return result;
	}

	private static final class ListItem {
		final int type;
		final ModGroupBucket bucket;
		final boolean collapsed;
		final String groupId;
		final ExtraSettingsRepository.ModEntry entry;
		final int emptyTextRes;
		final Exception error;
		final int ghostIndex;
		final boolean groupGhost;

		private ListItem(int type, ModGroupBucket bucket, boolean collapsed, String groupId, ExtraSettingsRepository.ModEntry entry, int emptyTextRes, Exception error, int ghostIndex, boolean groupGhost) {
			this.type = type;
			this.bucket = bucket;
			this.collapsed = collapsed;
			this.groupId = groupId;
			this.entry = entry;
			this.emptyTextRes = emptyTextRes;
			this.error = error;
			this.ghostIndex = ghostIndex;
			this.groupGhost = groupGhost;
		}

		static ListItem group(ModGroupBucket bucket, boolean collapsed) {
			return new ListItem(TYPE_GROUP, bucket, collapsed, bucket.id, null, 0, null, -1, false);
		}

		static ListItem mod(String groupId, ExtraSettingsRepository.ModEntry entry) {
			return new ListItem(TYPE_MOD, null, false, groupId, entry, 0, null, -1, false);
		}

		static ListItem empty(int textRes) {
			return new ListItem(TYPE_EMPTY, null, false, null, null, textRes, null, -1, false);
		}

		static ListItem error(Exception error) {
			return new ListItem(TYPE_ERROR, null, false, null, null, 0, error, -1, false);
		}

		static ListItem ghost(String groupId, int index, boolean forGroup) {
			return new ListItem(TYPE_GHOST, null, false, groupId, null, 0, null, index, forGroup);
		}
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
