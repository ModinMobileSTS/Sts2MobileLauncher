package com.godot.game;

import android.content.Context;
import android.graphics.Color;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.PopupMenu;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

	private static final int MOD_ACTION_INFO_ID = 4001;
	private static final int MOD_ACTION_SELECT_ID = 4002;
	private static final int MOD_ACTION_DELETE_ID = 4003;

	private static final int SORT_GROUP_ID = 5000;
	private static final int SORT_INSTALLED_ID = 5001;
	private static final int SORT_NAME_ID = 5002;

	private static final String SORT_INSTALLED = "installed";
	private static final String SORT_NAME = "name";

	private final Context context;
	private final ExtraSettingsRepository repository;
	private final ExtraSettingsActions actions;

	private final Set<String> selectedModIds = new HashSet<>();
	private final Set<String> expandedModIds = new HashSet<>();
	private final List<ExtraSettingsRepository.ModEntry> currentFilteredMods = new ArrayList<>();
	private ScrollView scrollView;
	private LinearLayout listContainer;
	private MaterialCardView bottomPanelCard;
	private LinearLayout bottomPanelContent;
	private TextInputEditText searchInput;
	private String filter = "all";
	private String sortMode = SORT_INSTALLED;
	private boolean bottomPanelVisible;

	public ModsPage(Context context, ExtraSettingsRepository repository, ExtraSettingsActions actions) {
		this.context = context;
		this.repository = repository;
		this.actions = actions;
	}

	public View build() {
		FrameLayout frame = new FrameLayout(context);
		frame.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		scrollView = new ScrollView(context);
		scrollView.setFillViewport(false);
		scrollView.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		LinearLayout root = ExtraSettingsUi.vertical(context);
		int padding = ExtraSettingsUi.dp(context, 20);
		root.setPadding(padding, ExtraSettingsUi.dp(context, 24), padding, ExtraSettingsUi.dp(context, 122));
		scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		root.addView(buildTopBar());
		ExtraSettingsUi.addCardSpacing(root, buildToolsCard());

		listContainer = ExtraSettingsUi.vertical(context);
		ExtraSettingsUi.addCardSpacing(root, listContainer);

		frame.addView(scrollView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		frame.addView(buildBottomPanel(), bottomPanelParams());
		refreshList();
		return frame;
	}

	private View buildTopBar() {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.title(context, R.string.tab_mods), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		MaterialButton profileMenu = topBarIconButton(R.drawable.ic_layers_24);
		profileMenu.setContentDescription(context.getString(R.string.mod_profiles_title));
		profileMenu.setOnClickListener(v -> showProfilesMenu(profileMenu));
		row.addView(profileMenu);

		MaterialButton sortMenu = topBarIconButton(R.drawable.ic_sort_24);
		sortMenu.setContentDescription(context.getString(R.string.mod_sort_menu_title));
		sortMenu.setOnClickListener(v -> showSortMenu(sortMenu));
		row.addView(sortMenu);

		MaterialButton filterMenu = topBarIconButton(R.drawable.ic_tune_24);
		filterMenu.setContentDescription(context.getString(R.string.mod_filter_menu_title));
		filterMenu.setOnClickListener(v -> showFilterMenu(filterMenu));
		row.addView(filterMenu);
		return row;
	}

	private MaterialButton topBarIconButton(int iconRes) {
		MaterialButton button = ExtraSettingsUi.iconButton(context, iconRes);
		button.setPadding(ExtraSettingsUi.dp(context, 7), ExtraSettingsUi.dp(context, 10), ExtraSettingsUi.dp(context, 7), ExtraSettingsUi.dp(context, 10));
		button.setLayoutParams(new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 36), ExtraSettingsUi.dp(context, 44)));
		return button;
	}

	private View buildToolsCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(
			context,
			R.drawable.ic_extension_24,
			R.string.mods_tools_title,
			R.string.mods_tools_subtitle,
			infoButton(R.string.mods_tools_title, R.string.mods_tools_info)
		));

		try {
			JSONObject settings = repository.loadSettingsJson();
			MaterialSwitch master = new MaterialSwitch(context);
			master.setText(R.string.mod_master_switch);
			master.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
			master.setChecked(repository.isModLoadingEnabled(settings));
			master.setOnCheckedChangeListener((buttonView, isChecked) -> {
				try {
					repository.saveSetting(root -> repository.ensureModSettings(root).put("mods_enabled", isChecked));
				} catch (Exception exception) {
					buttonView.setChecked(!isChecked);
					actions.showError(exception);
				}
			});
			ExtraSettingsUi.addSmallSpacing(content, master);
		} catch (Exception exception) {
			actions.showError(exception);
		}

		MaterialButton importButton = ExtraSettingsUi.filledButton(context, R.string.import_mod, R.drawable.ic_upload_file_24);
		importButton.setOnClickListener(v -> actions.requestImportMod());
		ExtraSettingsUi.addSmallSpacing(content, importButton);

		TextInputLayout searchLayout = new TextInputLayout(context);
		searchLayout.setHint(context.getString(R.string.mod_search_hint));
		searchLayout.setStartIconDrawable(R.drawable.ic_search_24);
		searchLayout.setBoxBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		searchInput = new TextInputEditText(searchLayout.getContext());
		searchInput.setSingleLine(true);
		searchInput.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		searchInput.setHintTextColor(ExtraSettingsUi.COLOR_MUTED);
		searchInput.addTextChangedListener(new android.text.TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) { refreshList(); }
			@Override public void afterTextChanged(android.text.Editable s) {}
		});
		searchLayout.addView(searchInput);
		ExtraSettingsUi.addSmallSpacing(content, searchLayout);
		return card;
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

	private FrameLayout.LayoutParams bottomPanelParams() {
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
		int horizontal = ExtraSettingsUi.dp(context, 10);
		params.setMargins(horizontal, 0, horizontal, 0);
		return params;
	}

	private void showFilterMenu(View anchor) {
		PopupMenu popup = new PopupMenu(context, anchor);
		popup.setForceShowIcon(true);
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
		item.setIcon(iconRes);
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
		PopupMenu popup = new PopupMenu(context, anchor);
		popup.setForceShowIcon(true);
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
			refreshList();
			return true;
		});
		popup.show();
	}

	private void addSortMenuItem(Menu menu, int itemId, int titleRes, int iconRes, String value) {
		MenuItem item = menu.add(SORT_GROUP_ID, itemId, Menu.NONE, titleRes);
		item.setIcon(iconRes);
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
			PopupMenu popup = new PopupMenu(context, anchor);
			popup.setForceShowIcon(true);
			Menu menu = popup.getMenu();
			menu.add(Menu.NONE, PROFILE_CREATE_ID, 0, R.string.mod_profile_save_current).setIcon(R.drawable.ic_add_circle_24);
			for (int i = 0; i < state.profiles.size(); i++) {
				ExtraSettingsRepository.ModProfile profile = state.profiles.get(i);
				boolean active = state.activeProfileId.equals(profile.id);
				if (active) {
					activeProfile = profile;
				}
				int itemId = PROFILE_ITEM_BASE_ID + i;
				MenuItem item = menu.add(PROFILE_APPLY_GROUP_ID, itemId, i + 10, profile.name);
				item.setIcon(active ? R.drawable.ic_check_circle_24 : R.drawable.ic_layers_24);
				item.setCheckable(true);
				item.setChecked(active);
				profileItems.put(itemId, profile);
			}
			menu.setGroupCheckable(PROFILE_APPLY_GROUP_ID, true, true);
			if (activeProfile != null && !"default".equals(activeProfile.id)) {
				menu.add(Menu.NONE, PROFILE_DELETE_ACTIVE_ID, 1000, R.string.mod_profile_delete_active).setIcon(R.drawable.ic_delete_24);
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
			refreshList();
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private void setFilter(String value) {
		filter = value;
		selectedModIds.clear();
		refreshList();
	}

	private void updateSelectionActionsPanel() {
		if (bottomPanelCard == null || bottomPanelContent == null) {
			return;
		}
		bottomPanelContent.removeAllViews();
		if (selectedModIds.isEmpty()) {
			hideBottomPanel();
			return;
		}
		showBottomPanel();
		bottomPanelContent.addView(ExtraSettingsUi.sectionTitle(context, context.getString(R.string.mod_selected_count_format, selectedModIds.size())));

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
			for (ExtraSettingsRepository.ModEntry entry : filtered) {
				ExtraSettingsUi.addCardSpacing(listContainer, modCard(settings, entry));
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
			boolean enabled = !repository.isModDisabled(settings, entry.modId);
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
			String haystack = (entry.displayName + " " + entry.modId + " " + entry.version + " " + entry.authors + " " + entry.description + " " + entry.category + " " + entry.relativePath).toLowerCase(Locale.ROOT);
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

	private View modCard(JSONObject settings, ExtraSettingsRepository.ModEntry entry) throws Exception {
		boolean enabled = !repository.isModDisabled(settings, entry.modId);
		final boolean[] enabledState = new boolean[] { enabled };
		boolean selected = selectedModIds.contains(entry.modId);
		boolean selectionMode = !selectedModIds.isEmpty();
		boolean expanded = expandedModIds.contains(entry.modId);
		MaterialCardView card = ExtraSettingsUi.clickableCard(context);
		card.setRadius(ExtraSettingsUi.dp(context, 10));
		applyModCardStyle(card, enabled, selected);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.setPadding(ExtraSettingsUi.dp(context, 14), ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 12));

		LinearLayout top = ExtraSettingsUi.horizontal(context);
		if (selectionMode) {
			MaterialCheckBox checkBox = new MaterialCheckBox(context);
			checkBox.setChecked(selected);
			checkBox.setOnClickListener(v -> toggleSelected(entry.modId));
			LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			checkParams.setMarginEnd(ExtraSettingsUi.dp(context, 6));
			top.addView(checkBox, checkParams);
		}
		LinearLayout textColumn = ExtraSettingsUi.vertical(context);
		top.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		textColumn.addView(ExtraSettingsUi.sectionTitle(context, entry.displayName));
		if (!android.text.TextUtils.isEmpty(entry.modId)) {
			textColumn.addView(ExtraSettingsUi.caption(context, entry.modId));
		}

		MaterialButton more = ExtraSettingsUi.iconButton(context, R.drawable.ic_more_vert_24);
		more.setContentDescription(context.getString(R.string.mod_card_more_actions));
		more.setOnClickListener(v -> showModActionsMenu(more, entry, enabledState[0]));
		top.addView(more);
		content.addView(top);

		if (!android.text.TextUtils.isEmpty(entry.description)) {
			ExtraSettingsUi.addSmallSpacing(content, buildDescriptionRow(content, entry));
		}

		LinearLayout bottom = ExtraSettingsUi.horizontal(context);
		bottom.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout metaColumn = ExtraSettingsUi.vertical(context);
		bottom.addView(metaColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		addMetaInfo(metaColumn, R.drawable.ic_code_24, displayCategory(entry));
		addMetaInfo(metaColumn, R.drawable.ic_badge_24, emptyToDash(entry.version));
		addMetaInfo(metaColumn, R.drawable.ic_person_24, emptyToDash(entry.authors));
		addMetaInfo(metaColumn, R.drawable.ic_article_24, entry.relativePath);

		MaterialSwitch switchView = new MaterialSwitch(context);
		switchView.setChecked(enabled);
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
		switchParams.setMarginStart(ExtraSettingsUi.dp(context, 12));
		bottom.addView(switchView, switchParams);
		ExtraSettingsUi.addSmallSpacing(content, bottom);

		card.setOnClickListener(v -> {
			if (!selectedModIds.isEmpty()) {
				toggleSelected(entry.modId);
			} else {
				toggleExpandedDescription(content, entry.modId);
			}
		});
		card.setOnLongClickListener(v -> {
			toggleSelected(entry.modId);
			return true;
		});
		return card;
	}

	private View buildDescriptionRow(LinearLayout transitionRoot, ExtraSettingsRepository.ModEntry entry) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		boolean expanded = expandedModIds.contains(entry.modId);
		TextView description = ExtraSettingsUi.body(context, entry.description);
		description.setTag(entry.modId);
		description.setMaxLines(expanded ? Integer.MAX_VALUE : 2);
		description.setEllipsize(expanded ? null : android.text.TextUtils.TruncateAt.END);
		row.addView(description, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		MaterialButton arrow = ExtraSettingsUi.iconButton(context, expanded ? R.drawable.ic_expand_less_24 : R.drawable.ic_expand_more_24);
		arrow.setTag(entry.modId + ":arrow");
		arrow.setVisibility(shouldShowExpandArrow(entry.description) ? View.VISIBLE : View.INVISIBLE);
		arrow.setOnClickListener(v -> toggleExpandedDescription(transitionRoot, entry.modId));
		row.addView(arrow);
		return row;
	}

	private boolean shouldShowExpandArrow(String description) {
		return description != null && (description.length() > 72 || description.contains("\n"));
	}

	private void toggleExpandedDescription(ViewGroup transitionRoot, String modId) {
		if (expandedModIds.contains(modId)) {
			expandedModIds.remove(modId);
		} else {
			expandedModIds.add(modId);
		}
		TextView description = findDescriptionTextView(transitionRoot, modId);
		MaterialButton arrow = findArrowButton(transitionRoot, modId);
		if (description == null) {
			return;
		}
		boolean expanded = expandedModIds.contains(modId);
		AutoTransition transition = new AutoTransition();
		transition.setDuration(180);
		TransitionManager.beginDelayedTransition(transitionRoot, transition);
		description.setMaxLines(expanded ? Integer.MAX_VALUE : 2);
		description.setEllipsize(expanded ? null : android.text.TextUtils.TruncateAt.END);
		if (arrow != null) {
			arrow.setIconResource(expanded ? R.drawable.ic_expand_less_24 : R.drawable.ic_expand_more_24);
		}
	}

	private TextView findDescriptionTextView(View view, String modId) {
		Object tag = view.getTag();
		if (modId.equals(tag) && view instanceof TextView textView) {
			return textView;
		}
		if (view instanceof ViewGroup group) {
			for (int i = 0; i < group.getChildCount(); i++) {
				TextView found = findDescriptionTextView(group.getChildAt(i), modId);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private MaterialButton findArrowButton(View view, String modId) {
		Object tag = view.getTag();
		if ((modId + ":arrow").equals(tag) && view instanceof MaterialButton button) {
			return button;
		}
		if (view instanceof ViewGroup group) {
			for (int i = 0; i < group.getChildCount(); i++) {
				MaterialButton found = findArrowButton(group.getChildAt(i), modId);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private void applyModCardStyle(MaterialCardView card, boolean enabled, boolean selected) {
		if (selected) {
			card.setStrokeWidth(ExtraSettingsUi.dp(context, 5));
			card.setStrokeColor(Color.rgb(0, 150, 64));
			card.setCardBackgroundColor(Color.rgb(18, 58, 36));
		} else if (enabled) {
			card.setStrokeWidth(ExtraSettingsUi.dp(context, 1));
			card.setStrokeColor(Color.rgb(72, 104, 84));
			card.setCardBackgroundColor(Color.rgb(29, 50, 38));
		} else {
			card.setStrokeWidth(ExtraSettingsUi.dp(context, 1));
			card.setStrokeColor(ExtraSettingsUi.COLOR_OUTLINE);
			card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		}
	}

	private void addMetaInfo(LinearLayout parent, int iconRes, String value) {
		if (android.text.TextUtils.isEmpty(value)) {
			return;
		}
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_MUTED, 15));
		TextView text = ExtraSettingsUi.caption(context, value);
		text.setSingleLine(true);
		text.setEllipsize(android.text.TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(context, 6));
		row.addView(text, params);
		parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
	}

	private void showModActionsMenu(View anchor, ExtraSettingsRepository.ModEntry entry, boolean enabled) {
		PopupMenu popup = new PopupMenu(context, anchor);
		popup.setForceShowIcon(true);
		Menu menu = popup.getMenu();
		menu.add(Menu.NONE, MOD_ACTION_INFO_ID, 0, R.string.mod_action_info).setIcon(R.drawable.ic_info_24);
		menu.add(Menu.NONE, MOD_ACTION_SELECT_ID, 1, selectedModIds.contains(entry.modId) ? R.string.mod_action_unselect : R.string.mod_action_select).setIcon(R.drawable.ic_check_circle_24);
		menu.add(Menu.NONE, MOD_ACTION_DELETE_ID, 2, R.string.delete).setIcon(R.drawable.ic_delete_24);
		popup.setOnMenuItemClickListener(item -> {
			if (item.getItemId() == MOD_ACTION_INFO_ID) {
				showModDetails(entry, enabled);
				return true;
			}
			if (item.getItemId() == MOD_ACTION_SELECT_ID) {
				toggleSelected(entry.modId);
				return true;
			}
			if (item.getItemId() == MOD_ACTION_DELETE_ID) {
				confirmDelete(entry);
				return true;
			}
			return false;
		});
		popup.show();
	}

	private void toggleSelected(String modId) {
		if (selectedModIds.contains(modId)) {
			selectedModIds.remove(modId);
		} else {
			selectedModIds.add(modId);
		}
		refreshList();
	}

	private String emptyToDash(String value) {
		return android.text.TextUtils.isEmpty(value) ? "—" : value;
	}

	private String displayCategory(ExtraSettingsRepository.ModEntry entry) {
		if (!android.text.TextUtils.isEmpty(entry.category)) {
			return entry.category;
		}
		if (isMissingPayload(entry)) {
			return context.getString(R.string.mod_category_missing_files);
		}
		if (isLibraryLike(entry)) {
			return context.getString(R.string.mod_category_library);
		}
		return "";
	}

	private boolean isMissingPayload(ExtraSettingsRepository.ModEntry entry) {
		return !entry.hasPck && !entry.hasDll;
	}

	private boolean isLibraryLike(ExtraSettingsRepository.ModEntry entry) {
		String probe = (entry.modId + " " + entry.displayName + " " + entry.category).toLowerCase(Locale.ROOT);
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

	private MaterialButton infoButton(int titleRes, int messageRes) {
		MaterialButton button = ExtraSettingsUi.iconButton(context, R.drawable.ic_info_24);
		button.setOnClickListener(v -> ExtraSettingsUi.showInfoDialog(context, titleRes, messageRes));
		return button;
	}

	private void showModDetails(ExtraSettingsRepository.ModEntry entry, boolean enabled) {
		StringBuilder message = new StringBuilder();
		appendLine(message, context.getString(R.string.mod_detail_status), enabled ? context.getString(R.string.mod_enabled) : context.getString(R.string.mod_disabled));
		appendLine(message, "ID", entry.modId);
		appendLine(message, context.getString(R.string.mod_detail_category), displayCategory(entry));
		appendLine(message, context.getString(R.string.mod_detail_version), entry.version);
		appendLine(message, context.getString(R.string.mod_detail_author), entry.authors);
		appendLine(message, context.getString(R.string.mod_detail_files), context.getString(R.string.mod_detail_files_format, entry.hasPck ? "PCK" : "—", entry.hasDll ? "DLL" : "—"));
		appendLine(message, context.getString(R.string.mod_detail_dependencies), entry.dependencies.isEmpty() ? "—" : android.text.TextUtils.join(", ", entry.dependencies));
		appendLine(message, context.getString(R.string.mod_detail_path), entry.relativePath);
		if (!android.text.TextUtils.isEmpty(entry.description)) {
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
}
