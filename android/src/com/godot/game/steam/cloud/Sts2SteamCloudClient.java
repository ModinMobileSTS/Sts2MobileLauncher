package com.godot.game.steam.cloud;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.security.MessageDigest;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.rpc.service.Cloud;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType;
import in.dragonbra.javasteam.steam.authentication.AuthenticationException;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSession;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.discovery.FileServerListProvider;
import in.dragonbra.javasteam.steam.discovery.ServerRecord;
import in.dragonbra.javasteam.steam.discovery.SmartCMServerList;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileDownloadInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadBlockDetails;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.HttpHeaders;
import in.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;
import in.dragonbra.javasteam.types.AsyncJobSingle;
import in.dragonbra.javasteam.types.SteamID;
import in.dragonbra.javasteam.util.log.LogListener;
import in.dragonbra.javasteam.util.log.LogManager;
import okhttp3.RequestBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class Sts2SteamCloudClient implements AutoCloseable {
    private static final String TAG = "Sts2SteamCloud";
    private static final long CONNECT_TIMEOUT_MS = 40_000L;
    private static final long AUTH_START_TIMEOUT_MS = 60_000L;
    private static final long AUTH_POLL_TIMEOUT_MS = 4L * 60L * 1000L;
    private static final long LOGON_TIMEOUT_MS = 45_000L;
    private static final long RPC_TIMEOUT_MS = 90_000L;
    private static final long DOWNLOAD_TIMEOUT_MS = 60_000L;
    private static final long CALLBACK_POLL_TIMEOUT_MS = 250L;
    private static final int DOWNLOAD_MAX_ATTEMPTS = 4;
    private static final int FILE_UPLOAD_MAX_ATTEMPTS = 2;
    private static final int CLOUD_ALL_PLATFORMS = 0xFFFF_FFFF;
    private static final long[] DOWNLOAD_RETRY_DELAYS_MS = new long[] { 2_000L, 5_000L, 10_000L };
    private static final int BEGIN_FILE_UPLOAD_MAX_ATTEMPTS = 5;
    private static final long[] BEGIN_FILE_UPLOAD_RETRY_DELAYS_MS = new long[] { 2_000L, 5_000L, 10_000L };
    private static final long[] BEGIN_FILE_UPLOAD_DUPLICATE_RETRY_DELAYS_MS =
        new long[] { 2_000L, 5_000L, 10_000L, 20_000L };
    private static final long[] BEGIN_FILE_UPLOAD_PENDING_RETRY_DELAYS_MS =
        new long[] { 10_000L, 20_000L, 30_000L, 60_000L };
    private static final int TRANSIENT_RPC_MAX_ATTEMPTS = 4;
    private static final long[] TRANSIENT_RPC_RETRY_DELAYS_MS = new long[] { 2_000L, 5_000L, 10_000L };
    private static final int JAVA_STEAM_LOG_TAIL_LIMIT = 12;
    private static final int JAVA_STEAM_STACKTRACE_LINE_LIMIT = 24;
    private static final int DIAGNOSTIC_EVENT_LIMIT = 96;
    private static final String OUTPUT_DIR_NAME = "steam-cloud";
    private static final String LAST_CM_ENDPOINT_FILE_NAME = "last-websocket-cm-endpoint.txt";
    private static final String CM_SERVER_LIST_FILE_NAME = "steam-cm-server-list.bin";

    private final SteamClient steamClient;
    private final CallbackManager callbackManager;
    private final SteamUser steamUser;
    private final SteamCloud steamCloud;
    private final Cloud cloudService;
    private final OkHttpClient httpClient;
    private final File lastCmEndpointFile;
    private final boolean wattAccelerationEnabled;
    private final EnumSet<ProtocolTypes> protocolTypes = EnumSet.of(ProtocolTypes.WEB_SOCKET);
    private final JavaSteamLogCollector javaSteamLogCollector;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final CompletableFuture<Void> connectedFuture = new CompletableFuture<>();
    private final CompletableFuture<Void> disconnectedFuture = new CompletableFuture<>();
    private final CompletableFuture<LoggedOnCallback> loggedOnFuture = new CompletableFuture<>();
    private volatile String currentStage = "startup";
    private volatile boolean connectedCallbackReceived = false;
    private volatile String loggedOnResultDescription = "<not received>";
    private volatile String disconnectedDescription = "<not observed>";
    private volatile String resolvedServerDescription = "<not resolved>";
    private volatile String candidateSourceDescription = "<not selected>";
    private volatile String allowedChallengesDescription = "<not evaluated>";
    private volatile String lastAuthPromptDescription = "<not requested>";
    private volatile boolean guardDataConfigured = false;
    private volatile boolean guardDataUpdated = false;
    private volatile String currentSteamId64 = "";
    private volatile String loggedOnCallbackSteamId64 = "";
    private volatile String steamClientSteamId64 = "";
    private volatile long cmServerSelectionMs = -1L;
    private volatile long cmConnectWaitMs = -1L;
    private final Object diagnosticEventsLock = new Object();
    private final ArrayDeque<String> diagnosticEvents = new ArrayDeque<>();
    private Thread callbackThread;

    private static final class PreparedServerRecord {
        private final ServerRecord serverRecord;
        private final String candidateSourceDescription;

        private PreparedServerRecord(ServerRecord serverRecord, String candidateSourceDescription) {
            this.serverRecord = serverRecord;
            this.candidateSourceDescription = candidateSourceDescription;
        }
    }

    public Sts2SteamCloudClient(Context context) {
        applyProxySystemProperties();

        File outputDir = new File(new File(context.getFilesDir(), "steam"), OUTPUT_DIR_NAME);
        if (!outputDir.isDirectory()) {
            outputDir.mkdirs();
        }
        lastCmEndpointFile = new File(outputDir, LAST_CM_ENDPOINT_FILE_NAME);

        wattAccelerationEnabled = false;
        httpClient = com.godot.game.steam.core.SteamNetworkClientFactory.createDefaultClient();
        Log.i(TAG, "Steam Cloud direct OkHttp transport enabled.");

        File cmServerListFile = new File(outputDir, CM_SERVER_LIST_FILE_NAME);
        SteamConfiguration steamConfiguration = SteamConfiguration.create(builder -> {
            builder.withHttpClient(httpClient);
            builder.withConnectionTimeout(CONNECT_TIMEOUT_MS);
            builder.withProtocolTypes(protocolTypes);
            builder.withServerListProvider(new FileServerListProvider(cmServerListFile));
        });

        javaSteamLogCollector = new JavaSteamLogCollector();
        LogManager.addListener(javaSteamLogCollector);
        steamClient = new SteamClient(steamConfiguration);
        callbackManager = new CallbackManager(steamClient);
        steamUser = requireNonNull(steamClient.getHandler(SteamUser.class), "SteamUser handler");
        steamCloud = requireNonNull(steamClient.getHandler(SteamCloud.class), "SteamCloud handler");
        SteamUnifiedMessages unifiedMessages = requireNonNull(
            steamClient.getHandler(SteamUnifiedMessages.class),
            "SteamUnifiedMessages handler"
        );
        cloudService = unifiedMessages.createService(Cloud.class);

        callbackManager.subscribe(ConnectedCallback.class, callback -> {
            connectedCallbackReceived = true;
            recordDiagnosticEvent("connected_callback received stage=" + currentStage);
            Log.i(TAG, "Connected to Steam.");
            connectedFuture.complete(null);
        });
        callbackManager.subscribe(DisconnectedCallback.class, callback -> {
            String reason = callback.isUserInitiated() ? "user initiated" : "unexpected";
            disconnectedDescription = reason;
            recordDiagnosticEvent("disconnected_callback reason=" + reason + " stage=" + currentStage);
            Log.i(TAG, "Disconnected from Steam (" + reason + ") during " + currentStage + '.');
            if (!shuttingDown.get()) {
                IllegalStateException error = new IllegalStateException(buildDisconnectFailureMessage(reason));
                disconnectedFuture.completeExceptionally(error);
                connectedFuture.completeExceptionally(error);
                loggedOnFuture.completeExceptionally(error);
            }
        });
        callbackManager.subscribe(LoggedOnCallback.class, callback -> {
            loggedOnResultDescription = String.valueOf(callback.getResult());
            SteamID steamID = callback.getClientSteamID();
            currentSteamId64 = steamID == null ? "" : String.valueOf(steamID.convertToUInt64());
            loggedOnCallbackSteamId64 = currentSteamId64;
            SteamID clientSteamId = steamClient.getSteamID();
            steamClientSteamId64 = clientSteamId == null ? "" : String.valueOf(clientSteamId.convertToUInt64());
            recordDiagnosticEvent(
                "logged_on_callback result="
                    + callback.getResult()
                    + " callbackSteamIdResolved="
                    + !isBlank(loggedOnCallbackSteamId64)
                    + " clientSteamIdResolved="
                    + !isBlank(steamClientSteamId64)
            );
            Log.i(TAG, "Steam logon result: " + callback.getResult());
            loggedOnFuture.complete(callback);
        });
    }

    public void beginOperationDiagnostics(String operation, String accountName, boolean hasGuardData) {
        currentStage = operation == null || operation.trim().isEmpty() ? "startup" : operation.trim();
        guardDataConfigured = hasGuardData;
        guardDataUpdated = false;
        allowedChallengesDescription = "<not evaluated>";
        lastAuthPromptDescription = "<not requested>";
        loggedOnResultDescription = "<not received>";
        currentSteamId64 = "";
        loggedOnCallbackSteamId64 = "";
        steamClientSteamId64 = "";
        disconnectedDescription = "<not observed>";
        cmServerSelectionMs = -1L;
        cmConnectWaitMs = -1L;
        synchronized (diagnosticEventsLock) {
            diagnosticEvents.clear();
        }
        recordDiagnosticEvent(
            "operation_begin operation="
                + currentStage
                + " account="
                + (isBlank(accountName) ? "<unknown>" : accountName.trim())
                + " guardDataConfigured="
                + hasGuardData
                + " protocolTypes="
                + describeProtocolTypes(protocolTypes)
                + " wattAcceleration="
                + (wattAccelerationEnabled ? "enabled" : "disabled")
        );
        Log.i(
            TAG,
            "Beginning Steam Cloud operation="
                + currentStage
                + " account="
                + (isBlank(accountName) ? "<unknown>" : accountName.trim())
                + " guardDataConfigured="
                + hasGuardData
        );
    }

    public void start() throws Exception {
        try {
            running.set(true);
            recordDiagnosticEvent("callback_thread starting");
            callbackThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        callbackManager.runWaitCallbacks(CALLBACK_POLL_TIMEOUT_MS);
                    } catch (Throwable error) {
                        if (shuttingDown.get() || !running.get()) {
                            break;
                        }
                        recordDiagnosticEvent("callback_loop_failed " + describeThrowable(error));
                        Log.e(TAG, "Steam callback loop failed unexpectedly.", error);
                        connectedFuture.completeExceptionally(error);
                        loggedOnFuture.completeExceptionally(error);
                        disconnectedFuture.completeExceptionally(error);
                        break;
                    }
                }
            }, "steam-cloud-client-callbacks");
            callbackThread.setDaemon(true);
            callbackThread.start();
            recordDiagnosticEvent("callback_thread started");

            PreparedServerRecord preparedServerRecord;
            long serverSelectionStartedAtNs = System.nanoTime();
            try {
                recordDiagnosticEvent("cm_server_selection begin");
                preparedServerRecord = selectWebSocketServerRecord();
            } finally {
                cmServerSelectionMs = elapsedMillis(serverSelectionStartedAtNs);
                recordDiagnosticEvent("cm_server_selection end durationMs=" + cmServerSelectionMs);
            }
            ServerRecord serverRecord = preparedServerRecord == null ? null : preparedServerRecord.serverRecord;
            if (serverRecord == null) {
                throw new IllegalStateException(
                    "Steam server list returned no websocket CM candidate, and no fallback websocket endpoint was available."
                );
            }

            candidateSourceDescription = preparedServerRecord.candidateSourceDescription;
            resolvedServerDescription = describeServerRecord(serverRecord);
            recordDiagnosticEvent(
                "cm_connect begin endpoint="
                    + resolvedServerDescription
                    + " source="
                    + candidateSourceDescription
            );
            Log.i(
                TAG,
                "Connecting to Steam websocket CM endpoint="
                    + resolvedServerDescription
                    + " source="
                    + candidateSourceDescription
            );
            long connectStartedAtNs = System.nanoTime();
            try {
                steamClient.connect(serverRecord);
                waitForStage(connectedFuture, CONNECT_TIMEOUT_MS, "Steam connect");
            } finally {
                cmConnectWaitMs = elapsedMillis(connectStartedAtNs);
                recordDiagnosticEvent("cm_connect wait_finished durationMs=" + cmConnectWaitMs);
            }
            persistResolvedWebSocketEndpoint(serverRecord);
            recordDiagnosticEvent("cm_connect endpoint_persisted");
        } catch (Exception error) {
            recordDiagnosticEvent("cm_connect failed " + describeThrowable(error));
            Log.e(TAG, "Steam connect failed during " + currentStage + '.', error);
            throw error;
        }
    }

    public AuthMaterial authenticateWithCredentials(
        String username,
        String password,
        String guardData,
        AuthPrompt prompt
    ) throws Exception {
        try {
            String inputDiagnostics = buildCredentialsInputDiagnostics(username, password, guardData);
            recordDiagnosticEvent(
                "credentials_auth begin " + inputDiagnostics
            );
            Log.i(
                TAG,
                "Starting credentials auth. " + inputDiagnostics
            );
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = username;
            details.password = password;
            details.guardData = guardData;
            details.persistentSession = true;
            details.authenticator = new PromptAuthenticator(prompt);

            AuthSession authSession = waitForStage(
                steamClient.getAuthentication().beginAuthSessionViaCredentials(details),
                AUTH_START_TIMEOUT_MS,
                "Steam auth session start"
            );
            recordDiagnosticEvent("credentials_auth session_started");
            maybeValidateSupportedChallenges(authSession);

            AuthPollResult pollResult = waitForStage(
                authSession.pollingWaitForResult(),
                AUTH_POLL_TIMEOUT_MS,
                "Steam auth completion"
            );

            String effectiveGuardData = pollResult.getNewGuardData() != null
                ? pollResult.getNewGuardData()
                : guardData;
            guardDataUpdated = !isBlank(pollResult.getNewGuardData());
            recordDiagnosticEvent(
                "credentials_auth completed account="
                    + pollResult.getAccountName()
                    + " refreshTokenReceived="
                    + !isBlank(pollResult.getRefreshToken())
                    + " refreshTokenLength="
                    + (pollResult.getRefreshToken() == null ? 0 : pollResult.getRefreshToken().length())
                    + " guardDataUpdated="
                    + guardDataUpdated
            );
            Log.i(
                TAG,
                "Credentials auth completed for account="
                    + pollResult.getAccountName()
                    + " refreshTokenLength="
                    + (pollResult.getRefreshToken() == null ? 0 : pollResult.getRefreshToken().length())
                    + " guardDataUpdated="
                    + guardDataUpdated
            );

            return new AuthMaterial(
                pollResult.getAccountName(),
                pollResult.getRefreshToken(),
                effectiveGuardData
            );
        } catch (Exception error) {
            recordDiagnosticEvent(
                "credentials_auth failed "
                    + describeAuthenticationResult(error)
                    + " "
                    + buildCredentialsInputDiagnostics(username, password, guardData)
                    + " "
                    + describeThrowable(error)
            );
            Log.e(TAG, "Credentials auth failed during " + currentStage + '.', error);
            throw error;
        }
    }

    public void logOnWithRefreshToken(String accountName, String refreshToken) throws Exception {
        try {
            recordDiagnosticEvent(
                "refresh_token_logon begin account="
                    + (isBlank(accountName) ? "<unknown>" : accountName.trim())
                    + " tokenProvided="
                    + !isBlank(refreshToken)
                    + " tokenLength="
                    + (refreshToken == null ? 0 : refreshToken.length())
            );
            Log.i(
                TAG,
                "Logging on with refresh token for account="
                    + (isBlank(accountName) ? "<unknown>" : accountName.trim())
            );
            LogOnDetails details = new LogOnDetails();
            details.setUsername(accountName);
            details.setAccessToken(refreshToken);
            details.setShouldRememberPassword(true);
            details.setLoginID(149);
            steamUser.logOn(details);

            LoggedOnCallback callback = waitForStage(loggedOnFuture, LOGON_TIMEOUT_MS, "Steam logon");
            if (callback.getResult() != EResult.OK) {
                throw new IllegalStateException("Steam logon failed: " + callback.getResult());
            }
            SteamID steamID = callback.getClientSteamID();
            if (steamID != null) {
                currentSteamId64 = String.valueOf(steamID.convertToUInt64());
            } else if (steamClient.getSteamID() != null) {
                currentSteamId64 = String.valueOf(steamClient.getSteamID().convertToUInt64());
            }
            SteamID clientSteamId = steamClient.getSteamID();
            steamClientSteamId64 = clientSteamId == null ? "" : String.valueOf(clientSteamId.convertToUInt64());
            recordDiagnosticEvent(
                "refresh_token_logon completed steamIdResolved="
                    + !isBlank(currentSteamId64)
                    + " clientSteamIdResolved="
                    + !isBlank(steamClientSteamId64)
            );
        } catch (Exception error) {
            recordDiagnosticEvent("refresh_token_logon failed " + describeThrowable(error));
            Log.e(TAG, "Refresh-token logon failed during " + currentStage + '.', error);
            throw error;
        }
    }

    public String getCurrentSteamId64() {
        return currentSteamId64;
    }

    public List<RemoteFileRecord> listFiles(int appId) throws Exception {
        try {
            Log.i(TAG, "Requesting Steam Cloud manifest for AppID " + appId + '.');
            AppFileChangeList changeList = waitForStage(
                steamCloud.getAppFileListChange(appId),
                RPC_TIMEOUT_MS,
                "GetAppFileChangelist"
            );

            List<RemoteFileRecord> entries = new ArrayList<>();
            for (AppFileInfo file : changeList.getFiles()) {
                String pathPrefix = "";
                if (file.getPathPrefixIndex() >= 0 && file.getPathPrefixIndex() < changeList.getPathPrefixes().size()) {
                    pathPrefix = changeList.getPathPrefixes().get(file.getPathPrefixIndex());
                }

                String machineName = "";
                if (file.getMachineNameIndex() >= 0 && file.getMachineNameIndex() < changeList.getMachineNames().size()) {
                    machineName = changeList.getMachineNames().get(file.getMachineNameIndex());
                }

                String remotePath = joinRemotePath(pathPrefix, file.getFilename());
                entries.add(new RemoteFileRecord(
                    remotePath,
                    file.getRawFileSize(),
                    file.getTimestamp().toInstant().toEpochMilli(),
                    machineName,
                    file.getPersistState().name(),
                    bytesToHex(file.getShaFile())
                ));
            }

            entries.sort(Comparator.comparing(entry -> entry.remotePath.toLowerCase(Locale.ROOT)));
            Log.i(TAG, "Steam Cloud manifest request completed. files=" + entries.size());
            return entries;
        } catch (Exception error) {
            Log.e(TAG, "Steam Cloud manifest request failed during " + currentStage + '.', error);
            throw error;
        }
    }

    public DownloadResult downloadFile(int appId, String remotePath, File outputFile) throws Exception {
        return downloadFile(appId, remotePath, outputFile, -1L, "");
    }

    public DownloadResult downloadFile(
        int appId,
        String remotePath,
        File outputFile,
        long expectedRawSize,
        String expectedSha1
    ) throws Exception {
        for (int attempt = 1; attempt <= DOWNLOAD_MAX_ATTEMPTS; attempt++) {
            try {
                return downloadFileOnce(appId, remotePath, outputFile, expectedRawSize, expectedSha1);
            } catch (Exception error) {
                if (!isRetryableDownloadException(error) || attempt >= DOWNLOAD_MAX_ATTEMPTS) {
                    Log.e(TAG, "Steam Cloud file download failed for " + remotePath + '.', error);
                    throw error;
                }

                long delayMs = downloadRetryDelayMs(attempt);
                Log.w(
                    TAG,
                    "Steam Cloud download failed transiently for "
                        + remotePath
                        + ": "
                        + sanitizeSingleLine(error.getMessage())
                        + "; retrying attempt "
                        + (attempt + 1)
                        + "/"
                        + DOWNLOAD_MAX_ATTEMPTS
                        + " after "
                        + delayMs
                        + "ms.",
                    error
                );
                sleepBeforeRetry(delayMs);
            }
        }
        throw new IllegalStateException("Steam Cloud download failed without completing: " + remotePath);
    }

    private DownloadResult downloadFileOnce(
        int appId,
        String remotePath,
        File outputFile,
        long expectedRawSize,
        String expectedSha1
    ) throws Exception {
        long startedAtNs = System.nanoTime();
        long rpcMs = 0L;
        long httpMs = 0L;
        long unzipMs = 0L;
        long writeMs = 0L;
        long compressedBytesCount = 0L;
        long rawBytesCount = 0L;
        boolean decompressed = false;
        Log.i(TAG, "Downloading Steam Cloud file: " + remotePath);
        long rpcStartedAtNs = System.nanoTime();
        FileDownloadInfo info = waitForStageWithRetries(
            () -> steamCloud.clientFileDownload(appId, remotePath),
            RPC_TIMEOUT_MS,
            "ClientFileDownload"
        );
        rpcMs = elapsedMillis(rpcStartedAtNs);

        if (info.getUrlHost().isEmpty()) {
            throw new IllegalStateException("Steam returned an empty download host for " + remotePath);
        }

        String scheme = info.getUseHttps() ? "https://" : "http://";
        String url = scheme + info.getUrlHost() + info.getUrlPath();
        Request.Builder requestBuilder = new Request.Builder().url(url);
        for (HttpHeaders header : info.getRequestHeaders()) {
            requestBuilder.addHeader(header.getName(), header.getValue());
        }

        byte[] compressedBytes;
        long httpStartedAtNs = System.nanoTime();
        try {
            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new HttpStatusIOException(response.code(), "downloading", remotePath);
                }
                if (response.body() == null) {
                    throw new IOException("Steam returned an empty response body for " + remotePath);
                }
                compressedBytes = response.body().bytes();
            }
        } catch (IOException error) {
            if (error instanceof HttpStatusIOException) {
                throw error;
            }
            throw new HttpTransferIOException("HTTP transfer failed when downloading " + remotePath, error);
        }
        httpMs = elapsedMillis(httpStartedAtNs);
        compressedBytesCount = compressedBytes.length;

        byte[] rawBytes = compressedBytes;
        if (info.getRawFileSize() != info.getFileSize()) {
            long unzipStartedAtNs = System.nanoTime();
            rawBytes = maybeUnzip(compressedBytes, remotePath);
            unzipMs = elapsedMillis(unzipStartedAtNs);
            decompressed = true;
        }
        rawBytesCount = rawBytes.length;
        validateDownloadedBytes(rawBytes, expectedRawSize, expectedSha1, remotePath);

        File parent = outputFile.getParentFile();
        ensureDirectoryExists(parent, "output directory");
        long writeStartedAtNs = System.nanoTime();
        try (FileOutputStream outputStream = new FileOutputStream(outputFile, false)) {
            outputStream.write(rawBytes);
        }
        writeMs = elapsedMillis(writeStartedAtNs);
        long totalMs = elapsedMillis(startedAtNs);
        Log.i(
            TAG,
            "Downloaded Steam Cloud file: "
                + remotePath
                + " totalMs="
                + totalMs
                + " rpcMs="
                + rpcMs
                + " httpMs="
                + httpMs
                + " unzipMs="
                + unzipMs
                + " writeMs="
                + writeMs
                + " compressedBytes="
                + compressedBytesCount
                + " rawBytes="
                + rawBytesCount
                + " output="
                + outputFile.getAbsolutePath()
        );
        return new DownloadResult(
            remotePath,
            outputFile.getAbsolutePath(),
            compressedBytesCount,
            rawBytesCount,
            decompressed,
            rpcMs,
            httpMs,
            unzipMs,
            writeMs,
            totalMs
        );
    }

    public UploadBatch beginUploadBatch(int appId, List<String> remotePaths) throws Exception {
        return beginUploadBatch(appId, remotePaths, Collections.emptyList());
    }

    public UploadBatch beginManualUploadBatch(int appId, List<String> remotePaths) throws Exception {
        int uploadCount = remotePaths == null ? 0 : remotePaths.size();
        recordDiagnosticEvent(
            "begin_app_upload_batch manual_uploads actualUploadCount="
                + uploadCount
                + " declaredUploadPaths=false"
        );
        return beginUploadBatch(appId, Collections.emptyList(), Collections.emptyList());
    }

    public UploadBatch beginUploadBatch(
        int appId,
        List<String> remotePathsToUpload,
        List<String> remotePathsToDelete
    ) throws Exception {
        try {
            Log.i(
                TAG,
                "Beginning Steam Cloud upload batch. declaredUploads="
                    + remotePathsToUpload.size()
                    + " deletes="
                    + remotePathsToDelete.size()
            );
            SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Request request =
                SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Request.newBuilder()
                    .setAppid(appId)
                    .setMachineName(buildUploadMachineName())
                    .addAllFilesToUpload(remotePathsToUpload)
                    .addAllFilesToDelete(remotePathsToDelete)
                    .setClientId(0L)
                    .setAppBuildId(0L)
                    .build();
            ServiceMethodResponse<SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Response.Builder> response =
                waitForServiceJobWithRetries(
                    () -> cloudService.beginAppUploadBatch(request),
                    RPC_TIMEOUT_MS,
                    "BeginAppUploadBatch"
                );
            ensureServiceResult(response, "BeginAppUploadBatch");
            SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Response.Builder body = response.getBody();
            long batchId = body.getBatchId();
            if (batchId == 0L) {
                recordDiagnosticEvent("begin_app_upload_batch invalid_batch_id batchId=0 result=" + response.getResult());
            }
            ensureValidUploadBatchId(batchId, response.getResult());
            Log.i(
                TAG,
                "Steam Cloud upload batch started. batchId="
                    + batchId
                    + " appChangeNumber="
                    + body.getAppChangeNumber()
            );
            return new UploadBatch(batchId, body.getAppChangeNumber());
        } catch (Exception error) {
            Log.e(TAG, "Steam Cloud upload batch start failed during " + currentStage + '.', error);
            throw error;
        }
    }

    public UploadedFile uploadFile(int appId, String remotePath, File sourceFile, long uploadBatchId) throws Exception {
        if (!sourceFile.isFile()) {
            throw new IOException("Steam Cloud upload source file is missing: " + sourceFile.getAbsolutePath());
        }

        long fileSizeLong = sourceFile.length();
        if (fileSizeLong > Integer.MAX_VALUE) {
            throw new IOException("Steam Cloud upload does not support files larger than 2 GiB: " + remotePath);
        }
        byte[] sha1Bytes = sha1Bytes(sourceFile);
        String sha1Hex = bytesToHex(sha1Bytes);
        Exception lastError = null;
        for (int attempt = 1; attempt <= FILE_UPLOAD_MAX_ATTEMPTS; attempt++) {
            try {
                return uploadFileWithClientFileUpload(appId, remotePath, sourceFile, uploadBatchId, sha1Bytes, sha1Hex, attempt);
            } catch (Exception error) {
                lastError = error;
                if (attempt >= FILE_UPLOAD_MAX_ATTEMPTS || !isRetryableSteamCloudException(error)) {
                    break;
                }
                sleepBeforeTransientRetry("ClientBeginFileUpload", error, attempt);
            }
        }
        Log.e(TAG, "Steam Cloud upload failed for " + remotePath + '.', lastError);
        throw lastError == null ? new IllegalStateException("Steam Cloud upload failed without an exception: " + remotePath) : lastError;
    }

    private UploadedFile uploadFileWithClientFileUpload(
        int appId,
        String remotePath,
        File sourceFile,
        long uploadBatchId,
        byte[] sha1Bytes,
        String sha1Hex,
        int attempt
    ) throws Exception {
        long fileSizeLong = sourceFile.length();
        int fileSize = (int) fileSizeLong;
        boolean transferStarted = false;
        try {
            Log.i(
                TAG,
                "Beginning client file upload for Steam Cloud file: "
                    + remotePath
                    + " bytes="
                    + fileSize
                    + " batchId="
                    + uploadBatchId
                    + " sha1="
                    + sha1Hex
                    + " attempt="
                    + attempt
            );
            recordDiagnosticEvent(
                "client_begin_file_upload request remotePath="
                    + remotePath
                    + " bytes="
                    + fileSize
                    + " batchId="
                    + uploadBatchId
                    + " sha1="
                    + sha1Hex
                    + " attempt="
                    + attempt
            );
            SteammessagesCloudSteamclient.CCloud_ClientBeginFileUpload_Request request =
                SteammessagesCloudSteamclient.CCloud_ClientBeginFileUpload_Request.newBuilder()
                    .setAppid(appId)
                    .setFileSize(fileSize)
                    .setRawFileSize(fileSize)
                    .setFileSha(ByteString.copyFrom(sha1Bytes))
                    .setTimeStamp(Math.max(0L, sourceFile.lastModified() / 1000L))
                    .setFilename(remotePath)
                    .setPlatformsToSync(CLOUD_ALL_PLATFORMS)
                    .setCellId(steamClient.getCellID() == null ? 0 : steamClient.getCellID())
                    .setCanEncrypt(false)
                    .setIsSharedFile(false)
                    .setUploadBatchId(uploadBatchId)
                    .build();
            ServiceMethodResponse<SteammessagesCloudSteamclient.CCloud_ClientBeginFileUpload_Response.Builder> beginResponse =
                clientBeginFileUploadWithRetries(request, remotePath);
            FileUploadInfo uploadInfo = new FileUploadInfo(beginResponse.getBody());
            List<FileUploadBlockDetails> blocks = uploadInfo.getBlockRequests();
            if (beginResponse.getResult() == EResult.DuplicateRequest && (blocks == null || blocks.isEmpty())) {
                Log.i(TAG, "Steam Cloud upload treated as already pending/identical: " + remotePath);
                recordDiagnosticEvent("client_begin_file_upload duplicate_without_blocks_noop remotePath=" + remotePath);
                return new UploadedFile(remotePath, fileSizeLong, sha1Hex);
            }
            if (uploadInfo.getEncryptFile()) {
                throw new IllegalStateException("Steam requested encrypted file upload, which is not supported on Android for " + remotePath);
            }
            if (blocks == null || blocks.isEmpty()) {
                throw new IllegalStateException("Steam returned no upload blocks for " + remotePath);
            }
            recordDiagnosticEvent(
                "client_begin_file_upload response remotePath="
                    + remotePath
                    + " blocks="
                    + blocks.size()
                    + " encrypt="
                    + uploadInfo.getEncryptFile()
            );
            transferStarted = true;
            uploadClientFileBlocks(sourceFile, remotePath, blocks);
            boolean committed = commitClientFileUpload(true, appId, sha1Bytes, remotePath);
            if (!committed) {
                throw new IllegalStateException("Steam did not commit uploaded file: " + remotePath);
            }
            Log.i(TAG, "Steam Cloud upload committed: " + remotePath);
            recordDiagnosticEvent("client_commit_file_upload committed remotePath=" + remotePath);
            return new UploadedFile(remotePath, fileSizeLong, sha1Hex);
        } catch (Exception error) {
            recordDiagnosticEvent(
                "upload_file failed remotePath="
                    + remotePath
                    + " batchId="
                    + uploadBatchId
                    + " transferStarted="
                    + transferStarted
                    + " error="
                    + describeThrowable(error)
            );
            if (transferStarted) {
                try {
                    commitClientFileUpload(false, appId, sha1Bytes, remotePath);
                } catch (Throwable commitError) {
                    Log.w(TAG, "Failed to report Steam Cloud upload failure for " + remotePath + '.', commitError);
                }
            }
            throw error;
        }
    }

    private void uploadClientFileBlocks(File sourceFile, String remotePath, List<FileUploadBlockDetails> blocks) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(sourceFile, "r")) {
            for (int index = 0; index < blocks.size(); index++) {
                FileUploadBlockDetails block = blocks.get(index);
                byte[] requestBodyBytes = block.getExplicitBodyData();
                boolean explicitBody = requestBodyBytes != null && requestBodyBytes.length > 0;
                if (!explicitBody) {
                    int length = block.getBlockLength();
                    if (length < 0) {
                        throw new IOException("Steam returned invalid upload block length " + length + " for " + remotePath);
                    }
                    requestBodyBytes = new byte[length];
                    input.seek(block.getBlockOffset());
                    input.readFully(requestBodyBytes);
                }
                String url = (block.getUseHttps() ? "https://" : "http://") + block.getUrlHost() + block.getUrlPath();
                recordDiagnosticEvent(
                    "client_file_upload block remotePath="
                        + remotePath
                        + " index="
                        + (index + 1)
                        + "/"
                        + blocks.size()
                        + " offset="
                        + block.getBlockOffset()
                        + " length="
                        + requestBodyBytes.length
                        + " explicit="
                        + explicitBody
                        + " method="
                        + block.getHttpMethod()
                );
                Request.Builder requestBuilder = new Request.Builder().url(url);
                addRequestHeaders(requestBuilder, block.getRequestHeaders());
                RequestBody body = RequestBody.create(requestBodyBytes, null);
                requestBuilder.method(uploadBlockHttpMethodName(block.getHttpMethod()), body);
                try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                    if (!response.isSuccessful()) {
                        throw new HttpStatusIOException(response.code(), "uploading", remotePath);
                    }
                    recordDiagnosticEvent(
                        "client_file_upload block_success remotePath="
                            + remotePath
                            + " index="
                            + (index + 1)
                            + "/"
                            + blocks.size()
                            + " httpCode="
                            + response.code()
                    );
                }
            }
        } catch (IOException error) {
            if (error instanceof HttpStatusIOException) {
                throw error;
            }
            throw new HttpTransferIOException("HTTP transfer failed when uploading " + remotePath, error);
        }
    }

    private static void addRequestHeaders(Request.Builder requestBuilder, List<HttpHeaders> headers) {
        if (headers == null) {
            return;
        }
        for (HttpHeaders header : headers) {
            String name = header.getName();
            if ("host".equalsIgnoreCase(name) || "content-length".equalsIgnoreCase(name)) {
                continue;
            }
            requestBuilder.addHeader(name, header.getValue());
        }
    }

    private static String uploadBlockHttpMethodName(int steamHttpMethod) {
        // Steam uses the Steamworks EHTTPMethod numeric values:
        // 0 invalid, 1 GET, 2 HEAD, 3 POST, 4 PUT, 5 DELETE, 6 OPTIONS.
        // Cloud upload block requests are expected to be POST or PUT; an absent/invalid
        // value is treated as the legacy whole-file upload default, PUT.
        switch (steamHttpMethod) {
            case 0:
            case 4:
                return "PUT";
            case 3:
                return "POST";
            default:
                throw new IllegalArgumentException("Unsupported Steam Cloud upload HTTP method: " + steamHttpMethod);
        }
    }

    public void completeUploadBatch(int appId, long batchId, EResult batchResult) throws Exception {
        try {
            SteammessagesCloudSteamclient.CCloud_CompleteAppUploadBatch_Request request =
                SteammessagesCloudSteamclient.CCloud_CompleteAppUploadBatch_Request.newBuilder()
                    .setAppid(appId)
                    .setBatchId(batchId)
                    .setBatchEresult(batchResult.code())
                    .build();
            ServiceMethodResponse<SteammessagesCloudSteamclient.CCloud_CompleteAppUploadBatch_Response.Builder> response =
                waitForServiceJobWithRetries(
                    () -> cloudService.completeAppUploadBatchBlocking(request),
                    RPC_TIMEOUT_MS,
                    "CompleteAppUploadBatch"
                );
            ensureServiceResult(response, "CompleteAppUploadBatch");
            Log.i(TAG, "Steam Cloud upload batch completed. batchId=" + batchId + " result=" + batchResult);
        } catch (Exception error) {
            Log.e(TAG, "Steam Cloud upload batch completion failed during " + currentStage + '.', error);
            throw error;
        }
    }

    @Override
    public void close() {
        shuttingDown.set(true);
        running.set(false);

        Thread thread = callbackThread;
        if (thread != null) {
            thread.interrupt();
        }
        try {
            steamClient.disconnect();
        } catch (Throwable ignored) {
            // Best effort.
        }
        LogManager.removeListener(javaSteamLogCollector);
        if (thread != null) {
            try {
                thread.join(1000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        applyProxySystemProperties();
    }

    public DiagnosticsSnapshot snapshotDiagnostics() {
        return new DiagnosticsSnapshot(
            currentStage,
            describeProtocolTypes(protocolTypes),
            connectedCallbackReceived,
            loggedOnResultDescription,
            disconnectedDescription,
            resolvedServerDescription,
            candidateSourceDescription,
            allowedChallengesDescription,
            lastAuthPromptDescription,
            guardDataConfigured,
            guardDataUpdated,
            javaSteamLogCollector.describeLastLog(),
            javaSteamLogCollector.describeLastError(),
            javaSteamLogCollector.snapshotTailLines(),
            javaSteamLogCollector.snapshotErrorStackLines(),
            snapshotDiagnosticEvents(),
            wattAccelerationEnabled ? "enabled" : "disabled",
            loggedOnCallbackSteamId64,
            steamClientSteamId64,
            cmServerSelectionMs,
            cmConnectWaitMs
        );
    }

    private <T> T waitForStage(CompletableFuture<T> future, long timeoutMs, String stage) throws Exception {
        currentStage = stage;
        recordDiagnosticEvent("stage_begin name=" + stage + " timeoutMs=" + timeoutMs);
        long startedAtNs = System.nanoTime();
        try {
            T value = waitForEither(future, disconnectedFuture, timeoutMs, stage);
            recordDiagnosticEvent("stage_success name=" + stage + " durationMs=" + elapsedMillis(startedAtNs));
            return value;
        } catch (Exception error) {
            recordDiagnosticEvent(
                "stage_failed name="
                    + stage
                    + " durationMs="
                    + elapsedMillis(startedAtNs)
                    + " error="
                    + describeThrowable(error)
            );
            throw error;
        }
    }

    private <T> T waitForStageWithRetries(
        Supplier<CompletableFuture<T>> futureSupplier,
        long timeoutMs,
        String stage
    ) throws Exception {
        for (int attempt = 1; attempt <= TRANSIENT_RPC_MAX_ATTEMPTS; attempt++) {
            try {
                return waitForStage(futureSupplier.get(), timeoutMs, stage);
            } catch (Exception error) {
                if (!isRetryableSteamCloudException(error) || attempt >= TRANSIENT_RPC_MAX_ATTEMPTS) {
                    throw error;
                }
                sleepBeforeTransientRetry(stage, error, attempt);
            }
        }
        throw new IllegalStateException(stage + " failed without completing.");
    }

    private PreparedServerRecord selectWebSocketServerRecord() throws IOException {
        List<PreparedServerRecord> candidates = new ArrayList<>();

        ServerRecord serverListRecord = steamClient.getServers().getNextServerCandidate(protocolTypes);
        if (serverListRecord != null) {
            candidates.add(new PreparedServerRecord(serverListRecord, "Steam server list"));
        }

        String cachedAddress = readOptionalTextFile(lastCmEndpointFile);
        if (!isBlank(cachedAddress)) {
            candidates.add(
                new PreparedServerRecord(
                    ServerRecord.createWebSocketServer(cachedAddress),
                    "Cached websocket CM fallback"
                )
            );
        }

        String defaultAddress = SmartCMServerList.getDefaultServerWebSocket();
        if (!isBlank(defaultAddress)) {
            candidates.add(
                new PreparedServerRecord(
                    ServerRecord.createWebSocketServer(defaultAddress),
                    "JavaSteam default websocket CM"
                )
            );
        }

        IOException lastResolutionError = null;
        List<String> attemptedKeys = new ArrayList<>();
        for (PreparedServerRecord candidate : candidates) {
            String dedupeKey = buildServerRecordKey(candidate.serverRecord);
            if (attemptedKeys.contains(dedupeKey)) {
                continue;
            }
            attemptedKeys.add(dedupeKey);
            try {
                return materializeWebSocketServerRecord(candidate);
            } catch (IOException error) {
                lastResolutionError = error;
                Log.w(
                    TAG,
                    "Skipping Steam websocket CM candidate source="
                        + candidate.candidateSourceDescription
                        + " because endpoint pre-resolution failed: "
                        + sanitizeSingleLine(error.getMessage()),
                    error
                );
            }
        }

        if (lastResolutionError != null) {
            throw lastResolutionError;
        }
        return null;
    }

    private PreparedServerRecord materializeWebSocketServerRecord(PreparedServerRecord candidate) throws IOException {
        ServerRecord serverRecord = candidate.serverRecord;
        if (serverRecord == null || serverRecord.getEndpoint() == null) {
            return candidate;
        }
        if (!serverRecord.getProtocolTypes().contains(ProtocolTypes.WEB_SOCKET)) {
            return candidate;
        }

        InetSocketAddress endpoint = serverRecord.getEndpoint();
        String host = sanitizeSingleLine(endpoint.getHostString());
        int port = endpoint.getPort();
        if (isBlank(host)) {
            return candidate;
        }

        InetAddress resolvedAddress = endpoint.getAddress();
        if (resolvedAddress != null) {
            String literalAddress = sanitizeSingleLine(resolvedAddress.getHostAddress());
            if (!isBlank(literalAddress)) {
                return new PreparedServerRecord(
                    ServerRecord.createWebSocketServer(formatHostPort(literalAddress, port)),
                    candidate.candidateSourceDescription + " (pre-resolved " + host + " -> " + literalAddress + ")"
                );
            }
            return candidate;
        }

        if (isIpLiteral(host)) {
            return candidate;
        }

        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses == null || addresses.length == 0) {
            throw new IOException("Failed to resolve Steam websocket CM hostname: " + host);
        }
        String preferredAddress = selectPreferredAddress(addresses);
        if (isBlank(preferredAddress)) {
            throw new IOException("Resolved Steam websocket CM hostname had no usable address: " + host);
        }

        Log.i(TAG, "Pre-resolved Steam websocket CM hostname " + host + " -> " + preferredAddress + '.');
        return new PreparedServerRecord(
            ServerRecord.createWebSocketServer(formatHostPort(preferredAddress, port)),
            candidate.candidateSourceDescription + " (pre-resolved " + host + " -> " + preferredAddress + ")"
        );
    }

    private void persistResolvedWebSocketEndpoint(ServerRecord serverRecord) {
        if (serverRecord == null || !serverRecord.getProtocolTypes().contains(ProtocolTypes.WEB_SOCKET)) {
            return;
        }
        InetSocketAddress endpoint = serverRecord.getEndpoint();
        if (endpoint == null) {
            return;
        }
        String address = endpoint.getHostString();
        if (endpoint.getAddress() != null && !isBlank(endpoint.getAddress().getHostAddress())) {
            address = endpoint.getAddress().getHostAddress();
        }
        address = formatHostPort(address, endpoint.getPort());
        try {
            writeTextFile(lastCmEndpointFile, address + "\n");
        } catch (IOException ignored) {
            // Best effort cache.
        }
    }

    private void maybeValidateSupportedChallenges(AuthSession authSession) {
        List<String> challengeDescriptions = new ArrayList<>();
        for (int index = 0; index < authSession.getAllowedConfirmations().size(); index++) {
            EAuthSessionGuardType type = authSession.getAllowedConfirmations().get(index).getConfirmationType();
            challengeDescriptions.add(describeGuardType(type));
            Log.i(TAG, "Steam auth challenge candidate index=" + index + " type=" + describeGuardType(type));
            if (type == EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode
                || type == EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
                || type == EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation
            ) {
                allowedChallengesDescription = challengeDescriptions.isEmpty()
                    ? "<none>"
                    : String.join(", ", challengeDescriptions);
                recordDiagnosticEvent("auth_challenges supported=" + allowedChallengesDescription);
                Log.i(TAG, "Steam auth allowed challenges: " + allowedChallengesDescription);
                return;
            }
        }
        allowedChallengesDescription = challengeDescriptions.isEmpty()
            ? "<none>"
            : String.join(", ", challengeDescriptions);
        recordDiagnosticEvent("auth_challenges unsupported=" + allowedChallengesDescription);
        Log.w(TAG, "Steam auth exposed no supported challenge flow. allowed=" + allowedChallengesDescription);
    }

    private void recordDiagnosticEvent(String message) {
        String line = Instant.now() + " | " + sanitizeSingleLine(message);
        synchronized (diagnosticEventsLock) {
            while (diagnosticEvents.size() >= DIAGNOSTIC_EVENT_LIMIT) {
                diagnosticEvents.removeFirst();
            }
            diagnosticEvents.addLast(line);
        }
        Log.i(TAG, "diag " + line);
    }

    private List<String> snapshotDiagnosticEvents() {
        synchronized (diagnosticEventsLock) {
            return new ArrayList<>(diagnosticEvents);
        }
    }

    private String buildDisconnectFailureMessage(String reason) {
        StringBuilder message = new StringBuilder()
            .append("Steam disconnected (")
            .append(reason)
            .append(") during ")
            .append(currentStage)
            .append('.');
        message.append(" JavaSteam 1.6.0 websocket transport has a watchdog for stalled auth flows.");
        return message.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> T waitForEither(
        CompletableFuture<T> future,
        CompletableFuture<?> abortFuture,
        long timeoutMs,
        String stage
    ) throws Exception {
        CompletableFuture<Object> combined = new CompletableFuture<>();
        future.whenComplete((value, error) -> {
            if (error != null) {
                combined.completeExceptionally(error);
                return;
            }
            combined.complete(value);
        });

        if (abortFuture != null) {
            abortFuture.whenComplete((value, error) -> {
                if (error != null) {
                    combined.completeExceptionally(error);
                    return;
                }
                combined.completeExceptionally(new IllegalStateException(stage + " was aborted before completion."));
            });
        }

        try {
            return (T) combined.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            Throwable cause = unwrapAsyncThrowable(error);
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(stage + " failed.", cause);
        } catch (TimeoutException error) {
            throw new TimeoutException(stage + " timed out after " + (timeoutMs / 1000L) + "s.");
        }
    }

    private static Throwable unwrapAsyncThrowable(Throwable error) {
        Throwable current = error;
        while (current instanceof ExecutionException && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String sanitizeSingleLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String describeThrowable(Throwable error) {
        if (error == null) {
            return "";
        }
        String message = sanitizeSingleLine(error.getMessage());
        if (message.isEmpty()) {
            return error.getClass().getName();
        }
        return error.getClass().getName() + ": " + message;
    }

    private static String describeAuthenticationResult(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AuthenticationException) {
                AuthenticationException authError = (AuthenticationException) current;
                EResult result = authError.getResult();
                return "authResult=" + (result == null ? "<none>" : result);
            }
            current = current.getCause();
        }
        return "authResult=<none>";
    }

    private static List<String> buildStackTraceLines(Throwable error, int limit) {
        List<String> lines = new ArrayList<>();
        if (error == null || limit <= 0) {
            return lines;
        }
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        error.printStackTrace(printWriter);
        printWriter.flush();
        for (String line : stringWriter.toString().split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            lines.add(trimmed);
            if (lines.size() >= limit) {
                break;
            }
        }
        return lines;
    }

    private static String describeServerRecord(ServerRecord serverRecord) {
        if (serverRecord == null || serverRecord.getEndpoint() == null) {
            return "<not resolved>";
        }
        return serverRecord.getEndpoint().getHostString()
            + ':'
            + serverRecord.getEndpoint().getPort()
            + " ["
            + describeProtocolTypes(serverRecord.getProtocolTypes())
            + ']';
    }

    private static String describeProtocolTypes(EnumSet<ProtocolTypes> protocolTypes) {
        if (protocolTypes == null || protocolTypes.isEmpty()) {
            return "<none>";
        }
        List<String> values = new ArrayList<>();
        for (ProtocolTypes type : protocolTypes) {
            values.add(String.valueOf(type));
        }
        return String.join(", ", values);
    }

    private static String describeGuardType(EAuthSessionGuardType type) {
        if (type == null) {
            return "<unknown>";
        }
        switch (type) {
            case k_EAuthSessionGuardType_DeviceCode:
                return "device_code";
            case k_EAuthSessionGuardType_EmailCode:
                return "email_code";
            case k_EAuthSessionGuardType_DeviceConfirmation:
                return "device_confirmation";
            default:
                return String.valueOf(type);
        }
    }

    private static String joinRemotePath(String prefix, String filename) {
        if (isBlank(prefix)) {
            return filename;
        }
        if (isBlank(filename)) {
            return prefix;
        }
        char separator = prefix.indexOf('\\') >= 0 && prefix.indexOf('/') < 0 ? '\\' : '/';
        if (prefix.endsWith("/") || prefix.endsWith("\\")) {
            return prefix + filename;
        }
        return prefix + separator + filename;
    }

    private static String buildServerRecordKey(ServerRecord serverRecord) {
        if (serverRecord == null || serverRecord.getEndpoint() == null) {
            return "<null>";
        }
        return sanitizeSingleLine(serverRecord.getEndpoint().getHostString()).toLowerCase(Locale.ROOT)
            + ':'
            + serverRecord.getEndpoint().getPort()
            + '|'
            + describeProtocolTypes(serverRecord.getProtocolTypes());
    }

    private static String selectPreferredAddress(InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        for (InetAddress address : addresses) {
            String literal = address == null ? "" : sanitizeSingleLine(address.getHostAddress());
            if (!isBlank(literal) && literal.indexOf(':') < 0) {
                return literal;
            }
        }
        for (InetAddress address : addresses) {
            String literal = address == null ? "" : sanitizeSingleLine(address.getHostAddress());
            if (!isBlank(literal)) {
                return literal;
            }
        }
        return "";
    }

    private static String formatHostPort(String host, int port) {
        String sanitizedHost = sanitizeSingleLine(host);
        if (sanitizedHost.indexOf(':') >= 0 && !sanitizedHost.startsWith("[") && !sanitizedHost.endsWith("]")) {
            sanitizedHost = '[' + sanitizedHost + ']';
        }
        return sanitizedHost + ':' + port;
    }

    private static boolean isIpLiteral(String host) {
        String value = sanitizeSingleLine(host);
        if (value.isEmpty()) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if ((c < '0' || c > '9') && c != '.') {
                return false;
            }
        }
        return true;
    }

    private boolean commitClientFileUpload(
        boolean transferSucceeded,
        int appId,
        byte[] sha1Bytes,
        String remotePath
    ) throws Exception {
        SteammessagesCloudSteamclient.CCloud_ClientCommitFileUpload_Request request =
            SteammessagesCloudSteamclient.CCloud_ClientCommitFileUpload_Request.newBuilder()
                .setTransferSucceeded(transferSucceeded)
                .setAppid(appId)
                .setFileSha(ByteString.copyFrom(sha1Bytes))
                .setFilename(remotePath)
                .build();
        ServiceMethodResponse<SteammessagesCloudSteamclient.CCloud_ClientCommitFileUpload_Response.Builder> response = null;
        for (int attempt = 1; attempt <= TRANSIENT_RPC_MAX_ATTEMPTS; attempt++) {
            response = waitForServiceJob(
                cloudService.clientCommitFileUpload(request),
                RPC_TIMEOUT_MS,
                "ClientCommitFileUpload"
            );
            EResult result = response.getResult();
            if (result == EResult.OK) {
                return response.getBody().getFileCommitted();
            }
            if (!isRetryableSteamCloudResult(result) || attempt >= TRANSIENT_RPC_MAX_ATTEMPTS) {
                ensureServiceResult(response, "ClientCommitFileUpload");
            }
            sleepBeforeTransientRetry("ClientCommitFileUpload", result, remotePath, attempt);
        }

        ensureServiceResult(response, "ClientCommitFileUpload");
        throw new IllegalStateException("ClientCommitFileUpload failed without a response result.");
    }

    private <T extends GeneratedMessage.Builder<T>> ServiceMethodResponse<T> waitForServiceJob(
        AsyncJobSingle<ServiceMethodResponse<T>> job,
        long timeoutMs,
        String stage
    ) throws Exception {
        return waitForStage(job.toFuture(), timeoutMs, stage);
    }

    private <T extends GeneratedMessage.Builder<T>> ServiceMethodResponse<T> waitForServiceJobWithRetries(
        Supplier<AsyncJobSingle<ServiceMethodResponse<T>>> jobSupplier,
        long timeoutMs,
        String stage
    ) throws Exception {
        ServiceMethodResponse<T> response = null;
        for (int attempt = 1; attempt <= TRANSIENT_RPC_MAX_ATTEMPTS; attempt++) {
            try {
                response = waitForServiceJob(jobSupplier.get(), timeoutMs, stage);
            } catch (Exception error) {
                if (!isRetryableSteamCloudException(error) || attempt >= TRANSIENT_RPC_MAX_ATTEMPTS) {
                    throw error;
                }
                sleepBeforeTransientRetry(stage, error, attempt);
                continue;
            }

            EResult result = response.getResult();
            if (result == EResult.OK) {
                return response;
            }
            if (!isRetryableSteamCloudResult(result) || attempt >= TRANSIENT_RPC_MAX_ATTEMPTS) {
                return response;
            }
            sleepBeforeTransientRetry(stage, result, "", attempt);
        }

        ensureServiceResult(response, stage);
        throw new IllegalStateException(stage + " failed without a response result.");
    }

    private ServiceMethodResponse<SteammessagesCloudSteamclient.CCloud_ClientBeginFileUpload_Response.Builder>
    clientBeginFileUploadWithRetries(
        SteammessagesCloudSteamclient.CCloud_ClientBeginFileUpload_Request request,
        String remotePath
    ) throws Exception {
        ServiceMethodResponse<SteammessagesCloudSteamclient.CCloud_ClientBeginFileUpload_Response.Builder> response = null;
        for (int attempt = 1; attempt <= BEGIN_FILE_UPLOAD_MAX_ATTEMPTS; attempt++) {
            response = waitForServiceJob(
                cloudService.clientBeginFileUpload(request),
                RPC_TIMEOUT_MS,
                "ClientBeginFileUpload"
            );
            EResult result = response.getResult();
            SteammessagesCloudSteamclient.CCloud_ClientBeginFileUpload_Response.Builder body = response.getBody();
            int blockRequestCount = body == null ? 0 : body.getBlockRequestsCount();
            recordDiagnosticEvent(
                "client_begin_file_upload result remotePath="
                    + remotePath
                    + " attempt="
                    + attempt
                    + "/"
                    + BEGIN_FILE_UPLOAD_MAX_ATTEMPTS
                    + " result="
                    + result
                    + " blockRequests="
                    + blockRequestCount
            );
            if (result == EResult.OK) {
                return response;
            }
            if (result == EResult.DuplicateRequest) {
                if (blockRequestCount > 0) {
                    recordDiagnosticEvent(
                        "client_begin_file_upload accepting_duplicate_with_blocks remotePath="
                            + remotePath
                            + " blockRequests="
                            + blockRequestCount
                    );
                } else {
                    recordDiagnosticEvent(
                        "client_begin_file_upload accepting_duplicate_without_blocks remotePath="
                            + remotePath
                    );
                }
                return response;
            }
            if (!isRetryableBeginFileUploadResult(result) || attempt >= BEGIN_FILE_UPLOAD_MAX_ATTEMPTS) {
                ensureServiceResult(response, "ClientBeginFileUpload");
            }

            long delayMs = beginFileUploadRetryDelayMs(result, attempt);
            Log.w(
                TAG,
                "ClientBeginFileUpload returned "
                    + result
                    + " for "
                    + remotePath
                    + "; retrying attempt "
                    + (attempt + 1)
                    + "/"
                    + BEGIN_FILE_UPLOAD_MAX_ATTEMPTS
                    + " after "
                    + delayMs
                    + "ms."
                    + beginFileUploadRetryHint(result)
            );
            sleepBeforeRetry(delayMs);
        }

        ensureServiceResult(response, "ClientBeginFileUpload");
        throw new IllegalStateException("ClientBeginFileUpload failed without a response result.");
    }

    private static void sleepBeforeTransientRetry(
        String stage,
        EResult result,
        String remotePath,
        int attempt
    ) throws InterruptedException {
        long delayMs = transientRetryDelayMs(attempt);
        Log.w(
            TAG,
            stage
                + " returned "
                + result
                + (isBlank(remotePath) ? "" : " for " + remotePath)
                + "; retrying attempt "
                + (attempt + 1)
                + "/"
                + TRANSIENT_RPC_MAX_ATTEMPTS
                + " after "
                + delayMs
                + "ms."
        );
        sleepBeforeRetry(delayMs);
    }

    private static void sleepBeforeTransientRetry(
        String stage,
        Exception error,
        int attempt
    ) throws InterruptedException {
        long delayMs = transientRetryDelayMs(attempt);
        Log.w(
            TAG,
            stage
                + " failed transiently: "
                + sanitizeSingleLine(error.getMessage())
                + "; retrying attempt "
                + (attempt + 1)
                + "/"
                + TRANSIENT_RPC_MAX_ATTEMPTS
                + " after "
                + delayMs
                + "ms.",
            error
        );
        sleepBeforeRetry(delayMs);
    }

    private static long transientRetryDelayMs(int attempt) {
        return TRANSIENT_RPC_RETRY_DELAYS_MS[
            Math.min(attempt - 1, TRANSIENT_RPC_RETRY_DELAYS_MS.length - 1)
        ];
    }

    private static boolean isRetryableSteamCloudResult(EResult result) {
        return result == EResult.Busy
            || result == EResult.ServiceUnavailable
            || result == EResult.Timeout
            || result == EResult.RemoteCallFailed;
    }

    private static boolean isRetryableSteamCloudException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("busy")
                    || normalized.contains("timeout")
                    || normalized.contains("timed out")
                    || normalized.contains("serviceunavailable")
                    || normalized.contains("service unavailable")
                    || normalized.contains("remotecallfailed")
                    || normalized.contains("remote call failed")
                ) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isRetryableDownloadException(Throwable error) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return false;
            }
            if (current instanceof HttpStatusIOException) {
                return isRetryableHttpStatus(((HttpStatusIOException) current).statusCode);
            }
            if (current instanceof HttpTransferIOException || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isRetryableHttpStatus(int statusCode) {
        return statusCode == 408
            || statusCode == 429
            || (statusCode >= 500 && statusCode <= 599);
    }

    private static long downloadRetryDelayMs(int attempt) {
        return DOWNLOAD_RETRY_DELAYS_MS[Math.min(attempt - 1, DOWNLOAD_RETRY_DELAYS_MS.length - 1)];
    }

    private static boolean isRetryableBeginFileUploadResult(EResult result) {
        return isRetryableSteamCloudResult(result)
            || result == EResult.TooManyPending
            || result == EResult.DuplicateRequest;
    }

    private static long beginFileUploadRetryDelayMs(EResult result, int attempt) {
        long[] delays;
        if (result == EResult.TooManyPending) {
            delays = BEGIN_FILE_UPLOAD_PENDING_RETRY_DELAYS_MS;
        } else if (result == EResult.DuplicateRequest) {
            delays = BEGIN_FILE_UPLOAD_DUPLICATE_RETRY_DELAYS_MS;
        } else {
            delays = BEGIN_FILE_UPLOAD_RETRY_DELAYS_MS;
        }
        return delays[Math.min(attempt - 1, delays.length - 1)];
    }

    private static String beginFileUploadRetryHint(EResult result) {
        if (result == EResult.TooManyPending) {
            return " Steam may still be clearing an earlier unfinished upload batch.";
        }
        if (result == EResult.DuplicateRequest) {
            return " Steam reports a duplicate upload request.";
        }
        return "";
    }

    private static void sleepBeforeRetry(long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
    }

    private static <T extends GeneratedMessage.Builder<T>> void ensureServiceResult(
        ServiceMethodResponse<T> response,
        String operation
    ) {
        if (response.getResult() != EResult.OK) {
            throw new IllegalStateException(operation + " failed: " + response.getResult());
        }
    }

    private static void ensureValidUploadBatchId(long batchId, EResult result) {
        if (batchId != 0L) {
            return;
        }
        throw new IllegalStateException(
            "BeginAppUploadBatch returned invalid batchId=0 (result=" + result
                + "). Steam may still be clearing an earlier unfinished upload batch."
        );
    }

    private static byte[] sha1Bytes(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (Exception error) {
            throw new IOException("Failed to initialize SHA-1 digest.", error);
        }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException error) {
            throw new IOException("Failed to read Steam Cloud upload source file.", error);
        }
        return digest.digest();
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xFF));
        }
        return builder.toString();
    }

    private static String buildUploadMachineName() {
        String manufacturer = sanitizeSingleLine(Build.MANUFACTURER);
        String model = sanitizeSingleLine(Build.MODEL);
        String base = manufacturer.isEmpty() ? model : manufacturer + ' ' + model;
        if (base.trim().isEmpty()) {
            return "Android (Steam Cloud)";
        }
        return base.trim() + " (Steam Cloud)";
    }

    private static byte[] maybeUnzip(byte[] bytes, String remotePath) throws IOException {
        if (bytes.length < 4
            || bytes[0] != 0x50
            || bytes[1] != 0x4B
            || bytes[2] != 0x03
            || bytes[3] != 0x04
        ) {
            return bytes;
        }

        try (ZipInputStream zipStream = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry = zipStream.getNextEntry();
            if (entry == null) {
                throw new IOException("Downloaded ZIP for " + remotePath + " had no entries.");
            }
            return readAll(zipStream);
        }
    }

    private static long elapsedMillis(long startedAtNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs);
    }

    private static byte[] readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            int read = inputStream.read(buffer);
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validateDownloadedBytes(
        byte[] rawBytes,
        long expectedRawSize,
        String expectedSha1,
        String remotePath
    ) throws IOException {
        if (expectedRawSize >= 0L && rawBytes.length != expectedRawSize) {
            throw new IOException(
                "Steam Cloud download size mismatch for "
                    + remotePath
                    + ": expectedRawSize="
                    + expectedRawSize
                    + " actualRawSize="
                    + rawBytes.length
            );
        }
        if (isBlank(expectedSha1)) {
            return;
        }
        String actualSha1 = sha1Hex(rawBytes);
        if (!actualSha1.equalsIgnoreCase(expectedSha1.trim())) {
            throw new IOException(
                "Steam Cloud download SHA-1 mismatch for "
                    + remotePath
                    + ": expectedSha1="
                    + expectedSha1.trim()
                    + " actualSha1="
                    + actualSha1
            );
        }
    }

    private static String sha1Hex(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(bytes);
            return bytesToHex(digest.digest());
        } catch (Exception error) {
            throw new IOException("Failed to calculate Steam Cloud download SHA-1.", error);
        }
    }

    private static void applyProxySystemProperties() {
        System.setProperty("java.net.useSystemProxies", "true");
        clearProxySystemProperty("http.proxyHost");
        clearProxySystemProperty("http.proxyPort");
        clearProxySystemProperty("https.proxyHost");
        clearProxySystemProperty("https.proxyPort");
        clearProxySystemProperty("socksProxyHost");
        clearProxySystemProperty("socksProxyPort");
    }

    private static void clearProxySystemProperty(String key) {
        System.clearProperty(key);
    }

    private static String readOptionalTextFile(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void ensureDirectoryExists(File directory, String description) throws IOException {
        if (directory == null || directory.isDirectory()) {
            return;
        }
        if (directory.mkdirs() || directory.isDirectory()) {
            return;
        }
        throw new IOException("Failed to create " + description + ": " + directory.getAbsolutePath());
    }

    private static void writeTextFile(File target, String content) throws IOException {
        File parent = target.getParentFile();
        ensureDirectoryExists(parent, "parent directory for " + target.getAbsolutePath());
        File tempFile = new File(parent, "." + target.getName() + "." + System.nanoTime() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tempFile)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Failed to replace existing file: " + target.getAbsolutePath());
        }
        if (!tempFile.renameTo(target)) {
            throw new IOException("Failed to move temp file into place: " + target.getAbsolutePath());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String buildCredentialsInputDiagnostics(String username, String password, String guardData) {
        return "usernamePresent="
            + (username != null)
            + " usernameLength="
            + safeLength(username)
            + " usernameTrimmedChanged="
            + trimmedChanged(username)
            + " usernameLeadingWhitespace="
            + hasLeadingWhitespace(username)
            + " usernameTrailingWhitespace="
            + hasTrailingWhitespace(username)
            + " usernameNonAsciiCount="
            + countNonAscii(username)
            + " passwordPresent="
            + (password != null)
            + " passwordLength="
            + safeLength(password)
            + " passwordLeadingWhitespace="
            + hasLeadingWhitespace(password)
            + " passwordTrailingWhitespace="
            + hasTrailingWhitespace(password)
            + " passwordNonAsciiCount="
            + countNonAscii(password)
            + " guardDataConfigured="
            + !isBlank(guardData)
            + " guardDataLength="
            + safeLength(guardData);
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static boolean trimmedChanged(String value) {
        return value != null && !value.equals(value.trim());
    }

    private static boolean hasLeadingWhitespace(String value) {
        return value != null && !value.isEmpty() && Character.isWhitespace(value.charAt(0));
    }

    private static boolean hasTrailingWhitespace(String value) {
        return value != null && !value.isEmpty() && Character.isWhitespace(value.charAt(value.length() - 1));
    }

    private static int countNonAscii(String value) {
        if (value == null) {
            return 0;
        }
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7F) {
                count++;
            }
        }
        return count;
    }

    private static <T> T requireNonNull(T value, String label) {
        return Objects.requireNonNull(value, label + " was not available.");
    }

    public interface AuthPrompt {
        CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect);

        CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect);

        CompletableFuture<Boolean> acceptDeviceConfirmation();
    }

    public static final class AuthMaterial {
        private final String accountName;
        private final String refreshToken;
        private final String guardData;

        private AuthMaterial(String accountName, String refreshToken, String guardData) {
            this.accountName = accountName;
            this.refreshToken = refreshToken;
            this.guardData = guardData;
        }

        public String getAccountName() {
            return accountName;
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public String getGuardData() {
            return guardData;
        }
    }

    public static final class DiagnosticsSnapshot {
        private final String currentStage;
        private final String protocolTypesDescription;
        private final boolean connectedCallbackReceived;
        private final String loggedOnResultDescription;
        private final String disconnectedDescription;
        private final String resolvedServerDescription;
        private final String candidateSourceDescription;
        private final String allowedChallengesDescription;
        private final String lastAuthPromptDescription;
        private final boolean guardDataConfigured;
        private final boolean guardDataUpdated;
        private final String javaSteamLastLogDescription;
        private final String javaSteamLastErrorDescription;
        private final List<String> javaSteamLogTailLines;
        private final List<String> javaSteamErrorStackLines;
        private final List<String> diagnosticEventLines;
        private final String wattAccelerationDescription;
        private final String loggedOnCallbackSteamId64;
        private final String steamClientSteamId64;
        private final long cmServerSelectionMs;
        private final long cmConnectWaitMs;

        private DiagnosticsSnapshot(
            String currentStage,
            String protocolTypesDescription,
            boolean connectedCallbackReceived,
            String loggedOnResultDescription,
            String disconnectedDescription,
            String resolvedServerDescription,
            String candidateSourceDescription,
            String allowedChallengesDescription,
            String lastAuthPromptDescription,
            boolean guardDataConfigured,
            boolean guardDataUpdated,
            String javaSteamLastLogDescription,
            String javaSteamLastErrorDescription,
            List<String> javaSteamLogTailLines,
            List<String> javaSteamErrorStackLines,
            List<String> diagnosticEventLines,
            String wattAccelerationDescription,
            String loggedOnCallbackSteamId64,
            String steamClientSteamId64,
            long cmServerSelectionMs,
            long cmConnectWaitMs
        ) {
            this.currentStage = currentStage;
            this.protocolTypesDescription = protocolTypesDescription;
            this.connectedCallbackReceived = connectedCallbackReceived;
            this.loggedOnResultDescription = loggedOnResultDescription;
            this.disconnectedDescription = disconnectedDescription;
            this.resolvedServerDescription = resolvedServerDescription;
            this.candidateSourceDescription = candidateSourceDescription;
            this.allowedChallengesDescription = allowedChallengesDescription;
            this.lastAuthPromptDescription = lastAuthPromptDescription;
            this.guardDataConfigured = guardDataConfigured;
            this.guardDataUpdated = guardDataUpdated;
            this.javaSteamLastLogDescription = javaSteamLastLogDescription;
            this.javaSteamLastErrorDescription = javaSteamLastErrorDescription;
            this.javaSteamLogTailLines = Collections.unmodifiableList(new ArrayList<>(javaSteamLogTailLines));
            this.javaSteamErrorStackLines = Collections.unmodifiableList(new ArrayList<>(javaSteamErrorStackLines));
            this.diagnosticEventLines = Collections.unmodifiableList(new ArrayList<>(diagnosticEventLines));
            this.wattAccelerationDescription = wattAccelerationDescription;
            this.loggedOnCallbackSteamId64 = loggedOnCallbackSteamId64;
            this.steamClientSteamId64 = steamClientSteamId64;
            this.cmServerSelectionMs = cmServerSelectionMs;
            this.cmConnectWaitMs = cmConnectWaitMs;
        }

        public String getCurrentStage() {
            return currentStage;
        }

        public String getProtocolTypesDescription() {
            return protocolTypesDescription;
        }

        public boolean getConnectedCallbackReceived() {
            return connectedCallbackReceived;
        }

        public String getLoggedOnResultDescription() {
            return loggedOnResultDescription;
        }

        public String getDisconnectedDescription() {
            return disconnectedDescription;
        }

        public String getResolvedServerDescription() {
            return resolvedServerDescription;
        }

        public String getCandidateSourceDescription() {
            return candidateSourceDescription;
        }

        public String getAllowedChallengesDescription() {
            return allowedChallengesDescription;
        }

        public String getLastAuthPromptDescription() {
            return lastAuthPromptDescription;
        }

        public boolean getGuardDataConfigured() {
            return guardDataConfigured;
        }

        public boolean getGuardDataUpdated() {
            return guardDataUpdated;
        }

        public String getJavaSteamLastLogDescription() {
            return javaSteamLastLogDescription;
        }

        public String getJavaSteamLastErrorDescription() {
            return javaSteamLastErrorDescription;
        }

        public List<String> getJavaSteamLogTailLines() {
            return javaSteamLogTailLines;
        }

        public List<String> getJavaSteamErrorStackLines() {
            return javaSteamErrorStackLines;
        }

        public List<String> getDiagnosticEventLines() {
            return diagnosticEventLines;
        }

        public String getWattAccelerationDescription() {
            return wattAccelerationDescription;
        }

        public String getLoggedOnCallbackSteamId64() {
            return loggedOnCallbackSteamId64;
        }

        public String getSteamClientSteamId64() {
            return steamClientSteamId64;
        }

        public long getCmServerSelectionMs() {
            return cmServerSelectionMs;
        }

        public long getCmConnectWaitMs() {
            return cmConnectWaitMs;
        }
    }

    public static final class RemoteFileRecord {
        private final String remotePath;
        private final long rawFileSize;
        private final long timestampMs;
        private final String machineName;
        private final String persistState;
        private final String sha1;

        public RemoteFileRecord(
            String remotePath,
            long rawFileSize,
            long timestampMs,
            String machineName,
            String persistState,
            String sha1
        ) {
            this.remotePath = remotePath;
            this.rawFileSize = rawFileSize;
            this.timestampMs = timestampMs;
            this.machineName = machineName;
            this.persistState = persistState;
            this.sha1 = sha1 == null ? "" : sha1;
        }

        public String getRemotePath() {
            return remotePath;
        }

        public long getRawFileSize() {
            return rawFileSize;
        }

        public long getTimestampMs() {
            return timestampMs;
        }

        public String getMachineName() {
            return machineName;
        }

        public String getPersistState() {
            return persistState;
        }

        public String getSha1() {
            return sha1;
        }
    }

    public static final class DownloadResult {
        private final String remotePath;
        private final String outputPath;
        private final long compressedBytes;
        private final long rawBytes;
        private final boolean decompressed;
        private final long rpcMs;
        private final long httpMs;
        private final long unzipMs;
        private final long writeMs;
        private final long totalMs;

        private DownloadResult(
            String remotePath,
            String outputPath,
            long compressedBytes,
            long rawBytes,
            boolean decompressed,
            long rpcMs,
            long httpMs,
            long unzipMs,
            long writeMs,
            long totalMs
        ) {
            this.remotePath = remotePath;
            this.outputPath = outputPath;
            this.compressedBytes = compressedBytes;
            this.rawBytes = rawBytes;
            this.decompressed = decompressed;
            this.rpcMs = rpcMs;
            this.httpMs = httpMs;
            this.unzipMs = unzipMs;
            this.writeMs = writeMs;
            this.totalMs = totalMs;
        }

        public String getRemotePath() {
            return remotePath;
        }

        public String getOutputPath() {
            return outputPath;
        }

        public long getCompressedBytes() {
            return compressedBytes;
        }

        public long getRawBytes() {
            return rawBytes;
        }

        public boolean getDecompressed() {
            return decompressed;
        }

        public long getRpcMs() {
            return rpcMs;
        }

        public long getHttpMs() {
            return httpMs;
        }

        public long getUnzipMs() {
            return unzipMs;
        }

        public long getWriteMs() {
            return writeMs;
        }

        public long getTotalMs() {
            return totalMs;
        }
    }

    private static final class HttpStatusIOException extends IOException {
        private final int statusCode;

        private HttpStatusIOException(int statusCode, String operation, String remotePath) {
            super("HTTP " + statusCode + " when " + operation + " " + remotePath);
            this.statusCode = statusCode;
        }
    }

    private static final class HttpTransferIOException extends IOException {
        private HttpTransferIOException(String message, IOException cause) {
            super(message, cause);
        }
    }

    public static final class UploadBatch {
        private final long batchId;
        private final long appChangeNumber;

        private UploadBatch(long batchId, long appChangeNumber) {
            this.batchId = batchId;
            this.appChangeNumber = appChangeNumber;
        }

        public long getBatchId() {
            return batchId;
        }

        public long getAppChangeNumber() {
            return appChangeNumber;
        }
    }

    public static final class UploadedFile {
        private final String remotePath;
        private final long fileSize;
        private final String sha1Hex;

        private UploadedFile(String remotePath, long fileSize, String sha1Hex) {
            this.remotePath = remotePath;
            this.fileSize = fileSize;
            this.sha1Hex = sha1Hex;
        }

        public String getRemotePath() {
            return remotePath;
        }

        public long getFileSize() {
            return fileSize;
        }

        public String getSha1Hex() {
            return sha1Hex;
        }
    }

    private final class PromptAuthenticator implements IAuthenticator {
        private final AuthPrompt prompt;

        private PromptAuthenticator(AuthPrompt prompt) {
            this.prompt = prompt;
        }

        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            lastAuthPromptDescription = "device_code"
                + (previousCodeWasIncorrect ? " (retry)" : " (initial)");
            recordDiagnosticEvent("auth_prompt device_code retry=" + previousCodeWasIncorrect);
            Log.i(TAG, "Steam auth requested device code. retry=" + previousCodeWasIncorrect);
            return prompt.getDeviceCode(previousCodeWasIncorrect);
        }

        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            lastAuthPromptDescription = "email_code"
                + (isBlank(email) ? "" : " (" + email.trim() + ")")
                + (previousCodeWasIncorrect ? " (retry)" : " (initial)");
            recordDiagnosticEvent(
                "auth_prompt email_code hint="
                    + (isBlank(email) ? "<none>" : email.trim())
                    + " retry="
                    + previousCodeWasIncorrect
            );
            Log.i(
                TAG,
                "Steam auth requested email code. hint="
                    + (isBlank(email) ? "<none>" : email.trim())
                    + " retry="
                    + previousCodeWasIncorrect
            );
            return prompt.getEmailCode(email, previousCodeWasIncorrect);
        }

        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            lastAuthPromptDescription = "device_confirmation";
            recordDiagnosticEvent("auth_prompt device_confirmation");
            Log.i(TAG, "Steam auth requested mobile device confirmation.");
            return prompt.acceptDeviceConfirmation();
        }
    }

    private static final class JavaSteamLogCollector implements LogListener {
        private static final int MAX_ENTRIES = 48;

        private final Object lock = new Object();
        private final ArrayDeque<JavaSteamLogEntry> entries = new ArrayDeque<>();
        private JavaSteamLogEntry lastErrorEntry;

        @Override
        public void onLog(Class<?> clazz, String message, Throwable error) {
            record("DEBUG", clazz, message, error);
        }

        @Override
        public void onError(Class<?> clazz, String message, Throwable error) {
            record("ERROR", clazz, message, error);
        }

        private void record(String level, Class<?> clazz, String message, Throwable error) {
            JavaSteamLogEntry entry = new JavaSteamLogEntry(
                level,
                clazz == null ? "<unknown>" : clazz.getName(),
                sanitizeSingleLine(message),
                error,
                buildStackTraceLines(error, JAVA_STEAM_STACKTRACE_LINE_LIMIT)
            );

            synchronized (lock) {
                while (entries.size() >= MAX_ENTRIES) {
                    entries.removeFirst();
                }
                entries.addLast(entry);
                if ("ERROR".equals(level) || error != null) {
                    lastErrorEntry = entry;
                }
            }

            if ("ERROR".equals(level) || error != null) {
                Log.e(TAG, "JavaSteam [" + level + "] " + entry.describe(), error);
            } else {
                Log.d(TAG, "JavaSteam [" + level + "] " + entry.describe());
            }
        }

        private String describeLastLog() {
            synchronized (lock) {
                return entries.isEmpty() ? "<none>" : entries.getLast().describe();
            }
        }

        private String describeLastError() {
            synchronized (lock) {
                return lastErrorEntry == null ? "<none>" : lastErrorEntry.describe();
            }
        }

        private List<String> snapshotTailLines() {
            synchronized (lock) {
                if (entries.isEmpty()) {
                    return Collections.emptyList();
                }
                List<String> lines = new ArrayList<>();
                int index = 0;
                int start = Math.max(0, entries.size() - JAVA_STEAM_LOG_TAIL_LIMIT);
                for (JavaSteamLogEntry entry : entries) {
                    if (index++ < start) {
                        continue;
                    }
                    lines.add(entry.describe());
                }
                return lines;
            }
        }

        private List<String> snapshotErrorStackLines() {
            synchronized (lock) {
                if (lastErrorEntry == null || lastErrorEntry.stackTraceLines.isEmpty()) {
                    return Collections.emptyList();
                }
                return new ArrayList<>(lastErrorEntry.stackTraceLines);
            }
        }
    }

    private static final class JavaSteamLogEntry {
        private final String level;
        private final String sourceClass;
        private final String message;
        private final String throwableSummary;
        private final List<String> stackTraceLines;

        private JavaSteamLogEntry(
            String level,
            String sourceClass,
            String message,
            Throwable throwable,
            List<String> stackTraceLines
        ) {
            this.level = level;
            this.sourceClass = sourceClass;
            this.message = message == null ? "" : message;
            this.throwableSummary = describeThrowable(throwable);
            this.stackTraceLines = stackTraceLines;
        }

        private String describe() {
            StringBuilder builder = new StringBuilder();
            builder.append(level).append(' ').append(sourceClass);
            if (!message.isEmpty()) {
                builder.append(" - ").append(message);
            }
            if (!throwableSummary.isEmpty()) {
                builder.append(" | ").append(throwableSummary);
            }
            return builder.toString();
        }
    }
}
