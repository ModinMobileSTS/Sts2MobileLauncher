package com.godot.game;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class)
public class SteamWorkshopBrowseParsingTest {
	@Test public void jsonDataScriptPreservesItemsAuthorsAndServerPagination() throws Exception {
		JSONObject context = renderContext(items(), 2, 4, 63);
		String html = "<script type=\"application/json\" id=\"unrelated\">{}</script>"
			+ jsonScript(context);

		Object page = parse(html);
		assertEquals(2, field(page, "page"));
		assertEquals(63, field(page, "total"));
		List<SteamWorkshopCatalog.Item> items = items(page);
		assertEquals(1, items.size());
		SteamWorkshopCatalog.Item item = items.get(0);
		assertEquals("18446744073709551615", item.getPublishedFileId());
		assertEquals("Fixture \"quoted\" 龙", item.getTitle());
		assertEquals("Fixture Author", item.getAuthorName());
		assertEquals("Alpha & Beta", item.getDescription());
		assertEquals("https://images.steamusercontent.com/fixture.png", item.getPreviewUrl());
		assertEquals(76561198000000001L, item.getCreatorSteamId());
		assertEquals(5368709120L, item.getFileSizeBytes());
		assertEquals(17, item.getSubscriptions());
		assertEquals(1791348800L, item.getTimeUpdatedEpochSeconds());
	}

	@Test public void emptyJsonDataPageDoesNotResurrectStaleInlineResults() throws Exception {
		String html = inlineScript(renderContext(items(), 2, 4, 63))
			+ jsonScript(renderContext(new JSONArray(), 1, 1, 0));

		Object page = parse(html);
		assertEquals(1, field(page, "page"));
		assertEquals(0, field(page, "total"));
		assertTrue(items(page).isEmpty());
	}

	@Test public void malformedJsonDataStillAllowsLegacyInlineContext() throws Exception {
		String html = "<script id=\"valve-ssr-data\" type=\"application/json\">{broken</script>"
			+ inlineScript(renderContext(items(), 2, 4, 63));

		Object page = parse(html);
		assertEquals(2, field(page, "page"));
		assertEquals(63, field(page, "total"));
		assertEquals("18446744073709551615", items(page).get(0).getPublishedFileId());
		assertEquals("Fixture Author", items(page).get(0).getAuthorName());
	}

	@Test public void legacyHtmlKeepsDecodedItemAndNextPage() throws Exception {
		String html = "<div class=\"workshopItem\">"
			+ "<a href=\"https://steamcommunity.com/sharedfiles/filedetails/?id=90001\" data-appid=\"2868840\">"
			+ "<img class=\"workshopItemPreviewImage\" src=\"https://images.steamusercontent.com/legacy.png\">"
			+ "<div class=\"workshopItemTitle ellipsis\">Legacy &amp; Mod</div></a>"
			+ "<div class=\"workshopItemAuthorName ellipsis\"><a>Legacy Author</a></div></div>"
			+ "<a class=\"pagebtn\" href=\"?appid=2868840&p=2\">Next</a>";

		Object page = parse(html);
		assertEquals(1, field(page, "page"));
		assertEquals(21, field(page, "total"));
		assertEquals(1, items(page).size());
		SteamWorkshopCatalog.Item item = items(page).get(0);
		assertEquals("90001", item.getPublishedFileId());
		assertEquals("Legacy & Mod", item.getTitle());
		assertEquals("Legacy Author", item.getAuthorName());
	}

	private static JSONArray items() throws Exception {
		return new JSONArray().put(new JSONObject()
			.put("publishedfileid", "18446744073709551615")
			.put("consumer_appid", 2868840)
			.put("creator", "76561198000000001")
			.put("title", "Fixture \"quoted\" 龙")
			.put("short_description", "<b>Alpha</b> &amp; Beta")
			.put("preview_url", "https://images.steamusercontent.com/fixture.png")
			.put("file_size", 5368709120L)
			.put("subscriptions", 17)
			.put("time_updated", 1791348800L));
	}

	private static JSONObject renderContext(JSONArray results, int page, int totalPages, int total) throws Exception {
		JSONObject listing = new JSONObject()
			.put("current_page", page)
			.put("total_pages", totalPages)
			.put("total_count", total)
			.put("results", results);
		JSONArray queries = new JSONArray()
			.put(new JSONObject().put("state", new JSONObject().put("data", listing)))
			.put(new JSONObject()
				.put("queryKey", new JSONArray().put("PlayerLinkDetails").put("76561198000000001"))
				.put("state", new JSONObject().put("data", new JSONObject()
					.put("public_data", new JSONObject().put("persona_name", "Fixture Author")))));
		return new JSONObject().put("queryData", new JSONObject().put("queries", queries).toString());
	}

	private static String jsonScript(JSONObject context) throws Exception {
		return "<script nonce='fixture' type=\"application/json\"\nid = 'valve-ssr-data'>\n"
			+ new JSONObject().put("renderContext", context) + "\n</script>";
	}

	private static String inlineScript(JSONObject context) {
		return "<script>window.SSR.renderContext=JSON.parse("
			+ JSONObject.quote(context.toString()) + ");</script>";
	}

	private static Object parse(String html) throws Exception {
		SteamWorkshopCatalog catalog = new SteamWorkshopCatalog(RuntimeEnvironment.getApplication());
		Method method = SteamWorkshopCatalog.class.getDeclaredMethod("parsePublicBrowsePage", String.class, int.class, int.class);
		method.setAccessible(true);
		return method.invoke(catalog, html, 1, 20);
	}

	@SuppressWarnings("unchecked")
	private static List<SteamWorkshopCatalog.Item> items(Object page) throws Exception {
		return (List<SteamWorkshopCatalog.Item>) field(page, "items");
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}
}
