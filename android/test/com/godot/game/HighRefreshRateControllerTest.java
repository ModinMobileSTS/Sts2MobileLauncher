package com.godot.game;

import android.app.Application;
import android.view.Display;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.shadows.ShadowDisplayManager.ModeBuilder.modeBuilder;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 35)
public class HighRefreshRateControllerTest {
	@Test
	public void exactSameSizeSixtyModeWinsOverOtherModes() throws Exception {
		Display.Mode current = mode(1, 1080, 1920, 120.0f);
		Display.Mode exactSixty = mode(2, 1080, 1920, 59.94f);
		Display.Mode differentSizeSixty = mode(3, 1440, 2560, 60.0f);

		Object choice = choose60Hz(
			current,
			new Display.Mode[] {current, exactSixty, differentSizeSixty},
			1080,
			1920,
			1,
			120.0f,
			120.0f);

		assertTrue((Boolean) field(choice, "supported"));
		assertEquals(2, field(choice, "modeId"));
		assertEquals(59.94f, (Float) field(choice, "refreshRate"), 0.01f);
	}

	@Test
	public void alternativeSixtyUsesRefreshRateOnlyWhenNoExactModeExists() throws Exception {
		Display.Mode current = modeWithAlternatives(
			1,
			1080,
			1920,
			120.0f,
			new float[] {50.0f, 59.94f, 90.0f, 120.0f});
		Object choice = choose60Hz(
			current,
			new Display.Mode[] {current},
			1080,
			1920,
			1,
			120.0f,
			120.0f);

		assertTrue((Boolean) field(choice, "supported"));
		assertEquals(0, field(choice, "modeId"));
		assertEquals(59.94f, (Float) field(choice, "refreshRate"), 0.01f);
	}

	@Test
	public void fiftyNinetyAndOneTwentyAreNotSixtyFallbacks() throws Exception {
		Display.Mode current = mode(1, 1080, 1920, 120.0f);
		Display.Mode fifty = mode(2, 1080, 1920, 50.0f);
		Display.Mode ninety = mode(3, 1080, 1920, 90.0f);
		Display.Mode oneTwenty = mode(4, 1080, 1920, 120.0f);

		Object choice = choose60Hz(
			current,
			new Display.Mode[] {fifty, ninety, oneTwenty},
			1080,
			1920,
			1,
			120.0f,
			120.0f);

		assertFalse((Boolean) field(choice, "supported"));
		assertEquals(0.0f, (Float) field(choice, "refreshRate"), 0.0f);
	}

	@Test
	public void highUsesLowerBoundButSixtyUsesBidirectionalTolerance() throws Exception {
		assertTrue(refreshMatched(HighRefreshRateController.RefreshRateMode.HIGH, 120.0f, 119.5f, 0.0f));
		assertFalse(refreshMatched(HighRefreshRateController.RefreshRateMode.HIGH, 120.0f, 119.49f, 0.0f));
		assertFalse(refreshMatched(HighRefreshRateController.RefreshRateMode.HIGH, 120.0f, 120.0f, 60.0f));
		assertTrue(refreshMatched(HighRefreshRateController.RefreshRateMode.HZ60, 60.0f, 120.0f, 60.0f));
		assertFalse(refreshMatched(HighRefreshRateController.RefreshRateMode.HZ60, 60.0f, 60.0f, 120.0f));
		assertTrue(refreshMatched(HighRefreshRateController.RefreshRateMode.HZ60, 59.94f, 0.0f, 60.44f));
		assertFalse(refreshMatched(HighRefreshRateController.RefreshRateMode.HZ60, 59.94f, 0.0f, 60.45f));
		assertFalse(refreshMatched(HighRefreshRateController.RefreshRateMode.HZ60, 59.94f, 0.0f, 59.43f));
	}

	@Test
	public void explicitModeMismatchCannotBeVerified() throws Exception {
		assertFalse(modeMatched(7, 8));
		assertTrue(modeMatched(0, 8));
		assertTrue(modeMatched(7, 7));
	}

	private static Display.Mode mode(int id, int width, int height, float refreshRate) {
		return modeBuilder(id)
			.setWidth(width)
			.setHeight(height)
			.setRefreshRate(refreshRate)
			.build();
	}

	private static Display.Mode modeWithAlternatives(
		int id,
		int width,
		int height,
		float refreshRate,
		float[] alternatives
	) throws Exception {
		Constructor<Display.Mode> constructor = Display.Mode.class.getDeclaredConstructor(
			int.class,
			int.class,
			int.class,
			float.class,
			float[].class,
			int[].class);
		constructor.setAccessible(true);
		return constructor.newInstance(id, width, height, refreshRate, alternatives, new int[0]);
	}

	private static Object choose60Hz(
		Display.Mode current,
		Display.Mode[] modes,
		int width,
		int height,
		int currentModeId,
		float currentModeRefreshRate,
		float currentRefreshRate
	) throws Exception {
		Method method = HighRefreshRateController.class.getDeclaredMethod(
			"choose60HzMode",
			Display.Mode.class,
			Display.Mode[].class,
			int.class,
			int.class,
			int.class,
			float.class,
			float.class);
		method.setAccessible(true);
		return method.invoke(
			null,
			current,
			modes,
			width,
			height,
			currentModeId,
			currentModeRefreshRate,
			currentRefreshRate);
	}


	private static boolean refreshMatched(
		HighRefreshRateController.RefreshRateMode mode,
		float target,
		float observedModeRate,
		float observedDisplayRate
	) throws Exception {
		Method method = HighRefreshRateController.class.getDeclaredMethod(
			"isRefreshRateMatched",
			HighRefreshRateController.RefreshRateMode.class,
			float.class,
			float.class,
			float.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(null, mode, target, observedModeRate, observedDisplayRate);
	}

	private static boolean modeMatched(int targetModeId, int observedModeId) throws Exception {
		Method method = HighRefreshRateController.class.getDeclaredMethod(
			"isModeMatched", int.class, int.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(null, targetModeId, observedModeId);
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}
}
