package com.godot.game.loganalysis;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.OneShotPreDrawListener;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.godot.game.ExtraSettingsUi;
import com.godot.game.MaterialSymbols;
import com.godot.game.R;
import com.godot.game.SystemBarInsetsHelper;
import com.godot.game.llm.Cancellation;
import com.godot.game.llm.ChatSession;
import com.godot.game.llm.LlmConfig;
import com.godot.game.llm.LlmSettings;
import com.godot.game.llm.OpenAiClient;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LogAnalysisActivity extends AppCompatActivity {
	private static final String EXTRA_CURRENT = "com.godot.game.loganalysis.CURRENT_LOG";
	private static final int CONFIG = 1, NEW_CHAT = 2;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final ExecutorService worker = Executors.newSingleThreadExecutor();
	private ChatSession session; // Only the serial worker reads or changes conversation history.
	private LlmConfig config = new LlmConfig("", "", "", "");
	private Reply active;
	private File currentFile;
	private boolean ready;
	private MaterialToolbar toolbar;
	private RecyclerView list;
	private LogChatAdapter chat;
	private View welcome, connect;
	private TextInputEditText question;
	private MaterialButton send;

	public static Intent createIntent(Context context, File currentLog) {
		Intent intent = new Intent(context, LogAnalysisActivity.class);
		if (currentLog != null) intent.putExtra(EXTRA_CURRENT, currentLog.getAbsolutePath());
		return intent;
	}

	@Override protected void onCreate(Bundle state) {
		setTheme(R.style.Theme_Sts2Tools);
		super.onCreate(state);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		setContentView(R.layout.activity_log_analysis);
		View root = findViewById(R.id.analysis_root);
		SystemBarInsetsHelper.applySystemBarPaddingWithIme(root, true, true, true, true);
		View column = findViewById(R.id.analysis_column);
		final int readingWidth = ExtraSettingsUi.dp(this, 720);
		column.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			int inset = Math.max(0, (r - l - readingWidth) / 2);
			if (inset != v.getPaddingLeft()) v.setPadding(inset, 0, inset, 0);
		});

		String path = getIntent().getStringExtra(EXTRA_CURRENT);
		currentFile = path == null ? null : new File(path);
		toolbar = findViewById(R.id.analysis_toolbar);
		int color = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface);
		toolbar.setNavigationIcon(MaterialSymbols.drawable(this, "arrow_back", color, 24));
		toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description);
		toolbar.setNavigationOnClickListener(v -> finish());
		addMenu(CONFIG, R.string.log_analysis_config, "tune", color);
		addMenu(NEW_CHAT, R.string.log_analysis_restart, "add", color);
		toolbar.setOnMenuItemClickListener(item -> {
			if (item.getItemId() == CONFIG) showConfig();
			else if (item.getItemId() == NEW_CHAT) { question.setText(""); resetConversation(); }
			return true;
		});

		welcome = findViewById(R.id.analysis_welcome);
		connect = findViewById(R.id.analysis_connect);
		connect.setOnClickListener(v -> showConfig());
		for (int id : new int[]{R.id.analysis_prompt_crash, R.id.analysis_prompt_mods}) {
			findViewById(id).setOnClickListener(v -> {
				question.setText(((TextView) v).getText());
				requestSend();
			});
		}
		list = findViewById(R.id.analysis_messages);
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setItemAnimator(null);
		chat = new LogChatAdapter(this, () -> {
			if (ready && active == null) sendQuestion(chat.lastQuestion(), true);
		});
		list.setAdapter(chat);

		question = findViewById(R.id.analysis_question);
		send = findViewById(R.id.analysis_send);
		send.setOnClickListener(v -> { if (active != null) stopRequest(); else requestSend(); });
		question.setOnEditorActionListener((v, action, event) -> {
			if (action != EditorInfo.IME_ACTION_SEND) return false;
			requestSend();
			return true;
		});
		question.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateSendEnabled(); }
			@Override public void afterTextChanged(Editable s) {}
		});
		updateScreen();
		worker.execute(() -> {
			try {
				LlmConfig loaded = LlmSettings.read(getApplicationContext());
				onScreen(() -> { config = loaded; ready = true; updateScreen(); });
			} catch (IOException e) {
				onScreen(() -> {
					ready = true; updateScreen();
					Snackbar.make(root, R.string.log_analysis_config_failed, Snackbar.LENGTH_LONG).show();
				});
			}
		});
	}

	private void addMenu(int id, int text, String symbol, int color) {
		MenuItem item = toolbar.getMenu().add(0, id, id, text);
		item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		MaterialSymbols.applyMenuIcon(this, item, symbol, color, 24);
	}

	private void onScreen(Runnable action) {
		main.post(() -> { if (!isFinishing() && !isDestroyed()) action.run(); });
	}

	private void updateSendEnabled() {
		send.setEnabled(ready && (active != null || question.length() > 0));
	}

	private void updateScreen() {
		boolean empty = chat.getItemCount() == 0;
		welcome.setVisibility(empty ? View.VISIBLE : View.GONE);
		list.setVisibility(empty ? View.GONE : View.VISIBLE);
		connect.setVisibility(ready && config.model.isEmpty() ? View.VISIBLE : View.GONE);
		findViewById(R.id.analysis_prompt_crash).setEnabled(ready);
		findViewById(R.id.analysis_prompt_mods).setEnabled(ready);
		toolbar.setSubtitle(!ready ? getString(R.string.log_analysis_loading) : config.model.isEmpty()
			? getString(R.string.log_analysis_unconfigured) : config.model);
		toolbar.getMenu().findItem(CONFIG).setEnabled(ready);
		toolbar.getMenu().findItem(NEW_CHAT).setVisible(!empty).setEnabled(ready);
		send.setIconResource(active == null ? R.drawable.ic_ms_arrow_forward_24 : R.drawable.ic_ms_close_24);
		send.setContentDescription(getString(active == null ? R.string.log_analysis_send : R.string.log_analysis_stop));
		updateSendEnabled();
	}

	private void requestSend() {
		String text = value(question).trim();
		if (!ready || active != null || text.isEmpty()) return;
		try { config.validate(); }
		catch (IllegalArgumentException e) { showConfig(() -> sendQuestion(text, false)); return; }
		sendQuestion(text, false);
	}

	private void sendQuestion(String text, boolean retry) {
		LlmConfig connection = config;
		Reply reply = new Reply();
		active = reply;
		if (!retry) question.setText("");
		((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(question.getWindowToken(), 0);
		chat.start(text, reply.status, retry);
		updateScreen();
		list.scrollToPosition(chat.getItemCount() - 1);
		worker.execute(() -> {
			try {
				reply.token.check();
				if (session == null) {
					LogTools tools = new LogTools(getApplicationContext(), currentFile);
					session = new ChatSession(new OpenAiClient(connection), tools, tools.systemPrompt());
				}
				String answer = session.ask(text, reply.token, reply);
				onScreen(() -> finishReply(reply, answer, null));
			} catch (Exception e) {
				String error = e instanceof IOException && e.getMessage() != null ? e.getMessage() : getString(R.string.log_analysis_generic_error);
				onScreen(() -> finishReply(reply, null, error));
			}
		});
	}

	/** Coalesce deltas before parsing Markdown; at most one pending repaint per reply. */
	private final class Reply implements ChatSession.Observer, Runnable {
		final Cancellation token = new Cancellation();
		final StringBuilder draft = new StringBuilder();
		final String writing = getString(R.string.log_analysis_writing);
		String status = getString(R.string.log_analysis_working);
		boolean scheduled, changed;

		@Override public synchronized void onRoundStart() {
			if (draft.length() == 0) return;
			draft.setLength(0); changed = true; schedule();
		}
		@Override public synchronized void onText(String delta) {
			draft.append(delta); changed = true; status = writing; schedule();
		}
		@Override public synchronized void toolStatus(String summary) { status = summary; schedule(); }
		private void schedule() {
			if (!scheduled) { scheduled = true; main.postDelayed(this, 80); }
		}
		@Override public void run() {
			if (active != this) return;
			String source, label;
			synchronized (this) {
				source = changed ? draft.toString() : null; label = status;
				scheduled = changed = false;
			}
			updateAnswer(source, label, true);
		}
	}

	private void updateAnswer(String markdown, String status, boolean working) {
		boolean atEnd = !list.canScrollVertically(1) && list.getScrollState() == RecyclerView.SCROLL_STATE_IDLE;
		chat.update(markdown, status, working);
		// Follow a growing answer, but never pull the reader away from older evidence.
		if (atEnd) OneShotPreDrawListener.add(list, () -> {
			if (list.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) list.scrollBy(0, list.computeVerticalScrollRange());
		});
	}

	private void finishReply(Reply reply, String answer, String status) {
		if (reply != active) return;
		main.removeCallbacks(reply);
		if (answer == null) synchronized (reply) { answer = reply.changed ? reply.draft.toString() : null; }
		active = null;
		updateAnswer(answer, status, false);
		updateScreen();
	}

	private void stopRequest() {
		if (active == null) return;
		active.token.cancel();
		finishReply(active, null, getString(R.string.log_analysis_stopped));
	}

	private void resetConversation() {
		stopRequest();
		worker.execute(() -> session = null);
		chat.clear();
		updateScreen();
	}

	private void showConfig() { showConfig(null); }

	private void showConfig(Runnable afterSave) {
		LinearLayout form = new LinearLayout(this);
		form.setOrientation(LinearLayout.VERTICAL);
		int padding = ExtraSettingsUi.dp(this, 20);
		form.setPadding(padding, padding / 2, padding, padding / 2);
		TextView notice = new TextView(this);
		notice.setText(R.string.log_analysis_config_notice); notice.setTextSize(16);
		form.addView(notice);
		TextInputEditText url = field(form, R.string.log_analysis_base_url, config.baseUrl, false);
		TextInputEditText key = field(form, R.string.log_analysis_api_key, config.apiKey, true);
		TextInputEditText model = field(form, R.string.log_analysis_model, config.model, false);
		TextInputEditText effort = field(form, R.string.log_analysis_effort, config.reasoningEffort, false);
		TextView error = new TextView(this); error.setTextSize(16);
		error.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorError, 0xFFFFB4AB));
		form.addView(error);
		AlertDialog dialog = ExtraSettingsUi.scrollableDialog(this, form).setTitle(R.string.log_analysis_config)
			.setNegativeButton(R.string.log_analysis_cancel, null).setPositiveButton(R.string.log_analysis_save, null).create();
		dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			LlmConfig replacement = new LlmConfig(value(url), value(key), value(model), value(effort));
			try { replacement.validate(); }
			catch (IllegalArgumentException e) { error.setText(e.getMessage()); return; }
			stopRequest();
			ready = false; updateScreen();
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
			error.setText(R.string.log_analysis_saving);
			worker.execute(() -> {
				try {
					LlmSettings.save(getApplicationContext(), replacement);
					onScreen(() -> {
						config = replacement; ready = true; resetConversation();
						boolean resume = dialog.isShowing() && afterSave != null;
						dialog.dismiss();
						if (resume) afterSave.run();
					});
				} catch (IOException e) {
					onScreen(() -> {
						ready = true; updateScreen(); error.setText(e.getMessage());
						dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
					});
				}
			});
		}));
		dialog.show();
	}

	private TextInputEditText field(LinearLayout parent, int hint, String value, boolean password) {
		TextInputLayout layout = new TextInputLayout(this);
		layout.setHint(getString(hint)); layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
		TextInputEditText input = new TextInputEditText(layout.getContext());
		input.setTextSize(18); input.setSingleLine(true); input.setSaveEnabled(false);
		input.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
			: InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		if (android.os.Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
		input.setText(value);
		layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		parent.addView(layout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return input;
	}

	private static String value(TextInputEditText input) { return input.getText() == null ? "" : input.getText().toString(); }

	@Override protected void onDestroy() {
		if (active != null) active.token.cancel();
		active = null;
		main.removeCallbacksAndMessages(null);
		worker.shutdown();
		super.onDestroy();
	}
}
