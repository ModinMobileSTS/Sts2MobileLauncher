package com.godot.game.steam.cloud;

import android.content.Context;

import com.godot.game.steam.core.SteamSettings;
import com.godot.game.webdav.WebDavSavePathMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SteamCloudPathMapperTest {
    private Context context;
    private Sts2SteamCloudPathMapper mapper;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        SteamSettings.setSyncSettingsSave(context, false);
        mapper = new Sts2SteamCloudPathMapper(context);
    }

    @Test public void sdkPathsKeepVanillaAndModdedSavesSeparate() {
        for (String path : List.of("profile.save", "modded/profile.save", "profile1/saves/progress.save",
                "modded/profile2/saves/current_run_mp.save",
                "profile3/saves/history/20261007.run")) {
            assertEquals(path, mapper.toLocalRelativePath(mapper.toRemotePath(path)));
            // STS2 uses RemoteStorage filenames, not an Auto Cloud root for new files.
            assertEquals(path, mapper.toRemotePath(path));
        }
        File root = context.getFilesDir();
        assertEquals(new File(root, "modded/profile2/saves/current_run_mp.save"),
            mapper.resolveLocalFile(root, mapper.toLocalRelativePath("modded\\profile2\\saves\\current_run_mp.save")));
    }

    @Test public void autoCloudPrefixIsCaseInsensitiveAndAcceptsBothSeparators() {
        for (String prefix : List.of("%GameInstall%/", "%gameinstall%\\", "%GAMEINSTALL%//")) {
            assertEquals("profile1/saves/progress.save",
                mapper.toLocalRelativePath(prefix + "profile1\\saves\\progress.save"));
            assertEquals("modded/profile3/saves/history/20261007.run",
                mapper.toLocalRelativePath(prefix + "modded/profile3/saves/history/20261007.run"));
        }
    }

    @Test public void unknownRootsMalformedPrefixesAndTraversalAreRejected() {
        for (String path : List.of("%GameInstall%profile1/saves/progress.save",
                "%GameInstall%other/profile1/saves/progress.save",
                "%AppData%/profile1/saves/progress.save",
                "%GameInstall%/../profile1/saves/progress.save",
                "modded/profile1/saves/history/../../progress.save",
                "profile4/saves/progress.save", "default/1/profile1/saves/progress.save")) {
            assertEquals(path, "", mapper.toLocalRelativePath(path));
        }
    }

    @Test public void webDavKeepsModdedProfileSelectionInItsOwnNamespace() {
        WebDavSavePathMapper webDav = new WebDavSavePathMapper(false);
        String local = webDav.toLocalRelativePath("modded\\profile.save");
        assertEquals("modded/profile.save", local);
        assertEquals("modded/profile.save", webDav.toRemoteRelativePath(local));
        assertEquals(new File(context.getFilesDir(), "modded/profile.save"),
            webDav.resolveLocalFile(context.getFilesDir(), local));
        assertEquals("", webDav.toLocalRelativePath("modded/../profile.save"));
    }

    @Test public void settingsRemainOptInAcrossBothRemoteNamespaces() {
        assertEquals("", mapper.toLocalRelativePath("settings.save"));
        assertEquals("", mapper.toLocalRelativePath("%GameInstall%/settings.save"));
        assertEquals("", mapper.toRemotePath("settings.save"));
        SteamSettings.setSyncSettingsSave(context, true);
        Sts2SteamCloudPathMapper enabled = new Sts2SteamCloudPathMapper(context);
        assertEquals("settings.save", enabled.toLocalRelativePath("%GameInstall%\\settings.save"));
        assertEquals("settings.save", enabled.toRemotePath("settings.save"));
    }
}
