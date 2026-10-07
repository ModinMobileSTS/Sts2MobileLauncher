package com.godot.game.loganalysis;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.godot.game.ExtraSettingsUi;
import com.godot.game.MaterialSymbols;
import com.godot.game.R;
import com.godot.game.RuntimeLogFiles;
import com.godot.game.SystemBarInsetsHelper;
import com.godot.game.llm.Cancellation;
import com.godot.game.llm.ChatSession;
import com.godot.game.llm.LlmConfig;
import com.godot.game.llm.LlmSettings;
import com.godot.game.llm.OpenAiClient;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UI owns consent and allowlist. All scans, encrypted preferences, and HTTP run off the main thread. */
public final class LogAnalysisActivity extends AppCompatActivity {
	private static final String EXTRA_CURRENT = "com.godot.game.loganalysis.CURRENT_LOG";
	private static final int CONFIG = 1, FILES = 2, RESTART = 3, INFO = 4;
	private final ExecutorService worker = Executors.newSingleThreadExecutor();
	private final List<File> candidates = new ArrayList<>();
	private final List<File> selected = new ArrayList<>();
	private final List<Message> messages = new ArrayList<>();
	private final MessageAdapter adapter = new MessageAdapter();
	private LlmConfig config = new LlmConfig("", "", "", "");
	private Conversation conversation = new Conversation();
	private Cancellation cancellation;
	private File currentFile;
	private volatile int generation;
	private boolean closed, ready, busy;
	private MaterialToolbar toolbar;
	private RecyclerView list;
	private TextView fileText, status;
	private TextInputEditText question;
	private MaterialButton send, stop;
	private static final class Conversation { ChatSession session; boolean consent; }
	private static final class Message {
		final String title, text;
		Message(String title, String text) { this.title = title; this.text = text; }
	}

	public static Intent createIntent(Context context, File currentLog) {
		if (currentLog == null) throw new IllegalArgumentException("currentLog is required");
		return new Intent(context, LogAnalysisActivity.class).putExtra(EXTRA_CURRENT, currentLog.getAbsolutePath());
	}

