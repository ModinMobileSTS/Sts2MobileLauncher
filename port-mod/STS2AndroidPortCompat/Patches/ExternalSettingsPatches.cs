using System;
using System.IO;
using System.Reflection;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Achievements;
using MegaCrit.Sts2.Core.Models;
using MegaCrit.Sts2.Core.Models.Characters;
using MegaCrit.Sts2.Core.Nodes;
using MegaCrit.Sts2.Core.Saves;
using MegaCrit.Sts2.Core.Timeline;
using MegaCrit.Sts2.Core.Timeline.Epochs;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class ExternalSettingsPatches
{
    private static bool _didCheckPendingCommands;

    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(NGame), "GameStartup", postfix: PatchHelper.Method(typeof(ExternalSettingsPatches), nameof(GameStartupPostfix)));
        PatchHelper.Patch(harmony, typeof(NGame), "Quit", prefix: PatchHelper.Method(typeof(ExternalSettingsPatches), nameof(QuitPrefix)));

        var settingsScreenType = typeof(NGame).Assembly.GetType("MegaCrit.Sts2.Core.Nodes.Screens.Settings.NSettingsScreen");
        if (settingsScreenType != null)
        {
            PatchHelper.Patch(harmony, settingsScreenType, "_Ready", postfix: PatchHelper.Method(typeof(ExternalSettingsPatches), nameof(SettingsScreenReadyPostfix)));
            PatchHelper.Patch(harmony, settingsScreenType, "LocalizeLabels", postfix: PatchHelper.Method(typeof(ExternalSettingsPatches), nameof(SettingsScreenLocalizeLabelsPostfix)));
        }
    }

    public static void GameStartupPostfix()
    {
        if (_didCheckPendingCommands)
            return;
        _didCheckPendingCommands = true;
        ApplyPendingUnlockAllIfRequested();
    }

    public static bool QuitPrefix(NGame __instance)
    {
        try
        {
            SaveManager.Instance.SaveSettings();
            SaveManager.Instance.SavePrefsFile();
            SaveManager.Instance.SaveProgressFile();
            SaveManager.Instance.SaveProfile();
            if (CallStaticGodotAppBool("restartToSettingsFromGame"))
            {
                PatchHelper.Log("NGame.Quit redirected to Java settings-shell restart.");
                return false;
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"QuitPrefix failed; falling back to original Quit: {exception}");
        }
        return true;
    }

    public static void SettingsScreenReadyPostfix(Node __instance)
    {
        try
        {
            var content = GetSettingsContent(__instance);
            if (content == null)
                return;
            if (content.GetNodeOrNull("AndroidExternalSettings") != null)
                return;

            var line = new HBoxContainer { Name = "AndroidExternalSettings" };
            line.SizeFlagsHorizontal = Control.SizeFlags.ExpandFill;
            line.AddThemeConstantOverride("separation", 16);

            var label = new Label { Name = "Label", Text = "Android companion settings" };
            label.SizeFlagsHorizontal = Control.SizeFlags.ExpandFill;
            label.VerticalAlignment = VerticalAlignment.Center;

            var button = new Button { Name = "Button", Text = "OPEN" };
            button.Pressed += () =>
            {
                if (!OpenCompanionSettings())
                    PatchHelper.Log("Java settings-shell bridge returned false.");
            };

            line.AddChild(label);
            line.AddChild(button);
            content.AddChild(line);
            PatchHelper.Log("Added fallback Android companion settings row.");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"SettingsScreenReadyPostfix failed: {exception.Message}");
        }
    }

    public static void SettingsScreenLocalizeLabelsPostfix(Node __instance)
    {
        try
        {
            var content = GetSettingsContent(__instance);
            var label = content?.GetNodeOrNull<Label>("AndroidExternalSettings/Label");
            if (label != null)
                label.Text = "Android companion settings";
            var button = content?.GetNodeOrNull<Button>("AndroidExternalSettings/Button");
            if (button != null)
                button.Text = "OPEN";
        }
        catch
        {
        }
    }

    public static bool OpenCompanionSettings() => CallStaticGodotAppBool("launchGameSettingsFromGame");

    private static Control GetSettingsContent(Node settingsScreen)
    {
        var generalSettings = settingsScreen.GetNodeOrNull<Node>("%GeneralSettings");
        if (generalSettings == null)
            return null;
        try
        {
            var property = generalSettings.GetType().GetProperty("Content", BindingFlags.Public | BindingFlags.Instance);
            if (property?.GetValue(generalSettings) is Control control)
                return control;
        }
        catch
        {
        }
        return generalSettings.GetNodeOrNull<Control>("Content");
    }

    private static bool CallStaticGodotAppBool(string methodName)
    {
        try
        {
            var javaClassWrapper = Engine.GetSingleton("JavaClassWrapper");
            var wrapper = (GodotObject)javaClassWrapper.Call("wrap", "com.godot.game.GodotApp");
            var result = wrapper.Call(methodName);
            return result.VariantType switch
            {
                Variant.Type.Bool => result.AsBool(),
                Variant.Type.Int => result.AsInt32() != 0,
                _ => false,
            };
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"GodotApp.{methodName} bridge failed: {exception.Message}");
            return false;
        }
    }

    private static void ApplyPendingUnlockAllIfRequested()
    {
        try
        {
            var flagPath = AppPaths.PendingUnlockAllPath;
            if (!File.Exists(flagPath))
                return;
            PatchHelper.Log("Applying pending unlock-all request from Android companion settings.");
            ApplyUnlockAll();
            File.Delete(flagPath);
            SaveManager.Instance.DeleteCurrentRun();
            SaveManager.Instance.DeleteCurrentMultiplayerRun();
            SaveManager.Instance.SaveProgressFile();
            SaveManager.Instance.SaveProfile();
            PatchHelper.Log("Pending unlock-all request applied.");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Pending unlock-all failed: {exception}");
        }
    }

    private static void ApplyUnlockAll()
    {
        var progress = SaveManager.Instance.Progress;
        progress.EnableFtues = false;
        progress.TotalUnlocks = 18;
        progress.CurrentScore = 0;
        progress.PendingCharacterUnlock = ModelId.none;
        progress.MaxMultiplayerAscension = 10;
        progress.PreferredMultiplayerAscension = 10;

        foreach (var character in ModelDb.AllCharacters)
        {
            var stats = progress.GetOrCreateCharacterStats(character.Id);
            stats.MaxAscension = 10;
            stats.PreferredAscension = 10;
        }
        foreach (var card in ModelDb.AllCards)
            progress.MarkCardAsSeen(card.Id);
        foreach (var relic in ModelDb.AllRelics)
            progress.MarkRelicAsSeen(relic.Id);
        foreach (var potion in ModelDb.AllPotions)
            progress.MarkPotionAsSeen(potion.Id);
        foreach (var gameEvent in ModelDb.AllEvents)
            progress.MarkEventAsSeen(gameEvent.Id);
        foreach (var act in ModelDb.Acts)
            progress.MarkActAsSeen(act.Id);

        var ironcladId = ModelDb.GetId<Ironclad>();
        foreach (var monster in ModelDb.Monsters)
        {
            var stats = progress.GetOrCreateEnemyStats(monster.Id);
            if (stats.FightStats.Count == 0)
            {
                stats.FightStats.Add(new FightStats { Character = ironcladId, Wins = 1 });
            }
        }

        progress.ResetEpochs();
        foreach (var epochId in EpochModel.AllEpochIds)
            SaveManager.Instance.ObtainEpochOverride(epochId, EpochState.Revealed);

        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        foreach (Achievement achievement in Enum.GetValues<Achievement>())
            progress.AddUnlockedAchievement(achievement, timestamp);
    }
}
