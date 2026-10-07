package com.godot.game.llm;

import java.io.InterruptedIOException;
import okhttp3.Call;

/** One token per question, shared by transport and incremental local IO. */
public final class Cancellation {
	private volatile boolean cancelled;
	private Call call;

	public synchronized void cancel() {
		cancelled = true;
		if (call != null) call.cancel();
	}

	public void check() throws InterruptedIOException {
		if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已停止");
	}

	public synchronized void attach(Call next) throws InterruptedIOException {
		check();
		call = next;
	}

	public synchronized void detach(Call previous) {
		if (call == previous) call = null;
	}
}
