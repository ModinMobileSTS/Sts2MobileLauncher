package com.godot.game;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

final class FileBrowserSupport {
	static final long MAX_EDITABLE_TEXT_BYTES = 4L * 1024L * 1024L;
	private static final int TEXT_SAMPLE_BYTES = 2048;

	private FileBrowserSupport() {
	}

	static void ensureDirectory(File directory) {
		if (directory == null) {
			return;
		}
		if (directory.isDirectory()) {
			return;
		}
		if (!directory.mkdirs() && !directory.isDirectory()) {
			throw new IllegalStateException("Unable to create directory: " + directory.getAbsolutePath());
		}
	}

	static void copyStream(InputStream inputStream, OutputStream outputStream) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = inputStream.read(buffer)) != -1) {
			outputStream.write(buffer, 0, read);
		}
		outputStream.flush();
	}

	static String readTextFile(File file) throws IOException {
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file));
				 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			copyStream(inputStream, outputStream);
			return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	static void writeTextFile(File file, String content) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) {
			ensureDirectory(parent);
		}
		try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(file))) {
			outputStream.write(content.getBytes(StandardCharsets.UTF_8));
			outputStream.flush();
		}
	}

	static boolean isProbablyText(File file) throws IOException {
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
			byte[] sample = new byte[TEXT_SAMPLE_BYTES];
			int read = inputStream.read(sample);
			if (read <= 0) {
				return true;
			}
			for (int i = 0; i < read; i++) {
				if (sample[i] == 0) {
					return false;
				}
			}
			return true;
		}
	}

	static void copyFile(File source, File destination) throws IOException {
		File parent = destination.getParentFile();
		if (parent != null) {
			ensureDirectory(parent);
		}
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(source));
				 OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(destination))) {
			copyStream(inputStream, outputStream);
		}
	}

	static void copyEntryRecursively(File source, File destination) throws IOException {
		if (source.isDirectory()) {
			ensureDirectory(destination);
			File[] children = source.listFiles();
			if (children == null) {
				return;
			}
			for (File child : children) {
				copyEntryRecursively(child, new File(destination, child.getName()));
			}
			return;
		}
		copyFile(source, destination);
	}

	static void deleteRecursively(File file) {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		if (!file.delete() && file.exists()) {
			throw new IllegalStateException("Unable to delete: " + file.getAbsolutePath());
		}
	}

	static File buildUniqueChild(File parent, String desiredName) {
		String sanitizedName = sanitizeFileName(desiredName);
		File candidate = new File(parent, sanitizedName);
		if (!candidate.exists()) {
			return candidate;
		}
		String extension = getExtension(sanitizedName);
		String baseName = removeExtension(sanitizedName);
		for (int suffix = 2; ; suffix++) {
			candidate = new File(parent, baseName + " (" + suffix + ")" + extension);
			if (!candidate.exists()) {
				return candidate;
			}
		}
	}

	static String sanitizeFileName(String input) {
		String sanitized = input == null ? "" : input.trim();
		sanitized = sanitized.replace('\\', '_').replace('/', '_').replace(':', '_').replace('*', '_').replace('?', '_').replace('"', '_').replace('<', '_').replace('>', '_').replace('|', '_');
		if (sanitized.isEmpty() || ".".equals(sanitized) || "..".equals(sanitized)) {
			return "unnamed";
		}
		return sanitized;
	}

	static String buildRelativePath(File root, File file) {
		String rootPath = getCanonicalOrAbsolutePath(root);
		String filePath = getCanonicalOrAbsolutePath(file);
		if (filePath.equals(rootPath)) {
			return "";
		}
		if (filePath.startsWith(rootPath + File.separator)) {
			return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
		}
		return file.getName();
	}

	static boolean isSameOrDescendant(File candidate, File parent) {
		String candidatePath = getCanonicalOrAbsolutePath(candidate);
		String parentPath = getCanonicalOrAbsolutePath(parent);
		return candidatePath.equals(parentPath) || candidatePath.startsWith(parentPath + File.separator);
	}

	static String resolveMimeType(File file, boolean probablyText) {
		String extension = getExtension(file.getName());
		if (!extension.isEmpty()) {
			String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.substring(1).toLowerCase(Locale.ROOT));
			if (mimeType != null && !mimeType.trim().isEmpty()) {
				return mimeType;
			}
		}
		return probablyText ? "text/plain" : "application/octet-stream";
	}

	static void openFileInExternalApp(Activity activity, File file, boolean preferEdit, CharSequence chooserTitle) throws Exception {
		if (file == null || !file.isFile()) {
			throw new IOException("Missing file: " + (file == null ? "null" : file.getAbsolutePath()));
		}
		boolean probablyText = isProbablyText(file);
		Uri uri = FileProvider.getUriForFile(activity, BuildConfig.APPLICATION_ID + ".fileprovider", file);
		String resolvedMimeType = resolveMimeType(file, probablyText);
		Intent baseIntent = buildIntent(file, uri, preferEdit ? Intent.ACTION_EDIT : Intent.ACTION_VIEW, resolvedMimeType, Intent.FLAG_GRANT_READ_URI_PERMISSION | (preferEdit ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
		if (!canHandleIntent(activity, baseIntent) && preferEdit) {
			baseIntent = buildIntent(file, uri, Intent.ACTION_VIEW, resolvedMimeType, Intent.FLAG_GRANT_READ_URI_PERMISSION);
		}
		if (!canHandleIntent(activity, baseIntent)) {
			baseIntent = buildIntent(file, uri, Intent.ACTION_VIEW, "*/*", Intent.FLAG_GRANT_READ_URI_PERMISSION);
		}
		if (!canHandleIntent(activity, baseIntent)) {
			throw new IllegalStateException(activity.getString(R.string.file_browser_no_app_found));
		}
		grantUriPermissions(activity, baseIntent, uri, baseIntent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
		activity.startActivity(Intent.createChooser(baseIntent, chooserTitle));
	}

	private static Intent buildIntent(File file, Uri uri, String action, String mimeType, int permissionFlags) {
		Intent intent = new Intent(action);
		intent.setDataAndType(uri, mimeType);
		intent.setClipData(ClipData.newRawUri(file.getName(), uri));
		intent.addFlags(permissionFlags);
		return intent;
	}

	private static boolean canHandleIntent(Context context, Intent intent) {
		PackageManager packageManager = context.getPackageManager();
		return intent.resolveActivity(packageManager) != null;
	}

	private static void grantUriPermissions(Context context, Intent intent, Uri uri, int permissionFlags) {
		PackageManager packageManager = context.getPackageManager();
		List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
		for (ResolveInfo resolveInfo : resolveInfos) {
			if (resolveInfo.activityInfo != null) {
				context.grantUriPermission(resolveInfo.activityInfo.packageName, uri, permissionFlags);
			}
		}
	}

	private static String removeExtension(String fileName) {
		int extensionIndex = fileName.lastIndexOf('.');
		if (extensionIndex <= 0) {
			return fileName;
		}
		return fileName.substring(0, extensionIndex);
	}

	private static String getExtension(String fileName) {
		int extensionIndex = fileName.lastIndexOf('.');
		if (extensionIndex <= 0 || extensionIndex >= fileName.length() - 1) {
			return "";
		}
		return fileName.substring(extensionIndex);
	}

	private static String getCanonicalOrAbsolutePath(File file) {
		try {
			return file.getCanonicalPath();
		} catch (IOException ignored) {
			return file.getAbsolutePath();
		}
	}
}
