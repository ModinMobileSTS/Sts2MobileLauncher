package com.godot.game.steam.cloud;

import android.content.Context;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.steam.discovery.FileServerListProvider;
import in.dragonbra.javasteam.steam.discovery.ServerRecord;
import in.dragonbra.javasteam.steam.discovery.SmartCMServerList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SteamCloudEndpointTest {
    private static final String NAT64_ADDRESS = "64:ff9b::c000:201";
    private final Map<String, String> proxyProperties = new HashMap<>();
    private String defaultServer;
    private Sts2SteamCloudClient client;
    private FileServerListProvider serverList;

    @Before public void setUp() {
        for (String key : List.of("java.net.useSystemProxies", "http.proxyHost", "http.proxyPort",
                "https.proxyHost", "https.proxyPort", "socksProxyHost", "socksProxyPort")) {
            proxyProperties.put(key, System.getProperty(key));
        }
        defaultServer = SmartCMServerList.getDefaultServerWebSocket();
        // Literal endpoints and a fresh on-disk CM list keep these tests offline.
        SmartCMServerList.setDefaultServerWebSocket("127.0.0.1:443");
        Context context = RuntimeEnvironment.getApplication();
        client = new Sts2SteamCloudClient(context);
        serverList = new FileServerListProvider(new File(context.getFilesDir(),
            "steam/steam-cloud/steam-cm-server-list.bin"));
    }

    @After public void tearDown() {
        if (client != null) {
            client.close();
        }
        SmartCMServerList.setDefaultServerWebSocket(defaultServer);
        proxyProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test public void resolvedNat64CandidateRetainsIpv6AddressAndPort() throws Exception {
        serverList.updateServerList(List.of(ServerRecord.createServer(NAT64_ADDRESS, 27020, ProtocolTypes.WEB_SOCKET)));
        assertEndpoint(select(), NAT64_ADDRESS, 27020);
    }

    @Test public void persistedIpv6EndpointCanBeSelectedAfterRestart() throws Exception {
        persist(ServerRecord.createServer(NAT64_ADDRESS, 27021, ProtocolTypes.WEB_SOCKET));
        client.close();
        client = new Sts2SteamCloudClient(RuntimeEnvironment.getApplication());
        // A TCP-only list has no WebSocket candidate, so selection must use the saved endpoint.
        serverList.updateServerList(List.of(ServerRecord.createServer("127.0.0.1", 27017, ProtocolTypes.TCP)));
        assertEndpoint(select(), NAT64_ADDRESS, 27021);
    }

    @Test public void ipv6CacheDoesNotBreakOrOverrideFreshIpv4Candidate() throws Exception {
        persist(ServerRecord.createServer(NAT64_ADDRESS, 27021, ProtocolTypes.WEB_SOCKET));
        serverList.updateServerList(List.of(ServerRecord.createServer("192.0.2.2", 27022, ProtocolTypes.WEB_SOCKET)));
        assertEndpoint(select(), "192.0.2.2", 27022);
    }

    @Test public void hostnameOnlyFallbackKeepsDefaultTlsPort() throws Exception {
        SmartCMServerList.setDefaultServerWebSocket("127.0.0.1");
        serverList.updateServerList(List.of(ServerRecord.createServer("127.0.0.1", 27017, ProtocolTypes.TCP)));
        assertEndpoint(select(), "127.0.0.1", 443);
    }

    private ServerRecord select() throws Exception {
        Object candidate = invoke("selectWebSocketServerRecord", new Class<?>[0]);
        Field record = candidate.getClass().getDeclaredField("serverRecord");
        record.setAccessible(true);
        return (ServerRecord) record.get(candidate);
    }

    private void persist(ServerRecord record) throws Exception {
        invoke("persistResolvedWebSocketEndpoint", new Class<?>[]{ServerRecord.class}, record);
    }

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = Sts2SteamCloudClient.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(client, args);
        } catch (InvocationTargetException error) {
            throw (Exception) error.getCause();
        }
    }

    private static void assertEndpoint(ServerRecord record, String address, int port) throws Exception {
        assertEquals(InetAddress.getByName(address), record.getEndpoint().getAddress());
        assertEquals(port, record.getPort());
        assertEquals(java.util.EnumSet.of(ProtocolTypes.WEB_SOCKET), record.getProtocolTypes());
    }
}
