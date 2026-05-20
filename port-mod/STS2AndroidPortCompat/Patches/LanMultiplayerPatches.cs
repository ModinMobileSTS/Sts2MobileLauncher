using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Threading.Tasks;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.addons.mega_text;
using MegaCrit.Sts2.Core.Debug;
using MegaCrit.Sts2.Core.Entities.Multiplayer;
using MegaCrit.Sts2.Core.Helpers;
using MegaCrit.Sts2.Core.Modding;
using MegaCrit.Sts2.Core.Multiplayer;
using MegaCrit.Sts2.Core.Multiplayer.Connection;
using MegaCrit.Sts2.Core.Multiplayer.Game;
using MegaCrit.Sts2.Core.Multiplayer.Messages.Lobby;
using MegaCrit.Sts2.Core.Multiplayer.Serialization;
using MegaCrit.Sts2.Core.Nodes.CommonUi;
using MegaCrit.Sts2.Core.Nodes.Screens.MainMenu;
using MegaCrit.Sts2.Core.Platform;
using MegaCrit.Sts2.Core.Platform.Null;
using MegaCrit.Sts2.Core.Platform.Steam;
using MegaCrit.Sts2.Core.Runs;
using MegaCrit.Sts2.Core.Saves;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class LanMultiplayerPatches
{
    public const ushort DefaultPort = 33771;
    private const int DefaultMaxPlayers = 4;
    private const int SteamMaxPlayers = 250;
    private const int MessageTypeCount = 49;

    private static readonly Dictionary<PlatformType, Dictionary<ulong, string>> PlayerNameOverrides = new();
    private static readonly Dictionary<Type, int> StableMessageTypeIds = new();
    private static readonly Dictionary<int, Type> StableMessageIdTypes = new();
    private static readonly string[] StableMessageTypeOrder =
    {
        "ActionEnqueuedMessage",
        "CardRemovedMessage",
        "ChecksumDataMessage",
        "StateDivergenceMessage",
        "ClearMapDrawingsMessage",
        "EndTurnPingMessage",
        "MapDrawingMessage",
        "MapDrawingModeChangedMessage",
        "MapPingMessage",
        "ReactionMessage",
        "RestSiteOptionHoveredMessage",
        "HookActionEnqueuedMessage",
        "MerchantCardRemovalMessage",
        "PaelsWingSacrificeMessage",
        "PlayerChoiceMessage",
        "RequestEnqueueActionMessage",
        "RequestEnqueueHookActionMessage",
        "RequestResumeActionAfterPlayerChoiceMessage",
        "ResumeActionAfterPlayerChoiceMessage",
        "RunAbandonedMessage",
        "GoldLostMessage",
        "OptionIndexChosenMessage",
        "PeerInputMessage",
        "RewardObtainedMessage",
        "SharedEventOptionChosenMessage",
        "VotedForSharedEventOptionMessage",
        "SyncPlayerDataMessage",
        "SyncRngMessage",
        "TreasureChestOpenedMessage",
        "HeartbeatRequestMessage",
        "HeartbeatResponseMessage",
        "ClientLoadJoinRequestMessage",
        "ClientLoadJoinResponseMessage",
        "ClientLobbyJoinRequestMessage",
        "ClientLobbyJoinResponseMessage",
        "ClientRejoinRequestMessage",
        "ClientRejoinResponseMessage",
        "InitialGameInfoMessage",
        "LobbyAscensionChangedMessage",
        "LobbyBeginLoadedRunMessage",
        "LobbyBeginRunMessage",
        "LobbyModifiersChangedMessage",
        "LobbyPlayerChangedCharacterMessage",
        "LobbyPlayerSetReadyMessage",
        "LobbySeedChangedMessage",
        "PlayerJoinedMessage",
        "PlayerLeftMessage",
        "PlayerReconnectedMessage",
        "PlayerRejoinedMessage",
    };

    public static void Apply(Harmony harmony)
    {
        if (!AndroidSettingsBridge.GetBool("lan_multiplayer_enabled", true))
        {
            PatchHelper.Log("LAN multiplayer compatibility is disabled by Android companion settings.");
            return;
        }

        BuildMessageTypeMaps();

        PatchHelper.Patch(harmony, typeof(InitialGameInfoMessage), "Basic", postfix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(InitialGameInfoBasicPostfix)));
        PatchHelper.Patch(harmony, typeof(ModManager), "GetGameplayRelevantModNameList", postfix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(ModNameListPostfix)));
        PatchHelper.Patch(harmony, typeof(ModManager), "GetModNameList", postfix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(ModNameListPostfix)));
        PatchHelper.Patch(harmony, typeof(JoinFlow), "Begin", prefix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(JoinFlowBeginPrefix)));
        PatchHelper.Patch(harmony, typeof(NullPlatformUtilStrategy), "GetPlayerName", prefix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(NullGetPlayerNamePrefix)));
        PatchHelper.Patch(harmony, typeof(NullPlatformUtilStrategy), "GetLocalPlayerId", prefix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(NullGetLocalPlayerIdPrefix)));
        var settingsScreenType = typeof(NJoinFriendScreen).Assembly.GetType("MegaCrit.Sts2.Core.Nodes.Screens.Settings.NSettingsScreen");
        if (settingsScreenType != null)
            PatchHelper.Patch(harmony, settingsScreenType, "_Ready", postfix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(SettingsScreenReadyPostfix)));
        PatchHelper.Patch(harmony, typeof(NetMessageBus), "SerializeMessage", prefix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(SerializeMessagePrefix)));
        PatchHelper.Patch(harmony, typeof(NetMessageBus), "TryDeserializeMessage", prefix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(TryDeserializeMessagePrefix)));
        PatchHelper.Patch(harmony, typeof(NJoinFriendScreen), "OnSubmenuOpened", prefix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(JoinFriendOpenedPrefix)));
        PatchHelper.Patch(harmony, typeof(NJoinFriendScreen), "RefreshButtonClicked", prefix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(JoinFriendRefreshButtonPrefix)));
        PatchHelper.Patch(harmony, typeof(NJoinFriendScreen), "OnSubmenuClosed", postfix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(JoinFriendClosedPostfix)));
        PatchHelper.Patch(harmony, typeof(NMultiplayerHostSubmenu), "StartHostAsync", prefix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(HostSubmenuStartHostAsyncPrefix)));
        PatchHelper.Patch(harmony, typeof(NMultiplayerSubmenu), "StartHostAsync", prefix: PatchHelper.Method(typeof(LanMultiplayerPatches), nameof(MultiplayerSubmenuStartHostAsyncPrefix)));

        PatchHelper.Log("LAN multiplayer compatibility patches applied.");
    }

    public static void InitialGameInfoBasicPostfix(ref InitialGameInfoMessage __result)
    {
        __result.mods = GetMultiplayerCompatibilityModNameList(__result.mods);
    }

    public static void ModNameListPostfix(ref List<string> __result)
    {
        __result = GetMultiplayerCompatibilityModNameList(__result);
    }

    public static void JoinFlowBeginPrefix()
    {
        ClearPlayerNameOverrides(PlatformType.None);
    }

    public static bool NullGetPlayerNamePrefix(ulong playerId, ref string __result)
    {
        if (TryGetPlayerNameOverride(PlatformType.None, playerId, out var playerName))
        {
            __result = playerName;
            return false;
        }
        __result = playerId switch
        {
            1UL => "Host",
            1000UL => "Client 1",
            2000UL => "Client 2",
            3000UL => "Client 3",
            _ => $"Player {playerId % 10000UL:0000}",
        };
        return false;
    }

    public static bool NullGetLocalPlayerIdPrefix(ref ulong __result)
    {
        if (AndroidSettingsBridge.GetBool("lan_use_custom_platform_player_id", false) && TryGetConfiguredCustomPeerId(out var playerId))
        {
            __result = playerId;
            return false;
        }
        return true;
    }

    public static void SettingsScreenReadyPostfix(Node __instance)
    {
        try
        {
            var enabled = AndroidSettingsBridge.GetBool("max_multiplayer_enabled", true);
            var generalSettings = __instance.GetNodeOrNull<Node>("%GeneralSettings");
            var content = generalSettings?.GetType().GetProperty("Content", BindingFlags.Public | BindingFlags.Instance)?.GetValue(generalSettings) as Node
                ?? generalSettings?.GetNodeOrNull<Node>("Content");
            var maxPlayersNode = content?.GetNodeOrNull<Control>("MaxMultiplayerPlayers");
            var maxPlayersDivider = content?.GetNodeOrNull<Control>("MaxMultiplayerPlayersDivider");
            if (maxPlayersNode != null)
                maxPlayersNode.Visible = enabled;
            if (maxPlayersDivider != null)
                maxPlayersDivider.Visible = enabled;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"LAN max-player settings visibility patch failed: {exception.Message}");
        }
    }

    public static bool SerializeMessagePrefix(NetMessageBus __instance, ulong senderId, INetMessage message, ref byte[] __result, ref int length)
    {
        if (!StableMessageTypeIds.TryGetValue(message.GetType(), out var typeId))
            return true;

        var writer = (PacketWriter)AccessTools.Field(typeof(NetMessageBus), "_writer")?.GetValue(__instance);
        if (writer == null)
            return true;

        writer.Reset();
        writer.WriteByte((byte)typeId);
        writer.WriteULong(senderId);
        message.Serialize(writer);
        length = (int)Math.Ceiling(writer.BitPosition / 8f);
        __result = writer.Buffer;
        return false;
    }

    public static bool TryDeserializeMessagePrefix(NetMessageBus __instance, byte[] packetBytes, ref INetMessage message, ref ulong? overrideSenderId, ref bool __result)
    {
        try
        {
            var reader = (PacketReader)AccessTools.Field(typeof(NetMessageBus), "_reader")?.GetValue(__instance);
            if (reader == null)
                return true;
            overrideSenderId = null;
            message = null;
            reader.Reset(packetBytes);
            if (packetBytes == null || packetBytes.Length == 0 || !StableMessageIdTypes.ContainsKey(packetBytes[0]))
                return true;
            var typeId = reader.ReadByte();
            if (!StableMessageIdTypes.TryGetValue(typeId, out var type))
            {
                PatchHelper.Log($"LAN message decode failed: unknown message id {typeId}");
                __result = false;
                return false;
            }
            overrideSenderId = reader.ReadULong();
            message = (INetMessage)Activator.CreateInstance(type);
            message.Deserialize(reader);
            __result = true;
            return false;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"LAN message decode failed: {exception}");
            __result = false;
            return false;
        }
    }

    public static bool JoinFriendOpenedPrefix(NJoinFriendScreen __instance)
    {
        if (SteamInitializer.Initialized || CommandLineHelper.HasArg("fastmp"))
            return true;

        try
        {
            var loadingOverlay = __instance.GetNodeOrNull<Control>("%LoadingOverlay");
            if (loadingOverlay != null)
                loadingOverlay.Visible = false;
            ConfigureLanJoinScreen(__instance);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"LAN join screen patch failed, falling back to original: {exception}");
            return true;
        }
        return false;
    }

    public static bool JoinFriendRefreshButtonPrefix(NJoinFriendScreen __instance)
    {
        if (SteamInitializer.Initialized || CommandLineHelper.HasArg("fastmp") || !TryGetLanInputs(__instance, out var hostInput, out var portInput))
            return true;
        TaskHelper.RunSafely(JoinLanGameAsync(__instance, hostInput, portInput));
        return false;
    }

    public static void JoinFriendClosedPostfix(object __instance)
    {
        try
        {
            var currentJoinFlow = AccessTools.Field(__instance.GetType(), "_currentJoinFlow")?.GetValue(__instance);
            var cancelToken = currentJoinFlow?.GetType().GetProperty("CancelToken")?.GetValue(currentJoinFlow);
            cancelToken?.GetType().GetMethod("Cancel", Type.EmptyTypes)?.Invoke(cancelToken, null);
        }
        catch
        {
        }
    }

    public static bool HostSubmenuStartHostAsyncPrefix(GameMode gameMode, Control loadingOverlay, NSubmenuStack stack, ref Task __result)
    {
        if (SteamInitializer.Initialized && !CommandLineHelper.HasArg("fastmp"))
            return true;
        __result = StartHostFromSubmenuAsync(gameMode, loadingOverlay, stack);
        return false;
    }

    public static bool MultiplayerSubmenuStartHostAsyncPrefix(object __instance, SerializableRun run, ref Task __result)
    {
        if (SteamInitializer.Initialized && !CommandLineHelper.HasArg("fastmp"))
            return true;
        __result = StartLoadedHostAsync(__instance, run);
        return false;
    }

    private static void BuildMessageTypeMaps()
    {
        if (StableMessageTypeIds.Count > 0)
            return;

        var allTypes = typeof(InitialGameInfoMessage).Assembly.GetTypes();
        for (var index = 0; index < StableMessageTypeOrder.Length; index++)
        {
            var name = StableMessageTypeOrder[index];
            var type = allTypes.FirstOrDefault(t => t.Name == name && typeof(INetMessage).IsAssignableFrom(t));
            if (type == null)
            {
                PatchHelper.Log($"Stable LAN message type not found: {name}");
                continue;
            }
            StableMessageTypeIds[type] = index;
            StableMessageIdTypes[index] = type;
        }
        PatchHelper.Log($"Stable LAN message protocol mapped {StableMessageTypeIds.Count}/{MessageTypeCount} built-in message types.");
    }

    private static List<string> GetMultiplayerCompatibilityModNameList(List<string> baseModNames)
    {
        var result = baseModNames ?? new List<string>();
        foreach (var configured in GetConfiguredCompatibilityModNames())
        {
            if (!result.Contains(configured, StringComparer.Ordinal))
                result.Add(configured);
        }
        return result.Count == 0 ? null : result;
    }

    private static IEnumerable<string> GetConfiguredCompatibilityModNames()
    {
        if (!AndroidSettingsBridge.TryGet("lan_compatibility_mod_names", out var element) || element.ValueKind != System.Text.Json.JsonValueKind.Array)
            yield break;
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var item in element.EnumerateArray())
        {
            var value = NormalizeText(item.ValueKind == System.Text.Json.JsonValueKind.String ? item.GetString() : item.ToString());
            if (!string.IsNullOrWhiteSpace(value) && seen.Add(value))
                yield return value;
        }
    }

    private static int GetConfiguredHostMaxPlayers()
    {
        if (!AndroidSettingsBridge.GetBool("max_multiplayer_enabled", true))
            return DefaultMaxPlayers;
        var configured = AndroidSettingsBridge.GetInt("max_multiplayer_players", DefaultMaxPlayers);
        return configured <= 0 ? DefaultMaxPlayers : configured;
    }

    private static int GetHostCapacityForPlatform(PlatformType platformType, int requestedMaxPlayers)
    {
        requestedMaxPlayers = requestedMaxPlayers <= 0 ? DefaultMaxPlayers : requestedMaxPlayers;
        return platformType == PlatformType.Steam ? Math.Min(requestedMaxPlayers, SteamMaxPlayers) : requestedMaxPlayers;
    }

    private static ulong GetOrCreateLocalPeerId()
    {
        if (TryGetConfiguredCustomPeerId(out var customPeerId))
            return customPeerId;

        var current = AndroidSettingsBridge.GetString("lan_player_id", string.Empty);
        if (ulong.TryParse(current, out var saved) && saved > 1)
            return saved;

        var generated = 0UL;
        while (generated <= 1)
        {
            generated = BitConverter.ToUInt64(System.Security.Cryptography.RandomNumberGenerator.GetBytes(sizeof(ulong))) & 0x7FFFFFFFFFFFFFFFUL;
        }
        SaveLanSetting("lan_player_id", generated);
        PatchHelper.Log($"Generated persistent LAN peer ID {generated}");
        return generated;
    }

    private static bool TryGetConfiguredCustomPeerId(out ulong peerId)
    {
        peerId = 0;
        if (!AndroidSettingsBridge.GetBool("lan_use_custom_player_id", false))
            return false;
        var normalized = NormalizeText(AndroidSettingsBridge.GetString("lan_custom_player_id", string.Empty));
        if (string.IsNullOrWhiteSpace(normalized))
            return false;
        if (ulong.TryParse(normalized, out peerId) && peerId > 1)
            return true;
        peerId = MapCustomPeerId(normalized);
        return true;
    }

    private static string GetConfiguredPlayerDisplayName()
    {
        return AndroidSettingsBridge.GetBool("lan_use_custom_player_id", false)
            ? NormalizeText(AndroidSettingsBridge.GetString("lan_custom_player_id", string.Empty))
            : string.Empty;
    }

    private static ulong MapCustomPeerId(string normalizedPeerId)
    {
        var bytes = Encoding.UTF8.GetBytes(normalizedPeerId);
        using var sha256 = System.Security.Cryptography.SHA256.Create();
        var hash = sha256.ComputeHash(bytes);
        var value = BinaryPrimitives.ReadUInt64BigEndian(hash.AsSpan(0, 8)) & 0x7FFFFFFFFFFFFFFFUL;
        return value <= 1 ? value + 2 : value;
    }

    private static string NormalizeText(string raw) => (raw ?? string.Empty).Trim().Normalize(NormalizationForm.FormKC);

    private static bool TryParseEndpoint(string hostInput, string portInput, out string host, out ushort port, out string error)
    {
        host = (hostInput ?? string.Empty).Trim();
        var portText = (portInput ?? string.Empty).Trim();
        if (TryExtractInlinePort(ref host, out var inlinePort))
            portText = inlinePort.ToString();
        if (string.IsNullOrWhiteSpace(host))
        {
            port = 0;
            error = "Enter the host IP address.";
            return false;
        }
        if (string.IsNullOrWhiteSpace(portText))
        {
            port = DefaultPort;
            error = string.Empty;
            return true;
        }
        if (!ushort.TryParse(portText, out port) || port == 0)
        {
            error = "Port must be a number from 1 to 65535.";
            return false;
        }
        error = string.Empty;
        return true;
    }

    private static bool TryExtractInlinePort(ref string host, out ushort port)
    {
        port = 0;
        if (string.IsNullOrWhiteSpace(host))
            return false;
        var colon = Math.Max(host.LastIndexOf(':'), host.LastIndexOf('：'));
        if (colon <= 0)
            return false;
        var hostPart = host[..colon].Trim();
        var portPart = host[(colon + 1)..].Trim();
        if (string.IsNullOrWhiteSpace(hostPart) || string.IsNullOrWhiteSpace(portPart) || hostPart.Contains(':') || hostPart.Contains('：'))
            return false;
        if (!ushort.TryParse(portPart, out port) || port == 0)
            return false;
        host = hostPart;
        return true;
    }

    private static void SaveJoinEndpoint(string host, ushort port)
    {
        SaveLanSetting("lan_join_host", host.Trim());
        SaveLanSetting("lan_join_port", port);
    }

    private static string GetSavedJoinHost() => AndroidSettingsBridge.GetString("lan_join_host", string.Empty);

    private static ushort GetSavedJoinPort()
    {
        var port = AndroidSettingsBridge.GetInt("lan_join_port", DefaultPort);
        return port is > 0 and <= 65535 ? (ushort)port : DefaultPort;
    }

    private static void SaveLanSetting(string key, object value)
    {
        if (!AndroidSettingsBridge.TryReadRaw(out var json) || string.IsNullOrWhiteSpace(json))
            json = "{}";
        var node = System.Text.Json.Nodes.JsonNode.Parse(json)?.AsObject() ?? new System.Text.Json.Nodes.JsonObject();
        node[key] = value switch
        {
            string s => s,
            int i => i,
            ushort u => u,
            ulong ul => ul.ToString(),
            _ => value?.ToString(),
        };
        AndroidSettingsBridge.TryWriteRaw(node.ToJsonString(new System.Text.Json.JsonSerializerOptions { WriteIndented = true }));
    }

    private static void ConfigureLanJoinScreen(NJoinFriendScreen screen)
    {
        var title = screen.GetNodeOrNull<MegaLabel>("TitleLabel");
        title?.SetTextAutoSize("Join LAN Game");

        var buttonContainer = screen.GetNodeOrNull<Control>("%ButtonContainer");
        if (buttonContainer != null)
            buttonContainer.Visible = false;
        var loading = screen.GetNodeOrNull<Control>("%LoadingIndicator");
        if (loading != null)
            loading.Visible = false;
        var noFriends = screen.GetNodeOrNull<Control>("%NoFriendsText");
        if (noFriends != null)
            noFriends.Visible = false;

        var panel = screen.GetNode<Control>("Panel");
        var container = panel.GetNodeOrNull<VBoxContainer>("LanJoinContainer") ?? CreateLanJoinContainer(panel);
        container.Visible = true;

        var helpLabel = container.GetNodeOrNull<MegaLabel>("LanHelpLabel");
        helpLabel?.SetTextAutoSize($"Host a game on the same LAN, then enter the host IP and port here. Default port: {DefaultPort}. You can also enter IP:port.");

        var hostInput = container.GetNode<LineEdit>("LanHostRow/LanHostInput");
        var portInput = container.GetNode<LineEdit>("LanPortRow/LanPortInput");
        hostInput.PlaceholderText = "192.168.0.123";
        portInput.PlaceholderText = DefaultPort.ToString();
        hostInput.Text = GetSavedJoinHost();
        portInput.Text = GetSavedJoinPort().ToString();

        var refreshButton = screen.GetNodeOrNull<NJoinFriendRefreshButton>("RefreshButton");
        if (refreshButton != null)
        {
            var label = refreshButton.GetNodeOrNull<MegaLabel>("Label");
            label?.SetTextAutoSize("Join");
            var icon = refreshButton.GetNodeOrNull<Control>("ControllerIcon");
            if (icon != null)
                icon.Visible = false;
        }

        ReplaceLineSubmitted(hostInput, _ => TaskHelper.RunSafely(JoinLanGameAsync(screen, hostInput, portInput)));
        ReplaceLineSubmitted(portInput, _ => TaskHelper.RunSafely(JoinLanGameAsync(screen, hostInput, portInput)));
        hostInput.CallDeferred(Control.MethodName.GrabFocus);
    }

    private static VBoxContainer CreateLanJoinContainer(Control panel)
    {
        var container = new VBoxContainer
        {
            Name = "LanJoinContainer",
            UniqueNameInOwner = true,
            AnchorLeft = 0.5f,
            AnchorTop = 0.5f,
            AnchorRight = 0.5f,
            AnchorBottom = 0.5f,
            OffsetLeft = -332,
            OffsetTop = -8,
            OffsetRight = 332,
            OffsetBottom = 202,
            GrowHorizontal = Control.GrowDirection.Both,
            GrowVertical = Control.GrowDirection.Both,
        };
        container.AddThemeConstantOverride("separation", 18);

        var help = MakeMegaLabel("LanHelpLabel", "Join the host by entering their LAN IP and port.", 26, Colors.White);
        help.CustomMinimumSize = new Vector2(0, 72);
        help.AutowrapMode = TextServer.AutowrapMode.WordSmart;
        container.AddChild(help);
        container.AddChild(MakeInputRow("LanHostRow", "LanHostLabel", "Host IP", "LanHostInput", false));
        container.AddChild(MakeInputRow("LanPortRow", "LanPortLabel", "Port", "LanPortInput", true));
        panel.AddChild(container);
        return container;
    }

    private static HBoxContainer MakeInputRow(string rowName, string labelName, string labelText, string inputName, bool numeric)
    {
        var row = new HBoxContainer { Name = rowName };
        row.AddThemeConstantOverride("separation", 18);
        var label = MakeMegaLabel(labelName, labelText, 28, new Color(0.937255f, 0.784314f, 0.317647f));
        label.CustomMinimumSize = new Vector2(120, 0);
        label.VerticalAlignment = VerticalAlignment.Center;
        row.AddChild(label);
        var input = new LineEdit
        {
            Name = inputName,
            UniqueNameInOwner = true,
            CustomMinimumSize = new Vector2(0, 48),
            SizeFlagsHorizontal = Control.SizeFlags.ExpandFill,
            MaxLength = numeric ? 5 : 128,
        };
        input.AddThemeFontSizeOverride("font_size", 24);
        row.AddChild(input);
        return row;
    }

    private static MegaLabel MakeMegaLabel(string name, string text, int fontSize, Color color)
    {
        var label = new MegaLabel { Name = name, Text = text };
        label.AddThemeColorOverride("font_color", color);
        label.AddThemeColorOverride("font_outline_color", Colors.Black);
        label.AddThemeConstantOverride("outline_size", 6);
        label.AddThemeFontSizeOverride("font_size", fontSize);
        return label;
    }

    private static bool TryGetLanInputs(NJoinFriendScreen screen, out LineEdit hostInput, out LineEdit portInput)
    {
        hostInput = null;
        portInput = null;
        var container = screen.GetNodeOrNull<VBoxContainer>("Panel/LanJoinContainer");
        if (container?.Visible != true)
            return false;
        hostInput = container.GetNodeOrNull<LineEdit>("LanHostRow/LanHostInput");
        portInput = container.GetNodeOrNull<LineEdit>("LanPortRow/LanPortInput");
        return hostInput != null && portInput != null;
    }

    private static void ReplaceLineSubmitted(LineEdit input, Action<string> action)
    {
        try
        {
            if (input.HasMeta("sts2_mobile_lan_submit_connected"))
                return;
            input.SetMeta("sts2_mobile_lan_submit_connected", true);
            input.Connect(LineEdit.SignalName.TextSubmitted, Callable.From<string>(value => action(value)));
        }
        catch
        {
        }
    }

    private static async Task JoinLanGameAsync(NJoinFriendScreen screen, LineEdit hostInput, LineEdit portInput)
    {
        if (!TryParseEndpoint(hostInput.Text, portInput.Text, out var host, out var port, out var error))
        {
            ShowInfoPopup("Unable to join LAN game", error);
            return;
        }
        hostInput.Text = host;
        portInput.Text = port.ToString();
        SaveJoinEndpoint(host, port);
        var joinFlow = new JoinFlow();
        AccessTools.Field(typeof(NJoinFriendScreen), "_currentJoinFlow")?.SetValue(screen, joinFlow);
        await InvokeJoinGameAsync(screen, joinFlow, new ENetClientConnectionInitializer(GetOrCreateLocalPeerId(), host, port));
    }

    private static async Task InvokeJoinGameAsync(NJoinFriendScreen screen, JoinFlow joinFlow, IClientConnectionInitializer initializer)
    {
        var method = typeof(NJoinFriendScreen).GetMethod("JoinGameAsync", BindingFlags.Public | BindingFlags.Instance);
        if (method != null)
        {
            await (Task)method.Invoke(screen, new object[] { initializer });
            return;
        }
        var result = await joinFlow.Begin(initializer, screen.GetTree());
        throw new NotSupportedException($"JoinGameAsync reflection target missing; manual fallback for state {result.sessionState} is not implemented.");
    }

    private static Task StartHostFromSubmenuAsync(GameMode gameMode, Control loadingOverlay, NSubmenuStack stack)
    {
        var platformType = PlatformType.None;
        var hostCapacity = GetHostCapacityForPlatform(platformType, GetConfiguredHostMaxPlayers());
        loadingOverlay.Visible = true;
        try
        {
            var netService = new NetHostGameService();
            var error = netService.StartENetHost(DefaultPort, hostCapacity);
            if (!error.HasValue)
            {
                switch (gameMode)
                {
                    case GameMode.Standard:
                        var character = stack.GetSubmenuType<MegaCrit.Sts2.Core.Nodes.Screens.CharacterSelect.NCharacterSelectScreen>();
                        character.InitializeMultiplayerAsHost(netService, hostCapacity);
                        stack.Push(character);
                        break;
                    case GameMode.Daily:
                        var daily = stack.GetSubmenuType<MegaCrit.Sts2.Core.Nodes.Screens.DailyRun.NDailyRunScreen>();
                        daily.InitializeMultiplayerAsHost(netService);
                        stack.Push(daily);
                        break;
                    default:
                        var custom = stack.GetSubmenuType<MegaCrit.Sts2.Core.Nodes.Screens.CustomRun.NCustomRunScreen>();
                        custom.InitializeMultiplayerAsHost(netService, hostCapacity);
                        stack.Push(custom);
                        break;
                }
                ShowLanHostInfo();
            }
            else
            {
                AddModal(NErrorPopup.Create(error.Value));
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"LAN host failed: {exception}");
            AddModal(NErrorPopup.Create(new NetErrorInfo(NetError.InternalError, selfInitiated: false)));
            throw;
        }
        finally
        {
            loadingOverlay.Visible = false;
        }
        return Task.CompletedTask;
    }

    private static Task StartLoadedHostAsync(object multiplayerSubmenu, SerializableRun run)
    {
        var loadingOverlay = (Control)AccessTools.Field(multiplayerSubmenu.GetType(), "_loadingOverlay")?.GetValue(multiplayerSubmenu);
        var stack = (NSubmenuStack)AccessTools.Field(typeof(NSubmenu), "_stack")?.GetValue(multiplayerSubmenu);
        if (loadingOverlay == null || stack == null)
            throw new InvalidOperationException("Could not reflect multiplayer submenu loading overlay/stack.");

        var hostCapacity = GetHostCapacityForPlatform(PlatformType.None, Math.Max(GetConfiguredHostMaxPlayers(), run.Players.Count));
        loadingOverlay.Visible = true;
        try
        {
            var netService = new NetHostGameService();
            var error = netService.StartENetHost(DefaultPort, hostCapacity);
            if (!error.HasValue)
            {
                switch (GetRunGameMode(run))
                {
                    case GameMode.Daily:
                        var daily = stack.GetSubmenuType<MegaCrit.Sts2.Core.Nodes.Screens.DailyRun.NDailyRunLoadScreen>();
                        daily.InitializeAsHost(netService, run);
                        stack.Push(daily);
                        break;
                    case GameMode.Custom:
                        var custom = stack.GetSubmenuType<MegaCrit.Sts2.Core.Nodes.Screens.CustomRun.NCustomRunLoadScreen>();
                        custom.InitializeAsHost(netService, run);
                        stack.Push(custom);
                        break;
                    default:
                        var standard = stack.GetSubmenuType<MegaCrit.Sts2.Core.Nodes.Screens.CharacterSelect.NMultiplayerLoadGameScreen>();
                        standard.InitializeAsHost(netService, run);
                        stack.Push(standard);
                        break;
                }
                ShowLanHostInfo();
            }
            else
            {
                AddModal(NErrorPopup.Create(error.Value));
            }
        }
        finally
        {
            loadingOverlay.Visible = false;
        }
        return Task.CompletedTask;
    }

    private static GameMode GetRunGameMode(SerializableRun run)
    {
        var property = run.GetType().GetProperty("GameMode", BindingFlags.Public | BindingFlags.Instance);
        if (property?.GetValue(run) is GameMode gameMode)
            return gameMode;
        return GameMode.Standard;
    }

    private static void ShowLanHostInfo()
    {
        AddModal(NErrorPopup.Create("LAN Host Ready", BuildHostInstructions(DefaultPort), showReportBugButton: false));
    }

    private static string BuildHostInstructions(ushort port)
    {
        var builder = new StringBuilder();
        builder.AppendLine("LAN game is ready.");
        builder.AppendLine("Other players on the same Wi‑Fi / LAN can join with one of these addresses:");
        builder.AppendLine();
        var addresses = GetLocalIpv4Addresses().ToArray();
        if (addresses.Length == 0)
        {
            builder.AppendLine($"Port: {port}");
            builder.AppendLine("Could not detect this device's LAN IP. Check system network settings and use <IP>:<port>.");
        }
        else
        {
            foreach (var address in addresses)
                builder.AppendLine($"{address}:{port}");
        }
        return builder.ToString().TrimEnd();
    }

    private static IEnumerable<string> GetLocalIpv4Addresses()
    {
        var ips = new HashSet<string>(StringComparer.Ordinal);
        try
        {
            foreach (var network in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (network.OperationalStatus != OperationalStatus.Up || network.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                    continue;
                foreach (var addressInfo in network.GetIPProperties().UnicastAddresses)
                {
                    var address = addressInfo.Address;
                    if (address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address))
                    {
                        var text = address.ToString();
                        if (!text.StartsWith("169.254.", StringComparison.Ordinal))
                            ips.Add(text);
                    }
                }
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Failed to enumerate LAN IPs: {exception.Message}");
        }
        return ips.OrderBy(ip => ip, StringComparer.Ordinal);
    }

    private static void SetPlayerNameOverride(PlatformType platformType, ulong playerId, string playerName)
    {
        if (string.IsNullOrWhiteSpace(playerName))
        {
            if (PlayerNameOverrides.TryGetValue(platformType, out var values))
                values.Remove(playerId);
            return;
        }
        if (!PlayerNameOverrides.TryGetValue(platformType, out var map))
        {
            map = new Dictionary<ulong, string>();
            PlayerNameOverrides[platformType] = map;
        }
        map[playerId] = playerName.Trim();
    }

    private static bool TryGetPlayerNameOverride(PlatformType platformType, ulong playerId, out string playerName)
    {
        playerName = null;
        return PlayerNameOverrides.TryGetValue(platformType, out var map) && map.TryGetValue(playerId, out playerName) && !string.IsNullOrWhiteSpace(playerName);
    }

    private static void ClearPlayerNameOverrides(PlatformType platformType)
    {
        PlayerNameOverrides.Remove(platformType);
        SetPlayerNameOverride(platformType, PlatformUtil.GetLocalPlayerId(platformType), GetConfiguredPlayerDisplayName());
    }

    private static void ShowInfoPopup(string title, string body)
    {
        AddModal(NErrorPopup.Create(title, body, showReportBugButton: false));
    }

    private static void AddModal(Node popup)
    {
        if (popup != null)
            NModalContainer.Instance.Add(popup);
    }
}
