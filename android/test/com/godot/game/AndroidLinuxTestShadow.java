package com.godot.game;

import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLinux;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

// Libcore caches its Linux object across tests. Use one shadow type so environment
// and inode tests cannot retain an instance of another test's incompatible shadow.
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
public class AndroidLinuxTestShadow extends ShadowLinux {
	static final Map<String, String> environment = new HashMap<>();
	static volatile long inode;

	@Implementation protected String getenv(String name) {
		return environment.containsKey(name) ? environment.get(name) : System.getenv(name);
	}

	@Implementation protected void setenv(String name, String value, boolean overwrite) {
		if (overwrite || getenv(name) == null) {
			environment.put(name, value);
		}
	}

	// Robolectric's stock lstat delegates to stat and follows real host symlinks.
	@Implementation @Override protected StructStat lstat(String path) throws ErrnoException {
		try {
			BasicFileAttributes a = Files.readAttributes(Paths.get(path), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			int mode = a.isDirectory() ? OsConstants.S_IFDIR : a.isRegularFile() ? OsConstants.S_IFREG
				: a.isSymbolicLink() ? OsConstants.S_IFLNK : OsConstants.S_IFIFO;
			return new StructStat(1, 0, mode, 1, 0, 0, 0, a.size(), 0, a.lastModifiedTime().toMillis() / 1000, 0, 0, 0);
		} catch (NoSuchFileException e) {
			throw new ErrnoException("lstat", OsConstants.ENOENT, e);
		} catch (IOException e) {
			throw new ErrnoException("lstat", OsConstants.EIO, e);
		}
	}

	@Implementation @Override protected StructStat fstat(FileDescriptor descriptor) {
		return new StructStat(1, inode, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}
}
