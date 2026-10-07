package com.godot.game;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LogViewportCanvasTest {
	@Test public void horizontalScrollProbeStillConstrainsChineseTextToViewport() {
		Fixture fixture = new Fixture();
		fixture.canvas.setLayoutParams(new FrameLayout.LayoutParams(320, ViewGroup.LayoutParams.MATCH_PARENT));
		fixture.measure();
		assertEquals(320, fixture.canvas.getMeasuredWidth());
		assertEquals(320, fixture.text.getMeasuredWidth());
		assertTrue(fixture.text.getLayout().getLineCount() > 1);
	}

	@Test public void repeatedWidthAndFontChangesKeepWrapExactAndAllowRealHorizontalScroll() {
		Fixture fixture = new Fixture();
		for (float font : new float[] {10f, 30f, 16f, 24f, 10f}) {
			fixture.text.setTextSize(font);
			fixture.text.setMaxLines(1);
			fixture.canvas.setLayoutParams(new FrameLayout.LayoutParams(5000, ViewGroup.LayoutParams.MATCH_PARENT));
			fixture.measure();
			assertEquals(5000, fixture.text.getMeasuredWidth());
			fixture.viewport.scrollTo(400, 0);
			assertEquals(400, fixture.viewport.getScrollX());

			fixture.text.setMaxLines(Integer.MAX_VALUE);
			fixture.canvas.setLayoutParams(new FrameLayout.LayoutParams(320, ViewGroup.LayoutParams.MATCH_PARENT));
			fixture.measure();
			fixture.viewport.scrollTo(0, 0);
			assertEquals(320, fixture.text.getMeasuredWidth());
			assertEquals(0, fixture.viewport.getScrollX());
			assertTrue(fixture.text.getLayout().getLineCount() > 1);
		}
	}

	private static final class Fixture {
		final HorizontalScrollView viewport;
		final LogViewportCanvas canvas;
		final TextView text;

		Fixture() {
			Context context = RuntimeEnvironment.getApplication();
			viewport = new HorizontalScrollView(context);
			viewport.setFillViewport(true);
			canvas = new LogViewportCanvas(context, null);
			text = new TextView(context);
			text.setSingleLine(false);
			text.setHorizontallyScrolling(false);
			text.setText("中文日志异常堆栈需要在实际视口内换行：com.example.Exception.atLongMethodName(Example.java:123)"
					+ "这是后续中文内容，反复缩放和切换换行模式不应该破坏内容宽度约束。");
			canvas.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			viewport.addView(canvas, new FrameLayout.LayoutParams(320, ViewGroup.LayoutParams.MATCH_PARENT));
		}

		void measure() {
			viewport.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
			viewport.layout(0, 0, 320, 240);
		}
	}
}