	@Override protected void onCreate(Bundle state) {
		super.onCreate(state);
		setTheme(R.style.Theme_Sts2Tools);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		setContentView(R.layout.activity_log_analysis);
		View root = findViewById(R.id.analysis_root);
		SystemBarInsetsHelper.applySystemBarPaddingWithIme(root, true, true, true, true);
		toolbar = findViewById(R.id.analysis_toolbar);
		fileText = findViewById(R.id.analysis_files);
		status = findViewById(R.id.analysis_status);
		question = findViewById(R.id.analysis_question);
		question.setSaveEnabled(false);
		send = findViewById(R.id.analysis_send);
		stop = findViewById(R.id.analysis_stop);
		list = findViewById(R.id.analysis_messages);
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		int foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF);
		toolbar.setNavigationIcon(MaterialSymbols.drawable(this, "arrow_back", foreground, 24));
		toolbar.setNavigationContentDescription("返回");
		toolbar.setNavigationOnClickListener(v -> finish());
		addMenu(CONFIG, R.string.log_analysis_config, "settings", MenuItem.SHOW_AS_ACTION_ALWAYS, foreground);
		addMenu(FILES, R.string.log_analysis_files, "description", MenuItem.SHOW_AS_ACTION_IF_ROOM, foreground);
		addMenu(RESTART, R.string.log_analysis_restart, "restart_alt", MenuItem.SHOW_AS_ACTION_NEVER, foreground);
		addMenu(INFO, R.string.log_analysis_info, "info", MenuItem.SHOW_AS_ACTION_ALWAYS, foreground);
		toolbar.setOnMenuItemClickListener(item -> {
			if (item.getItemId() == INFO) { showInfo(); return true; }
			if (!ready) return true;
			if (item.getItemId() == CONFIG) showConfig();
			else if (item.getItemId() == FILES) showFiles();
			else if (item.getItemId() == RESTART) resetConversation();
			return true;
		});
		MaterialSymbols.applyButtonIcon(send, "arrow_forward", send.getIconTint(), 24);
		MaterialSymbols.applyButtonIcon(stop, "close", stop.getIconTint(), 24);
		send.setOnClickListener(v -> requestSend());
		stop.setOnClickListener(v -> stopRequest());
		status.setText(R.string.log_analysis_loading);
		updateControls();
		String path = getIntent().getStringExtra(EXTRA_CURRENT);
		final File current = path == null ? null : new File(path);
		worker.execute(() -> {
			List<File> discovered = new ArrayList<>();
			if (current != null) addUnique(discovered, current);
			final File defaultFile = discovered.isEmpty() ? null : discovered.get(0);
			String failure = null;
			try {
				for (File file : RuntimeLogFiles.latest(getApplicationContext())) addUnique(discovered, file);
			} catch (Exception e) { failure = "无法发现关联日志；仍可分析当前文件"; }
			LlmConfig loaded;
			try { loaded = LlmSettings.read(getApplicationContext()); }
			catch (IOException e) { loaded = new LlmConfig("", "", "", ""); failure = getString(R.string.log_analysis_config_failed); }
			final LlmConfig result = loaded;
			final String notice = failure;
			post(0, () -> {
				config = result;
				candidates.addAll(discovered);
				currentFile = defaultFile;
				if (defaultFile != null) selected.add(defaultFile);
				ready = true;
				updateFiles(); updateControls();
				status.setText(R.string.log_analysis_ready);
				if (notice != null) showNotice(notice);
			});
		});
	}

	private void addMenu(int id, int text, String symbol, int mode, int color) {
		MenuItem item = toolbar.getMenu().add(0, id, id, text);
		item.setShowAsAction(mode);
		MaterialSymbols.applyMenuIcon(this, item, symbol, color, 24);
	}

	private void showInfo() {
		new MaterialAlertDialogBuilder(this).setTitle(R.string.log_analysis_info)
			.setMessage(getString(R.string.log_analysis_privacy) + "\n\n" + getString(R.string.log_analysis_welcome)
				+ "\n\n" + getString(R.string.log_analysis_select_notice))
			.setPositiveButton(R.string.log_analysis_close, null).show();
	}

	private void showNotice(String message) {
		new MaterialAlertDialogBuilder(this).setTitle(R.string.log_analysis_notice).setMessage(message)
			.setPositiveButton(R.string.log_analysis_close, null).show();
	}

	private static void addUnique(List<File> files, File file) {
		try {
			File canonical = file.getCanonicalFile();
			for (File existing : files) if (existing.equals(canonical)) return;
			files.add(canonical);
		} catch (IOException ignored) { /* An unreadable candidate is not eligible for authorization. */ }
	}

	private void post(int expected, Runnable runnable) {
		runOnUiThread(() -> { if (!closed && expected == generation) runnable.run(); });
	}

	private void updateControls() {
		send.setEnabled(ready && !busy);
		stop.setEnabled(busy);
		question.setEnabled(ready && !busy);
	}

	private String selectedNames() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < selected.size(); i++) {
			if (i > 0) text.append('\n');
			text.append("log_").append(i + 1).append(" · ").append(selected.get(i).getName());
		}
		return text.toString();
	}

	private void updateFiles() {
		fileText.setText(selected.isEmpty() ? getString(R.string.log_analysis_no_files) : selectedNames());
	}

	private void stopRequest() {
		if (cancellation != null) cancellation.cancel();
		generation++;
		busy = false;
		updateControls();
		status.setText(R.string.log_analysis_stopped);
	}

	private void resetConversation() {
		stopRequest();
		conversation = new Conversation();
		messages.clear(); adapter.notifyDataSetChanged();
		status.setText(R.string.log_analysis_ready);
	}

	private void requestSend() {
		String text = question.getText() == null ? "" : question.getText().toString().trim();
		if (text.isEmpty() || busy || !ready) return;
		if (selected.isEmpty()) { status.setText(R.string.log_analysis_no_files); return; }
		try { config.validate(); }
		catch (IllegalArgumentException e) { showConfig(); return; }
		if (conversation.consent) { sendQuestion(text); return; }
		final Conversation expected = conversation;
		final int expectedGeneration = generation;
		new MaterialAlertDialogBuilder(this).setTitle(R.string.log_analysis_consent_title)
			.setMessage(getString(R.string.log_analysis_consent, config.endpoint().toString(), selectedNames()))
			.setNegativeButton(R.string.log_analysis_cancel, null)
			.setPositiveButton(R.string.log_analysis_agree, (dialog, which) -> {
				if (expected == conversation && generation == expectedGeneration && !closed) {
					expected.consent = true;
					sendQuestion(text);
				}
			}).show();
	}

	private void sendQuestion(String text) {
		final int expected = ++generation;
		final Cancellation token = new Cancellation();
		cancellation = token;
		final Conversation turn = conversation;
		final LlmConfig connection = config;
		final List<File> allowed = new ArrayList<>(selected);
		final List<String> toolFailures = new ArrayList<>();
		busy = true; updateControls();
		question.setText("");
		addMessage(R.string.log_analysis_user, text);
		status.setText(R.string.log_analysis_working);
		worker.execute(() -> {
			try {
				token.check();
				if (turn.session == null) {
					LogTools tools = new LogTools(allowed, token);
					turn.session = new ChatSession(new OpenAiClient(connection), tools, tools.systemPrompt());
				}
				ChatSession.Result result = turn.session.ask(text, token, summary -> post(expected, () -> {
					if (summary.startsWith("工具失败")) {
						toolFailures.add(summary);
						status.setText(R.string.log_analysis_tool_failed);
					} else status.setText(summary);
				}));
				token.check();
				post(expected, () -> {
					busy = false; updateControls();
					if (!result.budgetReached) addMessage(R.string.log_analysis_assistant, result.text);
					status.setText(result.budgetReached ? R.string.log_analysis_budget : R.string.log_analysis_ready);
					String failures = android.text.TextUtils.join("\n", toolFailures);
					if (result.budgetReached) showNotice(result.text + (failures.isEmpty() ? "" : "\n\n" + failures));
					else if (!failures.isEmpty()) showNotice(failures);
				});
			} catch (Exception e) {
				String failure = e instanceof IOException ? e.getMessage() : getString(R.string.log_analysis_generic_error);
				if (e instanceof InterruptedIOException) failure = getString(R.string.log_analysis_stopped);
				final String visible = failure == null ? getString(R.string.log_analysis_generic_error) : failure;
				post(expected, () -> { busy = false; updateControls(); status.setText(R.string.log_analysis_failed); showNotice(visible); });
			}
		});
	}

	private void showFiles() {
		boolean[] checked = new boolean[candidates.size()];
		String[] names = new String[candidates.size()];
		for (int i = 0; i < candidates.size(); i++) {
			checked[i] = selected.contains(candidates.get(i));
			names[i] = candidates.get(i).getName() + (candidates.get(i).equals(currentFile) ? "（当前）" : "（最新关联）");
		}
		AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.log_analysis_files)
			.setMultiChoiceItems(names, checked, (d, index, selectedValue) -> checked[index] = selectedValue)
			.setNegativeButton(R.string.log_analysis_cancel, null)
			.setPositiveButton(R.string.log_analysis_confirm, null).create();
		dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			List<File> allowed = new ArrayList<>();
			for (int i = 0; i < checked.length; i++) if (checked[i]) allowed.add(candidates.get(i));
			if (allowed.isEmpty()) { status.setText(R.string.log_analysis_no_files); return; }
			resetConversation(); selected.clear(); selected.addAll(allowed); updateFiles(); dialog.dismiss();
		}));
		dialog.show();
	}

	private void showConfig() {
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
		ScrollView scroll = new ScrollView(this); scroll.addView(form);
		AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.log_analysis_config).setView(scroll)
			.setNegativeButton(R.string.log_analysis_cancel, null).setPositiveButton(R.string.log_analysis_save, null).create();
		dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			LlmConfig replacement = new LlmConfig(value(url), value(key), value(model), value(effort));
			try { replacement.validate(); }
			catch (IllegalArgumentException e) { error.setText(e.getMessage()); return; }
			stopRequest();
			ready = false;
			updateControls();
			final int expected = generation;
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
			error.setText("正在加密保存…");
			worker.execute(() -> {
				try {
					LlmSettings.save(getApplicationContext(), replacement);
					post(expected, () -> {
						config = replacement;
						ready = true;
						resetConversation();
						dialog.dismiss();
					});
				} catch (IOException e) {
					post(expected, () -> { ready = true; updateControls(); error.setText(e.getMessage()); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); });
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

	private void addMessage(int title, String text) {
		messages.add(new Message(getString(title), text));
		adapter.notifyItemInserted(messages.size() - 1);
		list.scrollToPosition(messages.size() - 1);
	}

	@Override protected void onDestroy() {
		closed = true;
		generation++;
		if (cancellation != null) cancellation.cancel();
		worker.shutdownNow();
		super.onDestroy();
	}

	private final class MessageAdapter extends RecyclerView.Adapter<MessageHolder> {
		@Override public MessageHolder onCreateViewHolder(ViewGroup parent, int type) {
			TextView text = new TextView(parent.getContext());
			text.setTextSize(18); text.setTextIsSelectable(true);
			text.setTextColor(MaterialColors.getColor(text, com.google.android.material.R.attr.colorOnSurface));
			int padding = ExtraSettingsUi.dp(parent.getContext(), 16);
			text.setPadding(padding, padding, padding, padding);
			text.setBackgroundColor(MaterialColors.getColor(text, com.google.android.material.R.attr.colorSurfaceContainer));
			RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			params.bottomMargin = ExtraSettingsUi.dp(parent.getContext(), 12);
			text.setLayoutParams(params);
			return new MessageHolder(text);
		}
		@Override public void onBindViewHolder(MessageHolder holder, int position) {
			Message message = messages.get(position);
			holder.text.setText(message.title + "\n\n" + message.text);
			holder.text.animate().cancel();
			holder.text.setAlpha(0.4f); holder.text.setTranslationY(ExtraSettingsUi.dp(LogAnalysisActivity.this, 12));
			holder.text.animate().alpha(1).translationY(0).setDuration(240).setInterpolator(new PathInterpolator(0.2f, 0, 0, 1)).start();
		}
		@Override public int getItemCount() { return messages.size(); }
	}
	private static final class MessageHolder extends RecyclerView.ViewHolder {
		final TextView text;
		MessageHolder(TextView text) { super(text); this.text = text; }
	}
}
