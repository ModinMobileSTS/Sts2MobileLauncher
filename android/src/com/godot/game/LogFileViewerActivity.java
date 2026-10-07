package com.godot.game;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.TextPaint;
import android.text.format.Formatter;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewTreeObserver;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.godot.game.loganalysis.LogAnalysisActivity;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogFileViewerActivity extends AppCompatActivity {
	public static final String EXTRA_FILE_PATH = "com.godot.game.extra.LOG_FILE_PATH";
	public static final String EXTRA_DISPLAY_NAME = "com.godot.game.extra.LOG_DISPLAY_NAME";
	public static final String EXTRA_DISPLAY_PATH = "com.godot.game.extra.LOG_DISPLAY_PATH";
	public static final String EXTRA_SOURCE_LABEL = "com.godot.game.extra.LOG_SOURCE_LABEL";
	public static final String EXTRA_LAST_MODIFIED = "com.godot.game.extra.LOG_LAST_MODIFIED";
	public static final String EXTRA_FILE_SIZE = "com.godot.game.extra.LOG_FILE_SIZE";

	private static final int REQUEST_EXPORT_FILE = 2101;
	private static final int MENU_SEARCH = 1;
	private static final int MENU_WRAP = 2;
	private static final int MENU_MORE = 3;
	private static final int MENU_SELECT_ALL = 4;
	private static final int MENU_ANALYZE = 5;
	private static final float BASE_TEXT_SP = 16f;
	private static final float WIDTH_BASE_SP = 10f;
	private static final String STATE_FONT = "log.font";
	private static final String STATE_WRAP = "log.wrap";
	private static final long MAX_PREVIEW_BYTES = 512L * 1024L;
	private static final int COLOR_SURFACE = 0xFF171A22;
	private static final int COLOR_ON_SURFACE = 0xFFF0F0F8;
	private static final int COLOR_MUTED = 0xFFC5C7D3;
	private static final int COLOR_PRIMARY = 0xFFB7C4FF;
	private static final int COLOR_ON_PRIMARY = 0xFF0E1B4D;
	private static final int COLOR_PRIMARY_CONTAINER = 0xFF2B3762;
	private static final int COLOR_ON_PRIMARY_CONTAINER = 0xFFDCE2FF;
	private static final int COLOR_ERROR = 0xFFFFB4AB;
	private static final int COLOR_ERROR_LINE = 0x22FF7268;
	private static final int COLOR_WARNING = 0xFFFFD54F;
	private static final int LEVEL_INFO = 0;
	private static final int LEVEL_WARNING = 1;
	private static final int LEVEL_ERROR = 2;
	private static final Pattern LEVEL_PATTERN = Pattern.compile(
			"(?:^|\\s|\\[)(ERROR|ERR|FATAL|WARNING|WARN|INFO|DEBUG|TRACE|VERBOSE|E|W|I|D|V)(?=\\]|[\\s:/]|$)",
			Pattern.CASE_INSENSITIVE);

	private final List<LogLine> lines = new ArrayList<>();
	private final List<Integer> visibleLineIndices = new ArrayList<>();
	private final List<Match> matches = new ArrayList<>();
	private MaterialToolbar toolbar;
	private View contentContainer;
	private TextView stateText;
	private TextView largeFileNotice;
	private View searchBar;
	private TextInputEditText searchInput;
	private TextView matchCountText;
	private ImageButton previousMatchButton;
	private ImageButton nextMatchButton;
	private HorizontalScrollView horizontalScroll;
	private FrameLayout scrollCanvas;
	private RecyclerView recyclerView;
	private LinearLayoutManager layoutManager;
	private LineAdapter adapter;
	private FloatingActionButton jumpBottomButton;
    private View detailActionBar;
    private MaterialButton detailCopyButton;
    private MaterialButton detailShareButton;
    private MaterialButton detailExportButton;
    private MaterialButton detailLocationButton;
    private View rangeActionBar;
    private MaterialButton rangeCopyButton;
    private MaterialButton rangeShareButton;
    private MaterialButton rangeExpandButton;
	private Chip errorChip;
	private Chip warningChip;
	private Chip infoChip;
	private File sourceFile;
	private String displayName;
	private String sourceLabel;
	private long lastModified;
	private long fileSize;
	private float maxLineWidthAtBase;
	private int numberWidth;
	private final TextPaint measurementPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final int[] touchLocation = new int[2];
	private float textSizeSp = BASE_TEXT_SP;
	private float pendingTextSizeSp = BASE_TEXT_SP;
	private ScaleGestureDetector scaleDetector;
	private boolean consumingPinch;
	private boolean resizingText;
	private boolean fontFramePending;
	private PinchAnchor pinchAnchor;
	private boolean awaitingRangeEnd;
	private boolean logGesture;
	private static final Object TYPOGRAPHY_PAYLOAD = new Object();
	private boolean wrapText = true;
	private boolean previewReady;
	private String searchQuery = "";
	private int currentMatch = -1;
	private int rangeStart = -1;
	private int rangeEnd = -1;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (savedInstanceState != null) {
			textSizeSp = Math.max(10f, Math.min(30f, savedInstanceState.getFloat(STATE_FONT, BASE_TEXT_SP)));
			wrapText = savedInstanceState.getBoolean(STATE_WRAP, true);
		}
		pendingTextSizeSp = textSizeSp;
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		setContentView(R.layout.activity_log_file_viewer);
        bindViews();
        configureToolbar();
        configureLevelFilters();
        configureSearch();
        configureDetailActions();
        configureRangeActions();
		adapter = new LineAdapter();
		layoutManager = new LinearLayoutManager(this);
		recyclerView.setLayoutManager(layoutManager);
		recyclerView.setAdapter(adapter);
		recyclerView.setItemAnimator(null);
		configurePinch();
		horizontalScroll.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
			if (r - l != oldR - oldL) {
				updateWrapWidth();
			}
		});
		jumpBottomButton.setImageDrawable(MaterialSymbols.drawable(this, "vertical_align_bottom", COLOR_ON_PRIMARY, 24));
		jumpBottomButton.setOnClickListener(v -> {
			if (rangeStart >= 0) {
				expandSelectedRange();
			} else {
				scrollToBottom(true);
			}
		});
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				handleBackNavigation();
			}
		});

		String filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
		displayName = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);
		sourceLabel = getIntent().getStringExtra(EXTRA_SOURCE_LABEL);
		lastModified = getIntent().getLongExtra(EXTRA_LAST_MODIFIED, 0L);
		fileSize = getIntent().getLongExtra(EXTRA_FILE_SIZE, 0L);
		if (displayName == null || displayName.trim().isEmpty()) {
			displayName = getString(R.string.log_file_viewer_title);
		}
		if (sourceLabel == null || sourceLabel.trim().isEmpty()) {
			sourceLabel = getString(R.string.log_viewer_source_other);
		}
		updateToolbar();
		if (filePath == null || filePath.trim().isEmpty()) {
			showLoadError(new IOException(getString(R.string.log_file_viewer_missing)));
			return;
		}
		sourceFile = new File(filePath);
		File file = sourceFile;
		stateText.setText(R.string.log_viewer_content_loading);
		stateText.setVisibility(View.VISIBLE);
		new Thread(() -> {
			try {
				if (!file.isFile()) {
					throw new IOException(getString(R.string.log_file_viewer_missing));
				}
				PreviewData data = readPreviewData(file);
				runOnUiThread(() -> applyPreview(data));
			} catch (Exception exception) {
				runOnUiThread(() -> showLoadError(exception));
			}
		}).start();
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putFloat(STATE_FONT, pendingTextSizeSp);
		outState.putBoolean(STATE_WRAP, wrapText);
		super.onSaveInstanceState(outState);
	}

	private TextPaint textPaint(float sizeSp) {
		measurementPaint.setTypeface(Typeface.MONOSPACE);
		measurementPaint.setTextSize(sizeSp * getResources().getDisplayMetrics().scaledDensity);
		return measurementPaint;
	}

	private void applyHolderTypography(LineHolder holder) {
		holder.number.setTextSize(textSizeSp - 1f);
		LinearLayout.LayoutParams numberParams = (LinearLayout.LayoutParams) holder.number.getLayoutParams();
		if (numberParams.width != numberWidth) {
			numberParams.width = numberWidth;
			holder.number.setLayoutParams(numberParams);
		}
		holder.text.setTextSize(textSizeSp);
		// The canvas owns horizontal scrolling, so TextView must keep a finite layout.
		holder.text.setSingleLine(false);
		holder.text.setHorizontallyScrolling(false);
		holder.text.setMaxLines(wrapText ? Integer.MAX_VALUE : 1);
	}

	private void configurePinch() {
		scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
			@Override
			public boolean onScaleBegin(ScaleGestureDetector detector) {
				pinchAnchor = capturePinchAnchor(detector.getFocusX(), detector.getFocusY());
				return true;
			}

			@Override
			public boolean onScale(ScaleGestureDetector detector) {
				float factor = detector.getScaleFactor();
				if (Float.isNaN(factor) || Float.isInfinite(factor)) {
					return false;
				}
				pendingTextSizeSp = Math.max(10f, Math.min(30f, pendingTextSizeSp * factor));
				if (pinchAnchor != null) {
					horizontalScroll.getLocationInWindow(touchLocation);
					pinchAnchor.focusX = detector.getFocusX() - touchLocation[0];
					pinchAnchor.focusY = detector.getFocusY() - touchLocation[1];
				}
				scheduleFontFrame();
				return true;
			}
		});
		scaleDetector.setQuickScaleEnabled(false);
		scaleDetector.setStylusScaleEnabled(false);
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		if (scaleDetector == null) {
			return super.dispatchTouchEvent(event);
		}
		int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			// Recover after focus loss even if the previous stream never sent its UP.
			consumingPinch = false;
			horizontalScroll.getParent().requestDisallowInterceptTouchEvent(false);
			horizontalScroll.getLocationInWindow(touchLocation);
			logGesture = event.getX() >= touchLocation[0] && event.getX() < touchLocation[0] + horizontalScroll.getWidth()
					&& event.getY() >= touchLocation[1] && event.getY() < touchLocation[1] + horizontalScroll.getHeight();
		}
		if (!logGesture) {
			return super.dispatchTouchEvent(event);
		}
		if (event.getPointerCount() >= 2 && !consumingPinch) {
			consumingPinch = true;
			recyclerView.stopScroll();
			horizontalScroll.fling(0);
			MotionEvent cancel = MotionEvent.obtain(event);
			cancel.setAction(MotionEvent.ACTION_CANCEL);
			super.dispatchTouchEvent(cancel);
			cancel.recycle();
			horizontalScroll.getParent().requestDisallowInterceptTouchEvent(true);
		}
		scaleDetector.onTouchEvent(event);
		if (!consumingPinch) {
			return super.dispatchTouchEvent(event);
		}
		// Consume the complete stream, including the remaining single finger's UP.
		if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
			consumingPinch = false;
			logGesture = false;
			horizontalScroll.getParent().requestDisallowInterceptTouchEvent(false);
		}
		return true;
	}

	private PinchAnchor capturePinchAnchor(float focusX, float focusY) {
		recyclerView.getLocationInWindow(touchLocation);
		View row = recyclerView.findChildViewUnder(focusX - touchLocation[0], focusY - touchLocation[1]);
		if (row == null) {
			return null;
		}
		LineHolder holder = (LineHolder) recyclerView.getChildViewHolder(row);
		int position = holder.getBindingAdapterPosition();
		Layout layout = holder.text.getLayout();
		if (position == RecyclerView.NO_POSITION || layout == null) {
			return null;
		}
		holder.text.getLocationInWindow(touchLocation);
		int y = Math.round(focusY - touchLocation[1] - holder.text.getTotalPaddingTop());
		int visualLine = layout.getLineForVertical(Math.max(0, y));
		float x = focusX - touchLocation[0] - holder.text.getTotalPaddingLeft();
		int offset = layout.getOffsetForHorizontal(visualLine, Math.max(0f, x));
		float withinLine = Math.max(0f, Math.min(1f, (y - layout.getLineTop(visualLine))
				/ (float) Math.max(1, layout.getLineBottom(visualLine) - layout.getLineTop(visualLine))));
		horizontalScroll.getLocationInWindow(touchLocation);
		return new PinchAnchor(position, offset, withinLine,
				focusX - touchLocation[0], focusY - touchLocation[1]);
	}

	private void scheduleFontFrame() {
		if (fontFramePending) {
			return;
		}
		fontFramePending = true;
		recyclerView.postOnAnimation(() -> {
			fontFramePending = false;
			if (isFinishing() || isDestroyed() || Math.abs(textSizeSp - pendingTextSizeSp) < 0.01f) {
				return;
			}
			resizingText = true;
			textSizeSp = pendingTextSizeSp;
			updateWrapWidth();
			adapter.notifyItemRangeChanged(0, adapter.getItemCount(), TYPOGRAPHY_PAYLOAD);
			if (pinchAnchor != null) {
				// Keep the anchor holder in the laid-out set even when a tall row shrinks.
				layoutManager.setStackFromEnd(false);
				layoutManager.scrollToPositionWithOffset(pinchAnchor.position,
						Math.round(pinchAnchor.focusY) - recyclerView.getPaddingTop());
			}
			recyclerView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
				@Override
				public boolean onPreDraw() {
					recyclerView.getViewTreeObserver().removeOnPreDrawListener(this);
					restorePinchAnchor();
					resizingText = false;
					return true;
				}
			});
		});
	}

	private void restorePinchAnchor() {
		if (pinchAnchor == null) {
			return;
		}
		RecyclerView.ViewHolder found = recyclerView.findViewHolderForAdapterPosition(pinchAnchor.position);
		if (!(found instanceof LineHolder)) {
			return;
		}
		LineHolder holder = (LineHolder) found;
		Layout layout = holder.text.getLayout();
		if (layout == null) {
			return;
		}
		int offset = Math.min(pinchAnchor.offset, holder.text.length());
		int visualLine = layout.getLineForOffset(offset);
		float charY = holder.text.getTop() + holder.text.getTotalPaddingTop() + layout.getLineTop(visualLine)
				+ pinchAnchor.withinLine * (layout.getLineBottom(visualLine) - layout.getLineTop(visualLine));
		// Scroll synchronously before drawing, not a second layout on the next frame.
		recyclerView.scrollBy(0, Math.round(holder.itemView.getTop() + charY - pinchAnchor.focusY));
		if (!wrapText) {
			int charX = holder.text.getLeft() + holder.text.getTotalPaddingLeft() + Math.round(layout.getPrimaryHorizontal(offset));
			horizontalScroll.scrollTo(Math.max(0, Math.round(charX - pinchAnchor.focusX)), 0);
		}
	}

	private static final class PinchAnchor {
		final int position;
		final int offset;
		final float withinLine;
		float focusX;
		float focusY;

		PinchAnchor(int position, int offset, float withinLine, float focusX, float focusY) {
			this.position = position;
			this.offset = offset;
			this.withinLine = withinLine;
			this.focusX = focusX;
			this.focusY = focusY;
		}
	}

	private void bindViews() {
		toolbar = findViewById(R.id.toolbar);
		contentContainer = findViewById(R.id.log_content_container);
		stateText = findViewById(R.id.log_state_text);
		largeFileNotice = findViewById(R.id.log_large_file_notice);
		searchBar = findViewById(R.id.log_search_bar);
		searchInput = findViewById(R.id.log_search_input);
		matchCountText = findViewById(R.id.log_search_match_count);
		previousMatchButton = findViewById(R.id.log_search_previous);
		nextMatchButton = findViewById(R.id.log_search_next);
		horizontalScroll = findViewById(R.id.log_horizontal_scroll);
		scrollCanvas = findViewById(R.id.log_scroll_canvas);
		recyclerView = findViewById(R.id.log_lines);
		jumpBottomButton = findViewById(R.id.log_jump_bottom);
        detailActionBar = findViewById(R.id.log_detail_actions);
        detailCopyButton = findViewById(R.id.log_detail_copy);
        detailShareButton = findViewById(R.id.log_detail_share);
        detailExportButton = findViewById(R.id.log_detail_export);
        detailLocationButton = findViewById(R.id.log_detail_location);
        rangeActionBar = findViewById(R.id.log_range_actions);
        rangeCopyButton = findViewById(R.id.log_range_copy);
        rangeShareButton = findViewById(R.id.log_range_share);
        rangeExpandButton = findViewById(R.id.log_range_expand);
        SystemBarInsetsHelper.applySystemBarPadding(toolbar, true, true, false, true);
        SystemBarInsetsHelper.applySystemBarPadding(findViewById(R.id.log_tools), false, true, false, true);
        SystemBarInsetsHelper.applySystemBarPadding(contentContainer, false, true, true, true);
        SystemBarInsetsHelper.applySystemBarPadding(detailActionBar, false, true, true, true);
        SystemBarInsetsHelper.applySystemBarPadding(rangeActionBar, false, true, true, true);
	}

	private void configureToolbar() {
		toolbar.setNavigationOnClickListener(v -> handleBackNavigation());
		toolbar.setOnMenuItemClickListener(item -> {
			switch (item.getItemId()) {
				case MENU_SEARCH:
					openSearch();
					return true;
				case MENU_WRAP:
					wrapText = !wrapText;
					updateWrapWidth();
					adapter.notifyDataSetChanged();
					updateToolbar();
					return true;
				case MENU_MORE:
					showMoreMenu(toolbar.findViewById(MENU_MORE));
					return true;
				case MENU_SELECT_ALL:
					selectAllVisibleLines();
					return true;
				default:
					return false;
			}
		});
	}

	private void updateToolbar() {
		boolean selecting = rangeStart >= 0;
		toolbar.setBackgroundTintList(null);
		toolbar.setBackgroundColor(selecting ? COLOR_PRIMARY_CONTAINER : COLOR_SURFACE);
		toolbar.setTitleTextColor(Color.WHITE);
		toolbar.setNavigationIcon(MaterialSymbols.drawable(this, selecting ? "close" : "arrow_back", Color.WHITE, 24));
		toolbar.getMenu().clear();
		if (selecting) {
			int end = rangeEnd < 0 ? rangeStart : rangeEnd;
			toolbar.setTitle(getString(R.string.log_file_viewer_range_selected,
					lines.get(Math.min(rangeStart, end)).number, lines.get(Math.max(rangeStart, end)).number));
			toolbar.setSubtitle(null);
			addToolbarItem(MENU_SELECT_ALL, R.string.log_file_viewer_select_all_lines, "select_all", Color.WHITE);
		} else {
			toolbar.setTitle(displayName);
			toolbar.setSubtitle(sourceLabel + " · " + Formatter.formatFileSize(this, fileSize) + " · " + formatDate(lastModified));
			addToolbarItem(MENU_WRAP, R.string.log_file_viewer_wrap_text, "wrap_text", wrapText ? COLOR_PRIMARY : Color.WHITE);
		}
		addToolbarItem(MENU_SEARCH, R.string.log_file_viewer_search, "search", Color.WHITE);
		addToolbarItem(MENU_MORE, R.string.log_file_viewer_more, "more_vert", Color.WHITE);
		jumpBottomButton.setImageDrawable(MaterialSymbols.drawable(this, selecting ? "unfold_more" : "vertical_align_bottom", COLOR_ON_PRIMARY, 24));
		jumpBottomButton.setContentDescription(getString(selecting ? R.string.log_detail_expand_context : R.string.log_file_viewer_jump_bottom));
        if (selecting) {
            detailActionBar.setVisibility(View.GONE);
            if (rangeActionBar.getVisibility() != View.VISIBLE) {
                showBar(rangeActionBar);
            }
        } else {
            detailActionBar.setVisibility(View.VISIBLE);
            rangeActionBar.animate().cancel();
            rangeActionBar.setVisibility(View.GONE);
        }
		rangeCopyButton.setEnabled(selecting);
		rangeShareButton.setEnabled(selecting);
		rangeExpandButton.setEnabled(selecting);
	}

	private void addToolbarItem(int id, int titleRes, String glyph, int tint) {
		toolbar.getMenu().add(Menu.NONE, id, Menu.NONE, titleRes)
				.setIcon(MaterialSymbols.drawable(this, glyph, tint, 24))
				.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
	}

	private void handleBackNavigation() {
		if (rangeStart >= 0) {
			clearRangeSelection();
		} else if (searchBar.getVisibility() == View.VISIBLE) {
			closeSearch();
		} else {
			finish();
		}
	}

	private void configureLevelFilters() {
		ChipGroup group = findViewById(R.id.log_level_chip_group);
		errorChip = createLevelChip("E", COLOR_ERROR);
		warningChip = createLevelChip("W", COLOR_WARNING);
		infoChip = createLevelChip("I", COLOR_PRIMARY);
		group.addView(errorChip);
		group.addView(warningChip);
		group.addView(infoChip);
		errorChip.setOnCheckedChangeListener((v, checked) -> applyLineFilter());
		warningChip.setOnCheckedChangeListener((v, checked) -> applyLineFilter());
		infoChip.setOnCheckedChangeListener((v, checked) -> applyLineFilter());
	}

	private Chip createLevelChip(String level, int color) {
		Chip chip = new Chip(this);
		chip.setText(level + " 0");
		chip.setCheckable(true);
		chip.setChecked(true);
		chip.setEnsureMinTouchTargetSize(true);
		chip.setTextSize(14);
		chip.setTextColor(color);
		chip.setChipBackgroundColor(new ColorStateList(
				new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
				new int[] {COLOR_PRIMARY_CONTAINER, COLOR_SURFACE}));
		chip.setCheckedIconTint(ColorStateList.valueOf(color));
		return chip;
	}

	private void configureSearch() {
		TextInputLayout inputLayout = findViewById(R.id.log_search_layout);
		inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_FILLED);
		inputLayout.setHint(getString(R.string.log_file_viewer_search_hint));
		inputLayout.setStartIconDrawable(MaterialSymbols.drawable(this, "search", COLOR_MUTED, 20));
		searchInput.setSingleLine(true);
		searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		searchInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				searchQuery = s == null ? "" : s.toString();
				currentMatch = -1;
				rebuildSearchMatches();
				if (adapter != null) {
					adapter.notifyDataSetChanged();
					if (!matches.isEmpty()) {
						scrollToMatch(false);
					}
				}
			}

			@Override
			public void afterTextChanged(Editable s) {
			}
		});
		previousMatchButton.setImageDrawable(MaterialSymbols.drawable(this, "keyboard_arrow_up", COLOR_MUTED, 24));
		nextMatchButton.setImageDrawable(MaterialSymbols.drawable(this, "keyboard_arrow_down", COLOR_MUTED, 24));
		ImageButton close = findViewById(R.id.log_search_close);
		close.setImageDrawable(MaterialSymbols.drawable(this, "close", COLOR_MUTED, 22));
		previousMatchButton.setOnClickListener(v -> moveToMatch(-1));
		nextMatchButton.setOnClickListener(v -> moveToMatch(1));
		close.setOnClickListener(v -> closeSearch());
	}


    private void configureDetailActions() {
        detailCopyButton.setIcon(MaterialSymbols.drawable(this, "content_copy", COLOR_MUTED, 20));
        detailShareButton.setIcon(MaterialSymbols.drawable(this, "share", COLOR_MUTED, 20));
        detailExportButton.setIcon(MaterialSymbols.drawable(this, "ios_share", COLOR_MUTED, 20));
        detailLocationButton.setIcon(MaterialSymbols.drawable(this, "folder_open", COLOR_MUTED, 20));
        detailCopyButton.setOnClickListener(v -> copyAllContent());
        detailShareButton.setOnClickListener(v -> shareOriginalFile());
        detailExportButton.setOnClickListener(v -> exportOriginalFile());
        detailLocationButton.setOnClickListener(v -> openFileLocation());
    }
	private void configureRangeActions() {
		rangeCopyButton.setIcon(MaterialSymbols.drawable(this, "content_copy", COLOR_MUTED, 20));
		rangeShareButton.setIcon(MaterialSymbols.drawable(this, "share", COLOR_MUTED, 20));
		rangeExpandButton.setIcon(MaterialSymbols.drawable(this, "unfold_more", COLOR_MUTED, 20));
		rangeCopyButton.setOnClickListener(v -> copyText(selectedRangeText(), R.string.log_file_viewer_range_copied));
		rangeShareButton.setOnClickListener(v -> shareSelectedRange());
		rangeExpandButton.setOnClickListener(v -> expandSelectedRange());
	}

	private PreviewData readPreviewData(File file) throws Exception {
		long length = file.length();
		long modified = file.lastModified();
		if (!isProbablyText(file)) {
			return new PreviewData(new ArrayList<>(), false, false, 0, length, modified);
		}
		boolean truncated = length > MAX_PREVIEW_BYTES;
		long firstLineNumber = 1L;
		byte[] bytes;
		try (InputStream input = new BufferedInputStream(new FileInputStream(file));
			 ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(length, MAX_PREVIEW_BYTES))) {
			long beforeTail = truncated ? length - MAX_PREVIEW_BYTES : 0L;
			byte[] buffer = new byte[8192];
			// Count skipped lines without retaining the file prefix. The preview stays bounded.
			while (beforeTail > 0L) {
				int read = input.read(buffer, 0, (int) Math.min(beforeTail, buffer.length));
				if (read < 0) {
					break;
				}
				for (int i = 0; i < read; i++) {
					if (buffer[i] == '\n') {
						firstLineNumber++;
					}
				}
				beforeTail -= read;
			}
			long remaining = Math.min(length, MAX_PREVIEW_BYTES);
			while (remaining > 0L) {
				int read = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
				if (read < 0) {
					break;
				}
				output.write(buffer, 0, read);
				remaining -= read;
			}
			bytes = output.toByteArray();
		}
		int offset = 0;
		if (truncated) {
			for (int i = 0; i < bytes.length; i++) {
				if (bytes[i] == '\n') {
					offset = i + 1;
					firstLineNumber++;
					break;
				}
			}
		}
		String content = new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
		List<LogLine> parsed = new ArrayList<>();
		Paint paint = new Paint();
		paint.setTypeface(Typeface.MONOSPACE);
		paint.setTextSize(WIDTH_BASE_SP * getResources().getDisplayMetrics().scaledDensity);
		int maxWidth = 0;
		int start = 0;
		int previousLevel = LEVEL_INFO;
		while (start < content.length()) {
			int end = content.indexOf('\n', start);
			if (end < 0) {
				end = content.length();
			}
			String text = content.substring(start, end);
			if (text.endsWith("\r")) {
				text = text.substring(0, text.length() - 1);
			}
			int level = detectLevel(text, previousLevel);
			previousLevel = level;
			parsed.add(new LogLine(firstLineNumber++, text, level));
			int tabs = 0;
			for (int i = 0; i < text.length(); i++) {
				if (text.charAt(i) == '\t') {
					tabs++;
				}
			}
			// A default Android tab stop advances at most 20 px. Cache an upper
			// bound at minimum font size so every later pinch remains fully scrollable.
			maxWidth = Math.max(maxWidth, (int) Math.ceil(paint.measureText(text)) + tabs * 20);
			start = end + 1;
		}
		return new PreviewData(parsed, truncated, true, maxWidth, length, modified);
	}

	private int detectLevel(String text, int previousLevel) {
		Matcher matcher = LEVEL_PATTERN.matcher(text);
		if (matcher.find()) {
			String label = matcher.group(1).toUpperCase(Locale.ROOT);
			if ("E".equals(label) || "ERROR".equals(label) || "ERR".equals(label) || "FATAL".equals(label)) {
				return LEVEL_ERROR;
			}
			if ("W".equals(label) || "WARN".equals(label) || "WARNING".equals(label)) {
				return LEVEL_WARNING;
			}
			return LEVEL_INFO;
		}
		if (text.contains("Exception") || text.startsWith("ERROR:")) {
			return LEVEL_ERROR;
		}
		if (previousLevel == LEVEL_ERROR && (text.startsWith(" ") || text.startsWith("\t") || text.startsWith("Caused by:") || text.startsWith("---"))) {
			return LEVEL_ERROR;
		}
		return LEVEL_INFO;
	}

	private void applyPreview(PreviewData data) {
		if (isFinishing() || isDestroyed()) {
			return;
		}
		lines.clear();
		lines.addAll(data.lines);
		previewReady = data.text;
		fileSize = data.size;
		lastModified = data.modified;
		maxLineWidthAtBase = data.width;
		largeFileNotice.setVisibility(data.truncated ? View.VISIBLE : View.GONE);
		int errors = 0;
		int warnings = 0;
		for (LogLine line : lines) {
			if (line.level == LEVEL_ERROR) {
				errors++;
			} else if (line.level == LEVEL_WARNING) {
				warnings++;
			}
		}
		errorChip.setText("E " + errors);
		warningChip.setText("W " + warnings);
		infoChip.setText("I " + (lines.size() - errors - warnings));
		updateToolbar();
		applyLineFilter();
		if (!data.text) {
			stateText.setText(R.string.log_viewer_binary_unavailable);
			stateText.setVisibility(View.VISIBLE);
		}
		updateWrapWidth();
		scrollToBottom(false);
	}

	private void showLoadError(Exception exception) {
		stateText.setText(getString(R.string.error_operation_failed) + ": " + buildErrorDetail(exception));
		stateText.setVisibility(View.VISIBLE);
		jumpBottomButton.setVisibility(View.GONE);
	}

	private void applyLineFilter() {
		if (adapter == null) {
			return;
		}
		clearRangeSelection();
		visibleLineIndices.clear();
		for (int i = 0; i < lines.size(); i++) {
			int level = lines.get(i).level;
			boolean enabled = level == LEVEL_ERROR ? errorChip.isChecked() : level == LEVEL_WARNING ? warningChip.isChecked() : infoChip.isChecked();
			if (enabled) {
				visibleLineIndices.add(i);
			}
		}
		currentMatch = -1;
		rebuildSearchMatches();
		adapter.notifyDataSetChanged();
		stateText.setVisibility(visibleLineIndices.isEmpty() ? View.VISIBLE : View.GONE);
		stateText.setText(lines.isEmpty() ? R.string.log_viewer_content_empty : R.string.log_file_viewer_no_lines);
		jumpBottomButton.setVisibility(visibleLineIndices.isEmpty() ? View.GONE : View.VISIBLE);
	}

	private void updateWrapWidth() {
		int viewport = horizontalScroll.getWidth() - horizontalScroll.getPaddingLeft() - horizontalScroll.getPaddingRight();
		if (viewport <= 0) {
			return;
		}
		Paint paint = textPaint(textSizeSp - 1f);
		String lastNumber = lines.isEmpty() ? "1" : String.valueOf(lines.get(lines.size() - 1).number);
		numberWidth = Math.max(dp(52), (int) Math.ceil(paint.measureText(lastNumber)) + dp(16));
		int width = wrapText ? viewport : Math.max(viewport,
				(int) Math.ceil(maxLineWidthAtBase * textSizeSp / WIDTH_BASE_SP) + numberWidth + dp(24));
		ViewGroup.LayoutParams params = scrollCanvas.getLayoutParams();
		if (params.width != width) {
			params.width = width;
			scrollCanvas.setLayoutParams(params);
		}
		horizontalScroll.setHorizontalScrollBarEnabled(!wrapText);
		if (wrapText) {
			horizontalScroll.scrollTo(0, 0);
		}
	}

	private void rebuildSearchMatches() {
		matches.clear();
		if (!searchQuery.isEmpty()) {
			for (int position = 0; position < visibleLineIndices.size(); position++) {
				String text = lines.get(visibleLineIndices.get(position)).text;
				int from = 0;
				int found;
				while ((found = findMatch(text, searchQuery, from)) >= 0) {
					matches.add(new Match(position, found));
					from = found + searchQuery.length();
				}
			}
		}
		if (matches.isEmpty()) {
			currentMatch = -1;
		} else if (currentMatch < 0 || currentMatch >= matches.size()) {
			currentMatch = 0;
		}
		updateMatchCount();
	}

	private int findMatch(String text, String query, int from) {
		if (query.isEmpty()) {
			return -1;
		}
		for (int i = from; i <= text.length() - query.length(); i++) {
			if (text.regionMatches(true, i, query, 0, query.length())) {
				return i;
			}
		}
		return -1;
	}

	private void updateMatchCount() {
		matchCountText.setText(getString(R.string.log_file_viewer_search_count, currentMatch < 0 ? 0 : currentMatch + 1, matches.size()));
		previousMatchButton.setEnabled(!matches.isEmpty());
		nextMatchButton.setEnabled(!matches.isEmpty());
	}

	private void moveToMatch(int direction) {
		if (matches.isEmpty()) {
			return;
		}
		int previousPosition = matches.get(currentMatch).position;
		currentMatch = (currentMatch + direction + matches.size()) % matches.size();
		adapter.notifyItemChanged(previousPosition);
		adapter.notifyItemChanged(matches.get(currentMatch).position);
		updateMatchCount();
		scrollToMatch(true);
	}

	private void scrollToMatch(boolean animate) {
		if (currentMatch < 0) {
			return;
		}
		Match match = matches.get(currentMatch);
		layoutManager.scrollToPositionWithOffset(match.position, dp(16));
		if (!wrapText) {
			LogLine line = lines.get(visibleLineIndices.get(match.position));
			TextPaint paint = textPaint(textSizeSp);
			int x = Math.max(0, numberWidth + dp(4) + (int) Layout.getDesiredWidth(line.text, 0, match.start, paint) - dp(40));
			if (animate) {
				horizontalScroll.smoothScrollTo(x, 0);
			} else {
				horizontalScroll.scrollTo(x, 0);
			}
		}
	}

	private void openSearch() {
		if (searchBar.getVisibility() != View.VISIBLE) {
			showBar(searchBar);
		}
		searchInput.requestFocus();
		InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		if (keyboard != null) {
			keyboard.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
		}
	}

	private void closeSearch() {
		searchBar.animate().cancel();
		searchBar.setVisibility(View.GONE);
		searchInput.setText("");
		searchInput.clearFocus();
		InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		if (keyboard != null) {
			keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
		}
	}

	private void showBar(View bar) {
		bar.setVisibility(View.VISIBLE);
		bar.setAlpha(0f);
		bar.setTranslationY(dp(12));
		bar.animate().alpha(1f).translationY(0f).setDuration(220)
				.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
	}

	private void scrollToBottom(boolean animate) {
		if (visibleLineIndices.isEmpty()) {
			return;
		}
		recyclerView.post(() -> {
			int last = visibleLineIndices.size() - 1;
			if (animate) {
				recyclerView.smoothScrollToPosition(last);
			} else {
				layoutManager.setStackFromEnd(true);
				layoutManager.scrollToPositionWithOffset(last, 0);
			}
		});
	}

	private void onLineNumberClicked(int lineIndex) {
		if (consumingPinch || resizingText) {
			return;
		}
		if (rangeStart < 0 || !awaitingRangeEnd) {
			rangeStart = lineIndex;
			rangeEnd = lineIndex;
			awaitingRangeEnd = true;
			toast(getString(R.string.log_detail_range_hint));
		} else {
			rangeEnd = lineIndex;
			awaitingRangeEnd = false;
		}
		updateToolbar();
		adapter.notifyDataSetChanged();
	}

	private void clearRangeSelection() {
		if (rangeStart < 0) {
			return;
		}
		rangeStart = -1;
		rangeEnd = -1;
		awaitingRangeEnd = false;
		rangeActionBar.animate().cancel();
		rangeActionBar.setVisibility(View.GONE);
		updateToolbar();
		adapter.notifyDataSetChanged();
	}

	private void selectAllVisibleLines() {
		if (visibleLineIndices.isEmpty()) {
			return;
		}
		rangeStart = visibleLineIndices.get(0);
		rangeEnd = visibleLineIndices.get(visibleLineIndices.size() - 1);
		awaitingRangeEnd = false;
		updateToolbar();
		adapter.notifyDataSetChanged();
	}

	private boolean isLineSelected(int index) {
		if (rangeStart < 0) {
			return false;
		}
		int end = rangeEnd < 0 ? rangeStart : rangeEnd;
		return index >= Math.min(rangeStart, end) && index <= Math.max(rangeStart, end);
	}

	private String selectedRangeText() {
		if (rangeStart < 0 || rangeEnd < 0) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		boolean first = true;
		for (int index : visibleLineIndices) {
			if (!isLineSelected(index)) {
				continue;
			}
			if (!first) {
				text.append('\n');
			}
			text.append(lines.get(index).text);
			first = false;
		}
		return text.toString();
	}

	private void expandSelectedRange() {
		if (rangeStart < 0 || rangeEnd < 0) {
			return;
		}
		int start = Math.min(rangeStart, rangeEnd);
		int end = Math.max(rangeStart, rangeEnd);
		rangeStart = Math.max(0, start - 20);
		rangeEnd = Math.min(lines.size() - 1, end + 20);
		awaitingRangeEnd = false;
		updateToolbar();
		adapter.notifyDataSetChanged();
	}

	private void showMoreMenu(View anchor) {
		PopupMenu popup = new PopupMenu(this, anchor == null ? toolbar : anchor, Gravity.END);
		popup.getMenu().add(Menu.NONE, MENU_ANALYZE, Menu.NONE, R.string.log_detail_analyze)
				.setIcon(MaterialSymbols.drawable(this, "auto_awesome", COLOR_MUTED, 24)).setEnabled(sourceFile != null);
		popup.setForceShowIcon(true);
		popup.setOnMenuItemClickListener(item -> {
			if (item.getItemId() == MENU_ANALYZE) {
				if (sourceFile == null || !sourceFile.isFile()) {
					showError(new IOException(getString(R.string.log_file_viewer_missing)));
				} else {
					startActivity(LogAnalysisActivity.createIntent(this, sourceFile));
				}
				return true;
			}
			return false;
		});
		popup.show();
	}

    private void copyAllContent() {
        if (sourceFile == null || !sourceFile.isFile()) {
            showError(new IOException(getString(R.string.log_file_viewer_missing)));
            return;
        }
        File file = sourceFile;
        new Thread(() -> {
            try {
                if (!isProbablyText(file)) {
                    throw new IOException(getString(R.string.log_viewer_binary_unavailable));
                }
                String text = readTextFile(file);
                runOnUiThread(() -> copyText(text, R.string.log_file_viewer_all_copied));
            } catch (Exception exception) {
                runOnUiThread(() -> showError(exception));
            }
        }).start();
    }

    private void copyText(String text, int messageRes) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_viewer_clipboard_label), text));
                toast(getString(messageRes));
            } catch (Exception exception) {
                showError(exception);
            }
        }
    }

    private void shareSelectedRange() {
        String text = selectedRangeText();
        new Thread(() -> {
            try {
                File sharedDirectory = new File(getCacheDir(), "shared");
                if (!sharedDirectory.isDirectory() && !sharedDirectory.mkdirs() && !sharedDirectory.isDirectory()) {
                    throw new IOException("Unable to create shared directory");
                }
                File output = File.createTempFile("sts2-log-fragment-", ".txt", sharedDirectory);
                try (OutputStream stream = new FileOutputStream(output)) {
                    stream.write(text.getBytes(StandardCharsets.UTF_8));
                }
                Uri uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", output);
                runOnUiThread(() -> shareUri(uri, "text/plain", R.string.log_file_viewer_share_fragment_chooser));
            } catch (Exception exception) {
                runOnUiThread(() -> showError(exception));
            }
        }).start();
    }

    private void shareOriginalFile() {
        if (sourceFile == null || !sourceFile.isFile()) {
            showError(new IOException(getString(R.string.log_file_viewer_missing)));
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", sourceFile);
            shareUri(uri, "application/octet-stream", R.string.log_file_viewer_share_chooser);
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private void shareUri(Uri uri, String mimeType, int chooserRes) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setClipData(ClipData.newRawUri(displayName, uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(chooserRes)));
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private void exportOriginalFile() {
        if (sourceFile == null || !sourceFile.isFile()) {
            showError(new IOException(getString(R.string.log_file_viewer_missing)));
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(previewReady ? "text/plain" : "application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, sourceFile.getName());
            startActivityForResult(intent, REQUEST_EXPORT_FILE);
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private void openFileLocation() {
        if (sourceFile == null || !sourceFile.isFile()) {
            showError(new IOException(getString(R.string.log_file_viewer_missing)));
            return;
        }
        try {
            File file = sourceFile.getCanonicalFile();
            File root = getFilesDir().getCanonicalFile();
            if (file.getPath().startsWith(root.getPath() + File.separator)) {
                startActivity(FileBrowserActivity.createIntent(this, file.getParentFile()));
                return;
            }
            // The file browser intentionally stays inside internal files. External logs use the system provider.
            Uri uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/octet-stream");
            intent.setClipData(ClipData.newRawUri(displayName, uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.log_file_viewer_external_location)));
        } catch (Exception exception) {
            showError(exception);
        }
    }

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQUEST_EXPORT_FILE || resultCode != RESULT_OK || data == null || data.getData() == null || sourceFile == null) {
			return;
		}
		Uri outputUri = data.getData();
		File file = sourceFile;
		new Thread(() -> {
			try (InputStream input = new BufferedInputStream(new FileInputStream(file));
				 OutputStream output = getContentResolver().openOutputStream(outputUri, "w")) {
				if (output == null) {
					throw new IOException("Received null stream from content resolver");
				}
				copyStream(input, output);
				runOnUiThread(() -> toast(getString(R.string.log_file_viewer_export_done)));
			} catch (Exception exception) {
				runOnUiThread(() -> showError(exception));
			}
		}).start();
	}

	private boolean isProbablyText(File file) throws IOException {
		try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
			byte[] sample = new byte[2048];
			int read = input.read(sample);
			for (int i = 0; i < read; i++) {
				if (sample[i] == 0) {
					return false;
				}
			}
			return true;
		}
	}

	private String readTextFile(File file) throws IOException {
		try (InputStream input = new BufferedInputStream(new FileInputStream(file));
			 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			copyStream(input, output);
			return new String(output.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private void copyStream(InputStream input, OutputStream output) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = input.read(buffer)) != -1) {
			output.write(buffer, 0, read);
		}
		output.flush();
	}

	private String formatDate(long time) {
		if (time <= 0L) {
			return getString(R.string.log_file_viewer_unknown_time);
		}
		Calendar now = Calendar.getInstance();
		Calendar value = Calendar.getInstance();
		value.setTimeInMillis(time);
		if (now.get(Calendar.YEAR) == value.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == value.get(Calendar.DAY_OF_YEAR)) {
			return getString(R.string.log_viewer_date_today) + " " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(value.getTime());
		}
		return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(time));
	}

	private int dp(int value) {
		return Math.round(value * getResources().getDisplayMetrics().density);
	}

	private String buildErrorDetail(Exception exception) {
		String detail = exception.getMessage();
		return detail == null || detail.trim().isEmpty() ? exception.getClass().getSimpleName() : detail;
	}

	private void showError(Exception exception) {
		Snackbar.make(contentContainer, getString(R.string.error_operation_failed) + ": " + buildErrorDetail(exception), Snackbar.LENGTH_LONG).show();
	}

	private void toast(String message) {
		Snackbar.make(contentContainer, message, Snackbar.LENGTH_SHORT).show();
	}

	private final class LineAdapter extends RecyclerView.Adapter<LineHolder> {
		@Override
		public LineHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			LinearLayout row = new LinearLayout(parent.getContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.TOP);
			row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			TextView number = new TextView(parent.getContext());
			number.setTypeface(Typeface.MONOSPACE);
			number.setTextSize(textSizeSp - 1f);
			number.setGravity(Gravity.END);
			number.setPadding(dp(8), dp(5), dp(8), dp(5));
			row.addView(number, new LinearLayout.LayoutParams(numberWidth, ViewGroup.LayoutParams.MATCH_PARENT));
			TextView text = new TextView(parent.getContext());
			text.setTypeface(Typeface.MONOSPACE);
			text.setTextSize(textSizeSp);
			text.setPadding(dp(4), dp(5), dp(12), dp(5));
			text.setIncludeFontPadding(false);
			row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
			return new LineHolder(row, number, text);
		}

		@Override
		public void onBindViewHolder(LineHolder holder, int position, List<Object> payloads) {
			if (!payloads.isEmpty()) {
				applyHolderTypography(holder);
			} else {
				onBindViewHolder(holder, position);
			}
		}

		@Override
		public void onBindViewHolder(LineHolder holder, int position) {
			int index = visibleLineIndices.get(position);
			LogLine line = lines.get(index);
			boolean selected = isLineSelected(index);
			holder.itemView.setBackgroundColor(selected ? COLOR_PRIMARY_CONTAINER : line.level == LEVEL_ERROR ? COLOR_ERROR_LINE : Color.TRANSPARENT);
			holder.number.setText(String.valueOf(line.number));
			holder.number.setTextColor(selected ? COLOR_ON_PRIMARY_CONTAINER : COLOR_MUTED);
			holder.number.setContentDescription(getString(R.string.log_file_viewer_select_line, line.number));
			holder.text.setTextColor(line.level == LEVEL_ERROR ? COLOR_ERROR : line.level == LEVEL_WARNING ? COLOR_WARNING : COLOR_ON_SURFACE);
			applyHolderTypography(holder);
			if (searchQuery.isEmpty()) {
				holder.text.setText(line.text);
				return;
			}
			SpannableString styled = new SpannableString(line.text);
			int from = 0;
			int found;
			Match current = currentMatch < 0 ? null : matches.get(currentMatch);
			while ((found = findMatch(line.text, searchQuery, from)) >= 0) {
				boolean active = current != null && current.position == position && current.start == found;
				styled.setSpan(new BackgroundColorSpan(active ? COLOR_PRIMARY : COLOR_WARNING), found, found + searchQuery.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
				styled.setSpan(new ForegroundColorSpan(COLOR_ON_PRIMARY), found, found + searchQuery.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
				from = found + searchQuery.length();
			}
			holder.text.setText(styled);
		}

		@Override
		public int getItemCount() {
			return visibleLineIndices.size();
		}
	}

	private final class LineHolder extends RecyclerView.ViewHolder {
		final TextView number;
		final TextView text;

		LineHolder(View row, TextView number, TextView text) {
			super(row);
			this.number = number;
			this.text = text;
			number.setOnClickListener(v -> {
				int position = getBindingAdapterPosition();
				if (position != RecyclerView.NO_POSITION) {
					onLineNumberClicked(visibleLineIndices.get(position));
				}
			});
			View.OnLongClickListener selectLine = v -> {
				int position = getBindingAdapterPosition();
				if (position != RecyclerView.NO_POSITION && !consumingPinch && !resizingText) {
					onLineNumberClicked(visibleLineIndices.get(position));
					return true;
				}
				return false;
			};
			row.setOnLongClickListener(selectLine);
			text.setOnLongClickListener(selectLine);
			number.setOnLongClickListener(selectLine);
			TypedValue ripple = new TypedValue();
			if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
				row.setForeground(getDrawable(ripple.resourceId));
			}
		}
	}

	private static final class LogLine {
		final long number;
		final String text;
		final int level;

		LogLine(long number, String text, int level) {
			this.number = number;
			this.text = text;
			this.level = level;
		}
	}

	private static final class PreviewData {
		final List<LogLine> lines;
		final boolean truncated;
		final boolean text;
		final int width;
		final long size;
		final long modified;

		PreviewData(List<LogLine> lines, boolean truncated, boolean text, int width, long size, long modified) {
			this.lines = lines;
			this.truncated = truncated;
			this.text = text;
			this.width = width;
			this.size = size;
			this.modified = modified;
		}
	}

	private static final class Match {
		final int position;
		final int start;

		Match(int position, int start) {
			this.position = position;
			this.start = start;
		}
	}
}
