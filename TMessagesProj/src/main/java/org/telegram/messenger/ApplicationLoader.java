/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import android.util.Log;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import org.json.JSONObject;
import org.json.JSONArray;

import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import link.yggdrasil.yggstack.android.data.PublicPeerInfo;
import link.yggdrasil.yggstack.android.service.PeerFetcherService;

import link.yggdrasil.yggstack.android.service.PeerPingerService;
import link.yggdrasil.yggstack.mobile.Mobile;
import link.yggdrasil.yggstack.mobile.Yggstack;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Adapters.DrawerLayoutAdapter;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.IUpdateButton;
import org.telegram.ui.IUpdateLayout;
import org.telegram.ui.LauncherIconController;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ApplicationLoader extends Application {

    public static ApplicationLoader applicationLoaderInstance;

    @SuppressLint("StaticFieldLeak")
    public static volatile Context applicationContext;
    public static volatile NetworkInfo currentNetworkInfo;
    public static volatile Handler applicationHandler;
    public static boolean isPeersReiniting = false;
    public static volatile Yggstack yggInstance = null;
    public static volatile List<PublicPeerInfo> lastPingedPeers = null;
    public static volatile boolean isScanningPeers = false;
    public static volatile int scanProgress = 0;
    public static volatile int scanTotal = 0;

    private static ConnectivityManager connectivityManager;
    private static volatile boolean applicationInited = false;
    private static volatile  ConnectivityManager.NetworkCallback networkCallback;
    private static long lastNetworkCheckTypeTime;
    private static int lastKnownNetworkType = -1;

    public static long startTime;

    public static volatile boolean isScreenOn = false;
    public static volatile boolean mainInterfacePaused = true;
    public static volatile boolean mainInterfaceStopped = true;
    public static volatile boolean externalInterfacePaused = true;
    public static volatile boolean mainInterfacePausedStageQueue = true;
    public static boolean canDrawOverlays;
    public static volatile long mainInterfacePausedStageQueueTime;

    private static PushListenerController.IPushListenerServiceProvider pushProvider;
    private static IMapsProvider mapsProvider;
    private static ILocationServiceProvider locationServiceProvider;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    public static ILocationServiceProvider getLocationServiceProvider() {
        if (locationServiceProvider == null) {
            locationServiceProvider = applicationLoaderInstance.onCreateLocationServiceProvider();
            locationServiceProvider.init(applicationContext);
        }
        return locationServiceProvider;
    }

    protected ILocationServiceProvider onCreateLocationServiceProvider() {
        return new GoogleLocationProvider();
    }

    public static IMapsProvider getMapsProvider() {
        if (mapsProvider == null) {
            mapsProvider = applicationLoaderInstance.onCreateMapsProvider();
        }
        return mapsProvider;
    }

    protected IMapsProvider onCreateMapsProvider() {
        return new GoogleMapsProvider();
    }

    public static PushListenerController.IPushListenerServiceProvider getPushProvider() {
        if (pushProvider == null) {
            pushProvider = applicationLoaderInstance.onCreatePushProvider();
        }
        return pushProvider;
    }

    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        return PushListenerController.GooglePushListenerServiceProvider.INSTANCE;
    }

    public static String getApplicationId() {
        return applicationLoaderInstance.onGetApplicationId();
    }

    protected String onGetApplicationId() {
        return null;
    }

    public static boolean isHuaweiStoreBuild() {
        return applicationLoaderInstance.isHuaweiBuild();
    }

    public static boolean isStandaloneBuild() {
        return applicationLoaderInstance.isStandalone();
    }

    public static boolean isBetaBuild() {
        return applicationLoaderInstance.isBeta();
    }

    public static boolean isAndroidTestEnvironment() {
        return applicationLoaderInstance.isAndroidTestEnv();
    }

    protected boolean isHuaweiBuild() {
        return false;
    }

    protected boolean isStandalone() {
        return false;
    }

    protected boolean isBeta() {
        return false;
    }

    protected boolean isAndroidTestEnv() {
        return false;
    }

    public static File getFilesDirFixed() {
        for (int a = 0; a < 10; a++) {
            File path = ApplicationLoader.applicationContext.getFilesDir();
            if (path != null) {
                return path;
            }
        }
        try {
            ApplicationInfo info = applicationContext.getApplicationInfo();
            File path = new File(info.dataDir, "files");
            path.mkdirs();
            return path;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return new File("/data/data/org.telegram.messenger/files");
    }

    public static File getFilesDirFixed(String child) {
        try {
            File path = getFilesDirFixed();
            File dir = new File(path, child);
            dir.mkdirs();

            return dir;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    public static void postInitApplication() {
        if (applicationInited || applicationContext == null) {
            return;
        }
        applicationInited = true;
        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);

        try {
            LocaleController.getInstance(); //TODO improve
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                    } catch (Throwable ignore) {

                    }

                    boolean isSlow = isConnectionSlow();
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        ConnectionsManager.getInstance(a).checkConnection();
                        FileLoader.getInstance(a).onNetworkChanged(isSlow);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            ApplicationLoader.applicationContext.registerReceiver(networkStateReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            final BroadcastReceiver mReceiver = new ScreenReceiver();
            applicationContext.registerReceiver(mReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            isScreenOn = pm.isScreenOn();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("screen state = " + isScreenOn);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        SharedConfig.loadConfig();
        SharedPrefsHelper.init(applicationContext);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            UserConfig.getInstance(a).loadConfig();
            MessagesController.getInstance(a);
            if (a == 0) {
                SharedConfig.pushStringStatus = "__FIREBASE_GENERATING_SINCE_" + ConnectionsManager.getInstance(a).getCurrentTime() + "__";
            } else {
                ConnectionsManager.getInstance(a);
            }
            TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
            if (user != null) {
                MessagesController.getInstance(a).putUser(user, true);
                SendMessagesHelper.getInstance(a).checkUnsentMessages();
            }
        }

        ApplicationLoader app = (ApplicationLoader) ApplicationLoader.applicationContext;
        app.initPushServices();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app initied");
        }

        MediaController.getInstance();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            ContactsController.getInstance(a).checkAppAccount();
            DownloadController.getInstance(a);
        }
        BillingController.getInstance().startConnection();
    }

    public ApplicationLoader() {
        super();
    }

    /**
     * Build a peer URI with maxbackoff param for stable reconnection.
     * Idempotent — won't double-add the param.
     */
    public static String buildPeerUri(String uri) {
        if (uri.contains("maxbackoff=")) return uri;
        String separator = uri.contains("?") ? "&" : "?";
        return uri + separator + "maxbackoff=5s";
    }

    private String buildFinalConfig(JSONObject json, List<PublicPeerInfo> peersToPut) throws Exception {
        // Add Certificate field if missing (required by core.New)
        if (!json.has("Certificate")) {
            json.put("Certificate", JSONObject.NULL);
        }

        if (peersToPut != null) {
            JSONArray peersArray = new JSONArray();
            for (PublicPeerInfo p : peersToPut) {
                peersArray.put(buildPeerUri(p.getUri()));
            }
            json.put("Peers", peersArray);
        }

        // 3. Отключаем лишний шум
        json.put("MulticastInterfaces", new JSONArray());
        json.put("IfMTU", 1280);
        json.put("NodeInfoPrivacy", true);

        return json.toString();
    }


    /**
     * Swap the active Yggdrasil peer: removes all current peers, adds the new one, updates saved config.
     * Safe to call from any thread.
     */
    public static void switchToPeer(String peerUri) {
        Yggstack ygg = yggInstance;
        if (ygg == null) return;
        try {
            // Remove all current peers — try both bare URI and full URI with params,
            // because getPeersJSON() returns bare URIs but removePeer may need the URI as added
            String peersJson = ygg.getPeersJSON();
            if (peersJson != null && !peersJson.isEmpty()) {
                JSONArray arr = new JSONArray(peersJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.getJSONObject(i);
                    String uri = p.optString("URI", "");
                    if (!uri.isEmpty()) {
                        Log.d("YGG_TEST", "switchToPeer: removing " + uri);
                        ygg.removePeer(uri);
                        ygg.removePeer(buildPeerUri(uri));
                    }
                }
            }

            // Add the new peer
            String newUri = buildPeerUri(peerUri);
            Log.d("YGG_TEST", "switchToPeer: adding " + newUri);
            ygg.addPeer(newUri);

            Log.d("YGG_TEST", "switchToPeer: peers after: " + ygg.getPeersJSON());

            // Update saved config
            SharedPreferences prefs = applicationContext.getSharedPreferences("yggstack_prefs", MODE_PRIVATE);
            String configJson = prefs.getString("ygg_config", null);
            if (configJson != null) {
                JSONObject config = new JSONObject(configJson);
                JSONArray peersArray = new JSONArray();
                peersArray.put(newUri);
                config.put("Peers", peersArray);
                prefs.edit().putString("ygg_config", config.toString()).apply();
            }
        } catch (Exception e) {
            Log.e("YGG_TEST", "Peer switch failed: " + e.getMessage());
        }
    }

    private void swapPeer(PublicPeerInfo newPeer, AtomicReference<String> currentPeerUri,
                          AtomicReference<Long> lastSwitchTime) {
        Log.d("YGG_TEST", "Switching to better peer: " + newPeer.getUri() + " (RTT: " + newPeer.getRtt() + "ms) from " + currentPeerUri.get());
        switchToPeer(newPeer.getUri());
        currentPeerUri.set(newPeer.getUri());
        lastSwitchTime.set(System.currentTimeMillis());
    }

    private void startYggstack(JSONObject yggConfig, List<PublicPeerInfo> peers) throws Exception {
        SharedPreferences prefs = getSharedPreferences("yggstack_prefs", MODE_PRIVATE);
        String finalConfigJson = buildFinalConfig(yggConfig, peers);
        prefs.edit().putString("ygg_config", finalConfigJson).apply();

        Yggstack ygg = Mobile.newYggstack();
        ygg.loadConfigJSON(finalConfigJson);

        ygg.clearLocalMappings();
        ygg.addLocalTCPMapping("127.0.0.1:9001", "[203:d7b9:b017:4ec0:eb4b:b28b:8a9f:bcd0]:9000");

        ygg.setLogLevel("info");
        ygg.setLogCallback(message -> Log.d("YGG_TEST", message));

        ygg.start("", "");
        yggInstance = ygg;
        Log.i("YGG_TEST", "Yggstack TUNNEL active on 9001. My IP: " + ygg.getAddress());

        // Enable proxy now that the tunnel is actually listening
        ConnectionsManager.setProxySettings(true, "127.0.0.1", 9001, "", "", "00000000000000000000000000000001");
    }

    private void initYggdrasil() {
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            try {
                SharedPreferences prefs = getSharedPreferences("yggstack_prefs", MODE_PRIVATE);
                String savedConfigJson = prefs.getString("ygg_config", null);
                boolean initialLaunch = savedConfigJson == null || savedConfigJson.isEmpty();

                PeerPingerService pingerService = new PeerPingerService();
                boolean peersUnreachable = false;

                JSONObject yggConfig;
                if (initialLaunch) {
                    Log.d("YGG_TEST", "First launch: generating new keys...");
                    yggConfig = new JSONObject(Mobile.generateConfig());
                } else {
                    Log.d("YGG_TEST", "Loading existing config (keys preserved)");
                    yggConfig = new JSONObject(savedConfigJson);
                    JSONArray peersArray = yggConfig.getJSONArray("Peers");
                    Log.d("YGG_TEST", "Pinging saved peers...");
                    for (int i = 0; i < peersArray.length(); i++) {
                        String peerUrl = peersArray.getString(i);
                        PublicPeerInfo info = new PublicPeerInfo(peerUrl, "", null, null);
                        PublicPeerInfo result = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) ->
                                pingerService.checkPeer(info, continuation)
                        );
                        if (result.getRtt() == null) {
                            Log.w("YGG_TEST", "Saved peer unreachable: " + peerUrl);
                            peersUnreachable = true;
                            break;
                        } else {
                            Log.d("YGG_TEST", "Saved peer reachable: " + peerUrl + ", RTT: " + result.getRtt());
                        }
                    }
                }

                if (!initialLaunch && !peersUnreachable) {
                    // Saved peer is fine, start immediately
                    startYggstack(yggConfig, null);
                    return;
                }

                // Need to find new peers
                isPeersReiniting = true;
                Log.d("YGG_TEST", "Fetching public peers...");
                PeerFetcherService peerFetcher = new PeerFetcherService();
                List<PublicPeerInfo> peers = peerFetcher.fetchPublicPeersBlocking();
                if (peers == null || peers.isEmpty()) {
                    Log.e("YGG_TEST", "No public peers found!");
                    isPeersReiniting = false;
                    return;
                }
                Log.d("YGG_TEST", "Found " + peers.size() + " peers. Pinging with early start...");
                isScanningPeers = true;
                scanProgress = 0;
                scanTotal = peers.size();

                AtomicBoolean yggStarted = new AtomicBoolean(false);
                AtomicReference<String> currentPeerUri = new AtomicReference<>(null);
                AtomicReference<Long> lastSwitchTime = new AtomicReference<>(0L);
                JSONObject yggConfigForCallback = yggConfig;

                List<PublicPeerInfo> sortedPeers = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) ->
                        pingerService.checkPeersByHostWithProgress(peers, (checked, total) -> {
                            scanProgress = checked;
                            return Unit.INSTANCE;
                        }, updatedList -> {
                            lastPingedPeers = new ArrayList<>(updatedList);
                            // Find current best reachable peer
                            PublicPeerInfo best = null;
                            for (PublicPeerInfo p : updatedList) {
                                if (p.getRtt() != null) {
                                    best = p;
                                    break;
                                }
                            }
                            if (best == null) return Unit.INSTANCE;

                            if (!yggStarted.get()) {
                                // First reachable peer — start immediately
                                if (yggStarted.compareAndSet(false, true)) {
                                    currentPeerUri.set(best.getUri());
                                    lastSwitchTime.set(System.currentTimeMillis());
                                    Log.d("YGG_TEST", "Early start with peer: " + best.getUri() + " (RTT: " + best.getRtt() + "ms)");
                                    try {
                                        startYggstack(yggConfigForCallback, Collections.singletonList(best));
                                    } catch (Exception e) {
                                        Log.e("YGG_TEST", "Early start failed: " + e.getMessage());
                                        yggStarted.set(false);
                                    }
                                }
                            } else if (!best.getUri().equals(currentPeerUri.get())) {
                                // Better peer found — swap if >=15s since last switch
                                long elapsed = System.currentTimeMillis() - lastSwitchTime.get();
                                if (elapsed >= 15000) {
                                    swapPeer(best, currentPeerUri, lastSwitchTime);
                                }
                            }
                            return Unit.INSTANCE;
                        }, continuation)
                );

                isPeersReiniting = false;
                isScanningPeers = false;
                lastPingedPeers = sortedPeers;

                // Final swap to best peer if needed
                PublicPeerInfo bestPeer = null;
                for (PublicPeerInfo p : sortedPeers) {
                    if (p.getRtt() != null) {
                        bestPeer = p;
                        break;
                    }
                }

                if (bestPeer == null) {
                    Log.e("YGG_TEST", "No reachable peers found!");
                    return;
                }

                if (!yggStarted.get()) {
                    Log.d("YGG_TEST", "Starting with best peer: " + bestPeer.getUri() + " (RTT: " + bestPeer.getRtt() + "ms)");
                    startYggstack(yggConfig, Collections.singletonList(bestPeer));
                } else if (!bestPeer.getUri().equals(currentPeerUri.get())) {
                    swapPeer(bestPeer, currentPeerUri, lastSwitchTime);
                } else {
                    Log.d("YGG_TEST", "Current peer is already the best: " + bestPeer.getUri());
                }
            } catch (Exception e) {
                Log.e("YGG_TEST", "Yggstack CRASH: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public void onCreate() {

        initYggdrasil();

        applicationLoaderInstance = this;
        try {
            applicationContext = getApplicationContext();
        } catch (Throwable ignore) {

        }

        super.onCreate();

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app start time = " + (startTime = SystemClock.elapsedRealtime()));
            try {
                final PackageInfo info = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                final String abi;
                switch (info.versionCode % 10) {
                    case 1:
                    case 2:
                        abi = "store bundled " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        break;
                    default:
                    case 9:
                        if (ApplicationLoader.isStandaloneBuild()) {
                            abi = "direct " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        } else {
                            abi = "universal " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        }
                        break;
                }
                FileLog.d("buildVersion = " + String.format(Locale.US, "v%s (%d[%d]) %s", info.versionName, info.versionCode / 10, info.versionCode % 10, abi));
            } catch (Exception e) {
                FileLog.e(e);
            }
            FileLog.d("device = manufacturer=" + Build.MANUFACTURER + ", device=" + Build.DEVICE + ", model=" + Build.MODEL + ", product=" + Build.PRODUCT);
        }
        if (applicationContext == null) {
            applicationContext = getApplicationContext();
        }

        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);
        try {
            ConnectionsManager.native_setJava(false);
        } catch (UnsatisfiedLinkError error) {
            throw new RuntimeException("can't load native libraries " +  Build.CPU_ABI + " lookup folder " + NativeLoader.getAbiFolder());
        }
        new ForegroundDetector(this) {
            @Override
            public void onActivityStarted(Activity activity) {
                boolean wasInBackground = isBackground();
                super.onActivityStarted(activity);
                if (wasInBackground) {
                    ensureCurrentNetworkGet(true);
                }
            }
        };
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("load libs time = " + (SystemClock.elapsedRealtime() - startTime));
        }

        applicationHandler = new Handler(applicationContext.getMainLooper());

        AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);

        LauncherIconController.tryFixLauncherIconIfNeeded();
        ProxyRotationController.init();
    }

    public static void startPushService() {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        boolean enabled;
        if (preferences.contains("pushService")) {
            enabled = preferences.getBoolean("pushService", true);
        } else {
            enabled = MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", false);
        }
        if (enabled) {
            try {
                applicationContext.startService(new Intent(applicationContext, NotificationsService.class));
            } catch (Throwable ignore) {

            }
        } else {
            applicationContext.stopService(new Intent(applicationContext, NotificationsService.class));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            LocaleController.getInstance().onDeviceConfigurationChange(newConfig);
            AndroidUtilities.checkDisplaySize(applicationContext, newConfig);
            VideoCapturerDevice.checkScreenCapturerSize();
            AndroidUtilities.resetTabletFlag();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initPushServices() {
        AndroidUtilities.runOnUIThread(() -> {
            if (getPushProvider().hasServices()) {
                getPushProvider().onRequestPushToken();
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("No valid " + getPushProvider().getLogTitle() + " APK found.");
                }
                SharedConfig.pushStringStatus = "__NO_GOOGLE_PLAY_SERVICES__";
                PushListenerController.sendRegistrationToServer(getPushProvider().getPushType(), null);
            }
        }, 1000);
    }

    private boolean checkPlayServices() {
        try {
            int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
            return resultCode == ConnectionResult.SUCCESS;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    private static long lastNetworkCheck = -1;
    private static void ensureCurrentNetworkGet() {
        final long now = System.currentTimeMillis();
        ensureCurrentNetworkGet(now - lastNetworkCheck > 5000);
        lastNetworkCheck = now;
    }

    private static void ensureCurrentNetworkGet(boolean force) {
        if (force || currentNetworkInfo == null) {
            try {
                if (connectivityManager == null) {
                    connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                }
                currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (networkCallback == null) {
                        networkCallback = new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(@NonNull Network network) {
                                lastKnownNetworkType = -1;
                            }

                            @Override
                            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                                lastKnownNetworkType = -1;
                            }
                        };
                        connectivityManager.registerDefaultNetworkCallback(networkCallback);
                    }
                }
            } catch (Throwable ignore) {

            }
        }
    }

    public static boolean isRoaming() {
        try {
            ensureCurrentNetworkGet(false);
            return currentNetworkInfo != null && currentNetworkInfo.isRoaming();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedOrConnectingToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET)) {
                NetworkInfo.State state = currentNetworkInfo.getState();
                if (state == NetworkInfo.State.CONNECTED || state == NetworkInfo.State.CONNECTING || state == NetworkInfo.State.SUSPENDED) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) && currentNetworkInfo.getState() == NetworkInfo.State.CONNECTED) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectionSlow() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && currentNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                switch (currentNetworkInfo.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        return true;
                }
            }
        } catch (Throwable ignore) {

        }
        return false;
    }

    public static int getAutodownloadNetworkType() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return StatsController.TYPE_MOBILE;
            }
            if (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (lastKnownNetworkType == StatsController.TYPE_MOBILE || lastKnownNetworkType == StatsController.TYPE_WIFI) && System.currentTimeMillis() - lastNetworkCheckTypeTime < 5000) {
                    return lastKnownNetworkType;
                }
                if (connectivityManager.isActiveNetworkMetered()) {
                    lastKnownNetworkType = StatsController.TYPE_MOBILE;
                } else {
                    lastKnownNetworkType = StatsController.TYPE_WIFI;
                }
                lastNetworkCheckTypeTime = System.currentTimeMillis();
                return lastKnownNetworkType;
            }
            if (currentNetworkInfo.isRoaming()) {
                return StatsController.TYPE_ROAMING;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return StatsController.TYPE_MOBILE;
    }

    public static int getCurrentNetworkType() {
        if (isConnectedOrConnectingToWiFi()) {
            return StatsController.TYPE_WIFI;
        } else if (isRoaming()) {
            return StatsController.TYPE_ROAMING;
        } else {
            return StatsController.TYPE_MOBILE;
        }
    }

    public static boolean isNetworkOnlineFast() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return true;
            }
            if (currentNetworkInfo.isConnectedOrConnecting() || currentNetworkInfo.isAvailable()) {
                return true;
            }

            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnlineRealtime() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
            if (netInfo != null && (netInfo.isConnectedOrConnecting() || netInfo.isAvailable())) {
                return true;
            }

            netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnline() {
        boolean result = isNetworkOnlineRealtime();
        if (BuildVars.DEBUG_PRIVATE_VERSION) {
            boolean result2 = isNetworkOnlineFast();
            if (result != result2) {
                FileLog.d("network online mismatch");
            }
        }
        return result;
    }

    public static void startAppCenter(Activity context) {
        applicationLoaderInstance.startAppCenterInternal(context);
    }

    public static void checkForUpdates() {
        applicationLoaderInstance.checkForUpdatesInternal();
    }

    public static void appCenterLog(Throwable e) {
        applicationLoaderInstance.appCenterLogInternal(e);
    }

    protected void appCenterLogInternal(Throwable e) {

    }

    protected void checkForUpdatesInternal() {

    }

    protected void startAppCenterInternal(Activity context) {

    }

    public static void logDualCamera(boolean success, boolean vendor) {
        applicationLoaderInstance.logDualCameraInternal(success, vendor);
    }

    protected void logDualCameraInternal(boolean success, boolean vendor) {

    }

    public boolean checkApkInstallPermissions(final Context context) {
        return false;
    }

    public boolean openApkInstall(Activity activity, TLRPC.Document document) {
        return false;
    }

    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        return false;
    }

    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        return false;
    }

    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenu, ViewGroup sideMenuContainer) {
        return null;
    }

    public IUpdateButton takeUpdateButton(Context context) {
        return null;
    }

    public TLRPC.Update parseTLUpdate(int constructor) {
        return null;
    }

    public void processUpdate(int currentAccount, TLRPC.Update update) {

    }

    public boolean onSuggestionFill(String suggestion, CharSequence[] output, boolean[] closeable) {
        return false;
    }

    public boolean onSuggestionClick(String suggestion) {
        return false;
    }

    public boolean extendDrawer(ArrayList<DrawerLayoutAdapter.Item> items) {
        return false;
    }

    public boolean checkRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        return false;
    }

    public boolean consumePush(int account, JSONObject json) {
        return false;
    }

    public void onResume() {

    }

    public boolean onPause() {
        return false;
    }

    public BaseFragment openSettings(int n) {
        return null;
    }

    public boolean isCustomUpdate() {
        return false;
    }
    public void downloadUpdate() {}
    public void cancelDownloadingUpdate() {}
    public boolean isDownloadingUpdate() {
        return false;
    }
    public float getDownloadingUpdateProgress() {
        return 0.0f;
    }
    public void checkUpdate(boolean force, Runnable whenDone) {}
    public BetaUpdate getUpdate() {
        return null;
    }
    public File getDownloadedUpdateFile() {
        return null;
    }
}
