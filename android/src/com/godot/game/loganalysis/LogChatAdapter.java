package com.godot.game.loganalysis;

import android.content.Context;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.godot.game.R;
import io.noties.markwon.Markwon;
import io.noties.markwon.ext.tables.TablePlugin;
import java.util.ArrayList;
import java.util.List;

/** A question and its evolving answer share one row, including recoverable failures. */
final class LogChatAdapter extends RecyclerView.Adapter<LogChatAdapter.Holder> {
	private final List<Turn> turns = new ArrayList<>();
	private final Markwon markdown;
	private final Runnable onRetry;

	LogChatAdapter(Context context, Runnable onRetry) {
		markdown = Markwon.builder(context).usePlugin(TablePlugin.create(context)).build();
		this.onRetry = onRetry;
	}

	private static final class Turn {
		final String question;
		Spanned answer;
		String status;
		boolean working;
		Turn(String question) { this.question = question; }
	}

	void start(String question, String status, boolean retry) {
		int previous = turns.size() - 1;
		Turn turn = retry ? turns.get(previous) : new Turn(question);
		turn.status = status;
		turn.working = true;
		turn.answer = null;
		if (!retry) {
			turns.add(turn);
			if (previous >= 0 && turns.get(previous).status != null) notifyItemChanged(previous);
			notifyItemInserted(turns.size() - 1);
		} else notifyItemChanged(previous);
	}

	String lastQuestion() { return turns.get(turns.size() - 1).question; }

	/** A null source changes only progress; an empty source clears an intermediate tool round. */
	void update(String source, String status, boolean working) {
		int last = turns.size() - 1;
		Turn turn = turns.get(last);
		if (source != null) turn.answer = source.isEmpty() ? null : markdown.toMarkdown(source);
		turn.status = status;
		turn.working = working;
		notifyItemChanged(last);
	}

	void clear() {
		int count = turns.size();
		turns.clear();
		notifyItemRangeRemoved(0, count);
	}

	@Override public int getItemCount() { return turns.size(); }

	@Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
		return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log_analysis_turn, parent, false));
	}

	@Override public void onBindViewHolder(Holder holder, int position) {
		Turn turn = turns.get(position);
		if (!turn.question.contentEquals(holder.question.getText())) holder.question.setText(turn.question);
		holder.answer.setVisibility(turn.answer == null ? View.GONE : View.VISIBLE);
		if (holder.rendered != turn.answer) {
			if (turn.answer != null) markdown.setParsedMarkdown(holder.answer, turn.answer);
			else holder.answer.setText(null);
			holder.rendered = turn.answer;
		}
		holder.footer.setVisibility(turn.status == null ? View.GONE : View.VISIBLE);
		holder.progress.setVisibility(turn.working ? View.VISIBLE : View.GONE);
		if (turn.status != null && !turn.status.contentEquals(holder.status.getText())) holder.status.setText(turn.status);
		holder.retry.setVisibility(!turn.working && turn.status != null && position == turns.size() - 1 ? View.VISIBLE : View.GONE);
	}

	final class Holder extends RecyclerView.ViewHolder {
		final TextView question, answer, status;
		final View footer, progress, retry;
		Spanned rendered;
		Holder(View row) {
			super(row);
			question = row.findViewById(R.id.analysis_user_text);
			answer = row.findViewById(R.id.analysis_answer);
			status = row.findViewById(R.id.analysis_status);
			footer = row.findViewById(R.id.analysis_progress_row);
			progress = row.findViewById(R.id.analysis_progress);
			retry = row.findViewById(R.id.analysis_retry);
			retry.setOnClickListener(v -> onRetry.run());
		}
	}
}
