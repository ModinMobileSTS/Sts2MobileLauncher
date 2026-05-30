package com.godot.game;

public interface ExtraSettingsActions {
	void launchGame();
	void requestExportSave();
	void requestImportSave();
	void requestImportGamePayload();
	void requestExtractBundledPayload();
	void requestClearGamePayload();
	void requestImportCompatPack();
	void requestInstallBundledCompatPacks();
	void requestSelectCompatPack(String packId);
	void requestDeleteCompatPack(String packId);
	void requestClearTextureCache();
	void requestArchiveActiveGameVersion();
	void requestSelectGameVersion(String versionId);
	void requestDeleteGameVersion(String versionId);
	void requestExportFullDataBackup();
	void requestImportFullDataBackup();
	void requestImportMod();
	void openModStore();
	void openLogViewer();
	void openFileBrowser();
	void openUrl(String url);
	void requestUpdateCheck();
	void openSettingsTab();
	void openVersionsTab();
	void refreshCurrentScreen();
	void showMessage(String message);
	void showError(Exception exception);
	void runAsyncOperation(String busyMessage, ExtraSettingsRepository.ThrowingSupplier<String> operation);
}
