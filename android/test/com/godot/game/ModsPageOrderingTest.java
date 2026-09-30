package com.godot.game;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.EditText;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = AndroidLinuxTestShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ModsPageOrderingTest {
	private Context context;
	private ExtraSettingsRepository repository;
	private ModsPage page;
	private EditText search;
	private JSONObject settings;
	private final Map<String, ExtraSettingsRepository.ModEntry> entries = new HashMap<>();

	@Before public void setup() throws Exception {
		context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Sts2ExtraSettings);
		repository = new ExtraSettingsRepository(context);
		settings = new JSONObject();
		ExtraSettingsActions actions = (ExtraSettingsActions) Proxy.newProxyInstance(
			ExtraSettingsActions.class.getClassLoader(), new Class<?>[]{ExtraSettingsActions.class},
			(proxy, method, args) -> {
				if (method.getName().equals("showMessage")) return null;
				if (method.getName().equals("showError")) throw new AssertionError("Page operation failed", (Exception) args[0]);
				throw new AssertionError("Unexpected action: " + method.getName());
			});
		page = new ModsPage(context, repository, actions);
		setField(page, "adapter", construct("ModsListAdapter", new Class<?>[]{ModsPage.class}, page));
		search = new EditText(context);
		setField(page, "searchInput", search);
	}

	@Test public void filteredNoOpDropKeepsHiddenRanksOnEitherSideOfDraggedCard() throws Exception {
		loadFiveMods("enabled");
		assertEquals(Arrays.asList("A", "B"), displayedIds());

		move("A", "content", "content", 0);
		move("A", "content", "content", 1);

		assertSavedOrder("content", "X", "A", "Y", "B", "Z");
		showAll();
		assertEquals(Arrays.asList("X", "A", "Y", "B", "Z"), displayedIds());
	}

	@Test public void invalidFilteredDropDoesNotReplaceCompleteOrder() throws Exception {
		loadFiveMods("enabled");

		move("A", "content", "content", -1);
		move("A", "content", "content", 3);

		assertSavedOrder("content", "X", "A", "Y", "B", "Z");
		assertEquals(Arrays.asList("A", "B"), displayedIds());
	}

	@Test public void enabledFilterMoveBeforeNeighborRetainsHiddenPrefixAndSuffix() throws Exception {
		loadFiveMods("enabled");

		move("B", "content", "content", 0);

		assertSavedOrder("content", "X", "B", "A", "Y", "Z");
		assertEquals(Arrays.asList("B", "A"), displayedIds());
		showAll();
		assertEquals(Arrays.asList("X", "B", "A", "Y", "Z"), displayedIds());
		assertEquals(Arrays.asList("X", "A", "Y", "B", "Z"), repository.loadRuntimeModListOrder(repository.loadSettingsJson()));
	}

	@Test public void searchMoveAfterLastNeighborDoesNotMoveHiddenSuffixToFront() throws Exception {
		loadFiveMods("all");
		search.setText("match");
		rebuild();
		assertEquals(Arrays.asList("A", "B"), displayedIds());

		move("A", "content", "content", 2);

		assertSavedOrder("content", "X", "Y", "B", "A", "Z");
		showAll();
		assertEquals(Arrays.asList("X", "Y", "B", "A", "Z"), displayedIds());
	}

	@Test public void filteredCrossGroupDropPreservesBothCompleteOrders() throws Exception {
		List<ExtraSettingsRepository.ModEntry> mods = mods("X", "A", "Y", "B", "Z", "U", "C", "V", "D", "W");
		repository.saveModOrder("content", Arrays.asList("X", "A", "Y", "B", "Z"));
		repository.saveModOrder("Other", Arrays.asList("U", "C", "V", "D", "W"));
		for (String id : Arrays.asList("U", "C", "V", "D", "W")) repository.moveModToGroup(entries.get(id), "Other");
		repository.saveModGroupOrder(Arrays.asList("content", "Other"));
		enableOnly("A", "B", "C", "D");
		setField(page, "filter", "enabled");
		load(mods);

		move("A", "content", "Other", 1);

		assertSavedOrder("content", "X", "Y", "B", "Z");
		assertSavedOrder("Other", "U", "C", "V", "A", "D", "W");
		assertEquals("Other", repository.loadModGroupAssignments().get("A"));
		assertEquals(Arrays.asList("B", "C", "A", "D"), displayedIds());
		showAll();
		assertEquals(Arrays.asList("X", "Y", "B", "Z", "U", "C", "V", "A", "D", "W"), displayedIds());
	}

	@Test public void dropIntoFilteredEmptyGroupKeepsAllHiddenTargetEntries() throws Exception {
		List<ExtraSettingsRepository.ModEntry> mods = mods("X", "A", "Y", "B", "Z", "U", "V", "W");
		repository.saveModOrder("content", Arrays.asList("X", "A", "Y", "B", "Z"));
		repository.saveModOrder("Other", Arrays.asList("U", "V", "W"));
		for (String id : Arrays.asList("U", "V", "W")) repository.moveModToGroup(entries.get(id), "Other");
		repository.saveModGroupOrder(Arrays.asList("content", "Other"));
		enableOnly("A", "B");
		setField(page, "filter", "enabled");
		load(mods);

		move("A", "content", "Other", 0);

		assertSavedOrder("content", "X", "Y", "B", "Z");
		assertSavedOrder("Other", "U", "V", "W", "A");
		showAll();
		assertEquals(Arrays.asList("X", "Y", "B", "Z", "U", "V", "W", "A"), displayedIds());
	}

	@Test public void dropBeforeMiddleHeaderKeepsGlobalOrdinalAcrossRecycledPrefix() throws Exception {
		RecyclerView recycler = loadTwentyScrolledGroups();
		View header = recycler.findViewHolderForAdapterPosition(11).itemView;
		int index = (Integer) call(page, "resolveGroupDropIndex", new Class<?>[]{float.class},
			header.getTop() + header.getHeight() / 2f - 1);
		assertEquals(11, index);
		call(page, "showDragGhost", new Class<?>[]{String.class, int.class, boolean.class}, null, index, true);
		assertGhostBetween("G10", "G11");
		call(page, "clearDragGhost", new Class<?>[]{boolean.class}, true);
		call(page, "reorderGroup", new Class<?>[]{nested("ModGroupBucket"), int.class}, bucket("G13"), index);

		List<String> expected = Arrays.asList("G0", "G1", "G2", "G3", "G4", "G5", "G6", "G7", "G8", "G9",
			"G10", "G13", "G11", "G12", "G14", "G15", "G16", "G17", "G18", "G19");
		assertEquals(expected, new ExtraSettingsRepository(context).loadModGroupOrder());
		assertEquals(expected, displayedGroupsAndGhost());
	}

	@Test public void dropAfterMiddleHeaderAdjustsSourceWithinGlobalOrder() throws Exception {
		RecyclerView recycler = loadTwentyScrolledGroups();
		View header = recycler.findViewHolderForAdapterPosition(12).itemView;
		int index = (Integer) call(page, "resolveGroupDropIndex", new Class<?>[]{float.class},
			header.getTop() + header.getHeight() / 2f + 1);
		assertEquals(13, index);
		call(page, "showDragGhost", new Class<?>[]{String.class, int.class, boolean.class}, null, index, true);
		assertGhostBetween("G12", "G13");
		call(page, "clearDragGhost", new Class<?>[]{boolean.class}, true);
		call(page, "reorderGroup", new Class<?>[]{nested("ModGroupBucket"), int.class}, bucket("G10"), index);

		List<String> expected = Arrays.asList("G0", "G1", "G2", "G3", "G4", "G5", "G6", "G7", "G8", "G9",
			"G11", "G12", "G10", "G13", "G14", "G15", "G16", "G17", "G18", "G19");
		assertEquals(expected, new ExtraSettingsRepository(context).loadModGroupOrder());
		assertEquals(expected, displayedGroupsAndGhost());
	}

	@Test public void adjacentLibraryEndpointsDoNotSelectInterleavedContentScanEntries() throws Exception {
		List<ExtraSettingsRepository.ModEntry> mods = mods("C1", "L1", "C2", "L2");
		repository.moveModToGroup(entries.get("L1"), "core");
		repository.moveModToGroup(entries.get("L2"), "core");
		load(mods);
		assertEquals(Arrays.asList("L1", "L2", "C1", "C2"), displayedIds());

		select("L1", "L2");
		selectRange();

		assertSelected("L1", "L2");
	}

	@Test public void manualRankRangeAddsTheDisplayedInteriorRatherThanScanInterior() throws Exception {
		List<ExtraSettingsRepository.ModEntry> mods = mods("A", "C", "B", "D");
		repository.saveModOrder("content", Arrays.asList("A", "B", "C", "D"));
		load(mods);
		assertEquals(Arrays.asList("A", "B", "C", "D"), displayedIds());

		select("A", "C");
		selectRange();

		assertSelected("A", "B", "C");
	}

	@Test public void filteredRankRangeKeepsEarlierExplicitSelectionWithoutAddingHiddenInterior() throws Exception {
		List<ExtraSettingsRepository.ModEntry> mods = mods("C", "A", "Hidden", "B", "D");
		repository.saveModOrder("content", Arrays.asList("A", "Hidden", "B", "C", "D"));
		load(mods);
		select("D");
		enableOnly("A", "B", "C");
		setField(page, "filter", "enabled");
		rebuild();
		assertEquals(Arrays.asList("A", "B", "C"), displayedIds());

		select("A", "C");
		selectRange();

		assertSelected("D", "A", "B", "C");
	}

	@Test public void searchRangeUsesManualRanksAmongMatchingRowsOnly() throws Exception {
		List<ExtraSettingsRepository.ModEntry> mods = mods("KeepC", "KeepA", "Hidden", "KeepB");
		repository.saveModOrder("content", Arrays.asList("KeepA", "Hidden", "KeepB", "KeepC"));
		load(mods);
		search.setText("Keep");
		rebuild();
		assertEquals(Arrays.asList("KeepA", "KeepB", "KeepC"), displayedIds());

		select("KeepA", "KeepC");
		selectRange();

		assertSelected("KeepA", "KeepB", "KeepC");
	}

	@Test public void rangeAcrossCollapsedGroupRetainsExplicitSelectionButDoesNotAddHiddenRows() throws Exception {
		loadWithMiddleGroup();
		select("H1");
		collapseMiddleGroup();
		assertEquals(Arrays.asList("L1", "L2", "C1", "C2"), displayedIds());

		select("L2", "C1");
		selectRange();

		assertSelected("H1", "L2", "C1");
	}

	@Test public void collapsedSelectedEntryCannotBecomeAVisualRangeEndpoint() throws Exception {
		loadWithMiddleGroup();
		select("H1");
		collapseMiddleGroup();

		select("C2");
		selectRange();

		assertSelected("H1", "C2");
	}

	@Test public void dragRebuildAndOrdinaryRebuildShareCollapsedRangeSemantics() throws Exception {
		loadWithMiddleGroup();
		collapseMiddleGroup();
		move("C2", "content", "content", 0);
		assertEquals(Arrays.asList("L1", "L2", "C2", "C1"), displayedIds());

		select("L2", "C2");
		selectRange();
		assertSelected("L2", "C2");
		select("L2", "C2");
		rebuild();
		select("L2", "C2");
		selectRange();
		assertSelected("L2", "C2");
	}

	private void loadWithMiddleGroup() throws Exception {
		List<ExtraSettingsRepository.ModEntry> mods = mods("L1", "L2", "H1", "H2", "C1", "C2");
		for (String id : Arrays.asList("L1", "L2")) repository.moveModToGroup(entries.get(id), "core");
		for (String id : Arrays.asList("H1", "H2")) repository.moveModToGroup(entries.get(id), "Middle");
		repository.saveModGroupOrder(Arrays.asList("core", "Middle", "content"));
		load(mods);
	}

	private void collapseMiddleGroup() throws Exception {
		ModsPageOrderingTest.<Set<String>>field(page, "collapsedGroupIds").add("Middle");
		rebuild();
	}

	private void select(String... ids) throws Exception {
		for (String id : ids) call(page, "toggleSelected", new Class<?>[]{String.class}, id);
	}

	private void selectRange() throws Exception {
		call(page, "selectRangeBetweenSelected", new Class<?>[0]);
	}

	private void assertSelected(String... ids) throws Exception {
		assertEquals(new LinkedHashSet<>(Arrays.asList(ids)), field(page, "selectedModIds"));
	}

	private RecyclerView loadTwentyScrolledGroups() throws Exception {
		List<String> groups = new ArrayList<>();
		List<ExtraSettingsRepository.ModEntry> mods = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			String group = "G" + i;
			groups.add(group);
			ExtraSettingsRepository.ModEntry entry = mods("M" + i).get(0);
			mods.add(entry);
			repository.moveModToGroup(entry, group);
		}
		repository.saveModGroupOrder(groups);
		ModsPageOrderingTest.<Set<String>>field(page, "collapsedGroupIds").addAll(groups);
		load(mods);
		RecyclerView recycler = new RecyclerView(context);
		LinearLayoutManager layoutManager = new LinearLayoutManager(context);
		recycler.setLayoutManager(layoutManager);
		recycler.setItemAnimator(null);
		recycler.setAdapter(ModsPageOrderingTest.<RecyclerView.Adapter<?>>field(page, "adapter"));
		setField(page, "recyclerView", recycler);
		layoutRecycler(recycler);
		layoutManager.scrollToPositionWithOffset(10, 0);
		layoutRecycler(recycler);
		for (int i = 0; i < 10; i++) assertNull("Prefix header must be recycled: " + i, recycler.findViewHolderForAdapterPosition(i));
		assertNotNull(recycler.findViewHolderForAdapterPosition(11));
		assertNotNull(recycler.findViewHolderForAdapterPosition(12));
		return recycler;
	}

	private void layoutRecycler(RecyclerView recycler) {
		int width = ExtraSettingsUi.dp(context, 480);
		int height = ExtraSettingsUi.dp(context, 240);
		recycler.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
		recycler.layout(0, 0, width, height);
	}

	private void assertGhostBetween(String before, String after) throws Exception {
		List<String> displayed = displayedGroupsAndGhost();
		int ghost = displayed.indexOf("ghost");
		assertTrue("Group drag must have a ghost between its neighbors", ghost > 0 && ghost < displayed.size() - 1);
		assertEquals(before, displayed.get(ghost - 1));
		assertEquals(after, displayed.get(ghost + 1));
	}

	private List<String> displayedGroupsAndGhost() throws Exception {
		List<String> groups = new ArrayList<>();
		Object adapter = field(page, "adapter");
		for (Object item : ModsPageOrderingTest.<List<?>>field(adapter, "items")) {
			if (field(item, "bucket") != null) groups.add(field(item, "groupId"));
			else if (ModsPageOrderingTest.<Boolean>field(item, "groupGhost")) groups.add("ghost");
		}
		return groups;
	}

	private void loadFiveMods(String filter) throws Exception {
		List<ExtraSettingsRepository.ModEntry> mods = mods("X", "A", "Y", "B", "Z");
		repository.saveModOrder("content", Arrays.asList("X", "A", "Y", "B", "Z"));
		enableOnly("A", "B");
		repository.saveSettingsJson(settings);
		setField(page, "filter", filter);
		load(mods);
	}

	private List<ExtraSettingsRepository.ModEntry> mods(String... ids) throws Exception {
		List<ExtraSettingsRepository.ModEntry> result = new ArrayList<>();
		for (String id : ids) {
			ExtraSettingsRepository.ModEntry entry = new ExtraSettingsRepository.ModEntry(
				new File(context.getCacheDir(), id + ".json"), id, id,
				id.equals("A") || id.equals("B") ? "match " + id : id,
				id + ".json", "1.0", "fixture", "", "", "", Collections.emptyList(), true, false, true, false);
			entries.put(id, entry);
			result.add(entry);
			repository.setModDisabled(settings, id, false);
		}
		return result;
	}

	private void enableOnly(String... enabledIds) throws Exception {
		List<String> enabled = Arrays.asList(enabledIds);
		for (String id : entries.keySet()) repository.setModDisabled(settings, id, !enabled.contains(id));
	}

	private void load(List<ExtraSettingsRepository.ModEntry> mods) throws Exception {
		// Inject a deterministic scanner result through the existing consumer callback,
		// avoiding background refresh/lifecycle races rather than adding a production hook.
		Object snapshot = construct("LoadedMods", new Class<?>[]{JSONObject.class, Map.class, String.class, List.class},
			settings, Collections.emptyMap(), "", mods);
		List<String> groups = repository.listModGroups();
		ModsPageOrderingTest.<List<String>>field(snapshot, "userGroups").addAll(groups);
		Map<String, String> assignments = repository.loadModGroupAssignments();
		ModsPageOrderingTest.<Map<String, String>>field(snapshot, "groupAssignments").putAll(assignments);
		ModsPageOrderingTest.<Map<String, Integer>>field(snapshot, "groupOrder").putAll(ranks(repository.loadModGroupOrder()));
		Set<String> groupIds = new LinkedHashSet<>(Arrays.asList("core", "content"));
		groupIds.addAll(groups);
		groupIds.addAll(assignments.values());
		Map<String, Map<String, Integer>> order = field(snapshot, "modOrder");
		for (String group : groupIds) order.put(group, ranks(repository.loadModOrder(group)));
		Map<ExtraSettingsRepository.ModEntry, Long> modifiedAt = field(snapshot, "modifiedAt");
		for (int i = 0; i < mods.size(); i++) modifiedAt.put(mods.get(i), (long) (mods.size() - i));
		call(page, "applyLoadedMods", new Class<?>[]{int.class, snapshot.getClass()}, 0, snapshot);
	}

	private void move(String id, String source, String target, int index) throws Exception {
		call(page, "moveModToGroup", new Class<?>[]{ExtraSettingsRepository.ModEntry.class, nested("ModGroupBucket"), nested("ModGroupBucket"), int.class},
			entries.get(id), bucket(source), bucket(target), index);
	}

	private Object bucket(String group) throws Exception {
		return call(page, "findBucket", new Class<?>[]{String.class}, group);
	}

	private void showAll() throws Exception {
		search.setText("");
		setField(page, "filter", "all");
		rebuild();
	}

	private void rebuild() throws Exception {
		call(page, "rebuildFilteredList", new Class<?>[0]);
	}

	private List<String> displayedIds() throws Exception {
		Object adapter = field(page, "adapter");
		List<String> ids = new ArrayList<>();
		for (Object item : ModsPageOrderingTest.<List<?>>field(adapter, "items")) {
			ExtraSettingsRepository.ModEntry entry = field(item, "entry");
			if (entry != null) ids.add(entry.modId);
		}
		return ids;
	}

	private void assertSavedOrder(String group, String... ids) {
		assertEquals(Arrays.asList(ids), new ExtraSettingsRepository(context).loadModOrder(group));
	}

	private static Map<String, Integer> ranks(List<String> ids) {
		Map<String, Integer> result = new HashMap<>();
		for (int i = 0; i < ids.size(); i++) result.put(ids.get(i), i);
		return result;
	}

	private static Class<?> nested(String name) throws Exception {
		return Class.forName(ModsPage.class.getName() + "$" + name);
	}

	private static Object construct(String name, Class<?>[] parameters, Object... args) throws Exception {
		Constructor<?> constructor = nested(name).getDeclaredConstructor(parameters);
		constructor.setAccessible(true);
		return constructor.newInstance(args);
	}

	private static Object call(Object receiver, String name, Class<?>[] parameters, Object... args) throws Exception {
		Method method = receiver.getClass().getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method.invoke(receiver, args);
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(Object receiver, String name) throws Exception {
		Field field = receiver.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (T) field.get(receiver);
	}

	private static void setField(Object receiver, String name, Object value) throws Exception {
		Field field = receiver.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(receiver, value);
	}
}
