package com.godot.game;

import android.system.StructStat;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLinux;
import java.io.FileDescriptor;
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

	@Implementation @Override protected StructStat fstat(FileDescriptor descriptor) {
		return new StructStat(1, inode, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}
}
