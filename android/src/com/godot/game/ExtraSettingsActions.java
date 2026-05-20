package com.godot.game;

public interface ExtraSettingsActions {
	void launchGame();
	void requestExportSave();
	void requestImportSave();
	void requestImportGamePayload();
	void requestExtractBundledPayload();
	void requestClearGamePayload();
	void requestExportFullDataBackup();
	void requestImportFullDataBackup();
	void requestImportMod();
	void openLogViewer();
	void openFileBrowser();
	void openUrl(String url);
	void requestUpdateCheck();
	void openSettingsTab();
	void refreshCurrentScreen();
	void showMessage(String message);
	void showError(Exception exception);
	void runAsyncOperation(String busyMessage, ExtraSettingsRepository.ThrowingSupplier<String> operation);
}
