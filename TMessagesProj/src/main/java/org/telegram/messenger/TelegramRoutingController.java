package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

/**
 * Chooses between a direct Telegram connection and the internal Yggdrasil
 * MTProxy.  All mutable state is confined to the application/UI thread.
 *
 * <p>The proxy managed here is deliberately runtime-only.  This class must
 * not read or write Telegram's persisted manual-proxy preferences.</p>
 */
public final class TelegramRoutingController implements NotificationCenter.NotificationCenterDelegate {

    public enum Route {
        DIRECT,
        YGGDRASIL
    }

    public enum ProbeResult {
        NEVER,
        SUCCESS,
        FAILED
    }

    /** Immutable, read-only diagnostics snapshot. */
    public static final class Status {
        public final Route route;
        public final int selectedAccount;
        public final int connectionState;
        public final long networkGeneration;
        public final long yggdrasilGeneration;
        public final boolean networkOnline;
        public final boolean yggdrasilRunning;
        public final boolean yggdrasilPeerUp;
        public final boolean probeInFlight;
        public final ProbeResult lastDirectProbeResult;
        public final long lastDirectProbeLatencyMs;
        public final long lastDirectProbeAgeMs;
        public final ProbeResult lastYggdrasilProbeResult;
        public final long lastYggdrasilProbeLatencyMs;
        public final long lastYggdrasilProbeAgeMs;

        private Status(
                Route route,
                int selectedAccount,
                int connectionState,
                long networkGeneration,
                long yggdrasilGeneration,
                boolean networkOnline,
                boolean yggdrasilRunning,
                boolean yggdrasilPeerUp,
                boolean probeInFlight,
                ProbeResult lastDirectProbeResult,
                long lastDirectProbeLatencyMs,
                long lastDirectProbeAgeMs,
                ProbeResult lastYggdrasilProbeResult,
                long lastYggdrasilProbeLatencyMs,
                long lastYggdrasilProbeAgeMs) {
            this.route = route;
            this.selectedAccount = selectedAccount;
            this.connectionState = connectionState;
            this.networkGeneration = networkGeneration;
            this.yggdrasilGeneration = yggdrasilGeneration;
            this.networkOnline = networkOnline;
            this.yggdrasilRunning = yggdrasilRunning;
            this.yggdrasilPeerUp = yggdrasilPeerUp;
            this.probeInFlight = probeInFlight;
            this.lastDirectProbeResult = lastDirectProbeResult;
            this.lastDirectProbeLatencyMs = lastDirectProbeLatencyMs;
            this.lastDirectProbeAgeMs = lastDirectProbeAgeMs;
            this.lastYggdrasilProbeResult = lastYggdrasilProbeResult;
            this.lastYggdrasilProbeLatencyMs = lastYggdrasilProbeLatencyMs;
            this.lastYggdrasilProbeAgeMs = lastYggdrasilProbeAgeMs;
        }
    }

    private enum ProbeKind {
        DIRECT,
        YGGDRASIL
    }

    private static final String LOG_PREFIX = "TelegramRouting: ";
    private static final String YGG_PROXY_HOST = "127.0.0.1";
    private static final int YGG_PROXY_PORT = 9001;
    private static final String YGG_PROXY_SECRET = "00000000000000000000000000000001";

    private static final long DIRECT_GRACE_MS = 3_000L;
    private static final long PROBE_TIMEOUT_MS = 7_000L;
    private static final long YGG_HEALTH_TTL_MS = 30_000L;
    private static final long YGG_HEALTH_RETRY_MS = 5_000L;
    private static final long NETWORK_DEBOUNCE_MS = 500L;
    private static final long OWN_PROXY_STATE_SUPPRESSION_MS = 3_000L;
    private static final long DIRECT_RECHECK_INTERVAL_MS = 5L * 60L * 1_000L;

    private static final TelegramRoutingController INSTANCE = new TelegramRoutingController();

    private volatile boolean initialized;
    private volatile Route route = Route.DIRECT;
    private volatile int selectedAccount;
    private volatile int selectedConnectionState = ConnectionsManager.ConnectionStateWaitingForNetwork;
    private volatile long networkGeneration;
    private volatile long yggdrasilGeneration;
    private volatile boolean networkOnline;
    private volatile boolean yggdrasilRunning;
    private volatile boolean yggdrasilPeerUp;
    private boolean yggdrasilStopping;
    private volatile boolean foreground;

    private long directConnectingSince = -1L;
    private long suppressProxyStateUntil;
    private long nextProbeToken;
    private volatile ActiveProbe activeProbe;

    private long healthyYggdrasilAt = -1L;
    private long healthyYggdrasilNetworkGeneration = Long.MIN_VALUE;
    private long healthyYggdrasilGeneration = Long.MIN_VALUE;
    private int healthyYggdrasilAccount = -1;

    private volatile ProbeResult lastDirectProbeResult = ProbeResult.NEVER;
    private volatile long lastDirectProbeLatencyMs = -1L;
    private volatile long lastDirectProbeAt = -1L;
    private volatile ProbeResult lastYggdrasilProbeResult = ProbeResult.NEVER;
    private volatile long lastYggdrasilProbeLatencyMs = -1L;
    private volatile long lastYggdrasilProbeAt = -1L;

    private boolean graceScheduled;
    private boolean healthRetryScheduled;
    private boolean networkDebounceScheduled;
    private boolean periodicScheduled;
    private boolean proxyRouteCheckScheduled;

    private final Runnable graceRunnable = () -> {
        graceScheduled = false;
        maybeEnableYggdrasil("direct-grace-expired");
    };

    private final Runnable healthRetryRunnable = () -> {
        healthRetryScheduled = false;
        if (route != Route.DIRECT || !networkOnline || !isSelectedAccountConnecting()) {
            return;
        }
        ensureYggdrasilAvailable();
        startYggdrasilProbe("health-retry");
        if (!hasFreshYggdrasilHealth()) {
            scheduleHealthRetry();
        }
    };

    private final Runnable networkDebounceRunnable = () -> {
        networkDebounceScheduled = false;
        if (!networkOnline) {
            return;
        }
        refreshSelectedAccount("network-change");
        if (route == Route.YGGDRASIL) {
            startDirectProbe("network-change");
        } else if (isSelectedAccountConnecting()) {
            beginDirectConnecting("network-change");
        }
    };

    private final Runnable periodicDirectProbeRunnable = () -> {
        periodicScheduled = false;
        if (!foreground) {
            return;
        }
        refreshSelectedAccount("periodic");
        if (route == Route.YGGDRASIL) {
            startDirectProbe("foreground-periodic");
        }
        schedulePeriodicDirectProbe();
    };

    private final Runnable proxyRouteCheckRunnable = () -> {
        proxyRouteCheckScheduled = false;
        long remainingSuppression = suppressProxyStateUntil - SystemClock.elapsedRealtime();
        if (remainingSuppression > 0) {
            scheduleProxyRouteCheck(remainingSuppression);
            return;
        }
        if (route == Route.YGGDRASIL
                && networkOnline
                && selectedConnectionState == ConnectionsManager.ConnectionStateConnectingToProxy) {
            startDirectProbe("proxy-route-post-switch");
        }
    };

    private TelegramRoutingController() {
    }

    /** Initialize once, after ConnectionsManager and the application handler exist. */
    public static void init() {
        runOnUiThread(INSTANCE::initInternal);
    }

    /** Called only for a new, meaningful default-network generation. */
    public static void onNetworkChanged(long generation, boolean online) {
        runOnUiThread(() -> INSTANCE.onNetworkChangedInternal(generation, online));
    }

    public static void onForeground() {
        runOnUiThread(INSTANCE::onForegroundInternal);
    }

    public static void onBackground() {
        runOnUiThread(INSTANCE::onBackgroundInternal);
    }

    /**
     * Report the current Yggdrasil generation and readiness.  A generation
     * must change whenever the Yggstack instance is replaced or restarted.
     */
    public static void onYggdrasilStateChanged(long generation, boolean running, boolean peerUp) {
        runOnUiThread(() -> INSTANCE.onYggdrasilStateChangedInternal(generation, running, peerUp));
    }

    /** Must be called before stopping or replacing the active Yggstack. */
    public static void onYggdrasilStopping() {
        runOnUiThread(INSTANCE::onYggdrasilStoppingInternal);
    }

    /** Called by the native proxy-error path. */
    public static void onInternalProxyError(int account) {
        runOnUiThread(() -> INSTANCE.onInternalProxyErrorInternal(account));
    }

    /** Optional explicit hook; activeAccountChanged is also observed. */
    public static void onSelectedAccountChanged() {
        runOnUiThread(() -> INSTANCE.refreshSelectedAccount("explicit-account-change"));
    }

    public static Route getRoute() {
        return INSTANCE.route;
    }

    public static boolean isUsingYggdrasil() {
        return INSTANCE.route == Route.YGGDRASIL;
    }

    public static Status getStatus() {
        long now = SystemClock.elapsedRealtime();
        long directAt = INSTANCE.lastDirectProbeAt;
        long yggAt = INSTANCE.lastYggdrasilProbeAt;
        return new Status(
                INSTANCE.route,
                INSTANCE.selectedAccount,
                INSTANCE.selectedConnectionState,
                INSTANCE.networkGeneration,
                INSTANCE.yggdrasilGeneration,
                INSTANCE.networkOnline,
                INSTANCE.yggdrasilRunning,
                INSTANCE.yggdrasilPeerUp,
                INSTANCE.activeProbe != null,
                INSTANCE.lastDirectProbeResult,
                INSTANCE.lastDirectProbeLatencyMs,
                directAt < 0 ? -1L : Math.max(0L, now - directAt),
                INSTANCE.lastYggdrasilProbeResult,
                INSTANCE.lastYggdrasilProbeLatencyMs,
                yggAt < 0 ? -1L : Math.max(0L, now - yggAt));
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            refreshSelectedAccount("notification");
            return;
        }
        if (id != NotificationCenter.didUpdateConnectionState) {
            return;
        }
        refreshSelectedAccount("connection-state");
        if (account != selectedAccount) {
            return;
        }
        handleConnectionState(ConnectionsManager.getInstance(account).getConnectionState());
    }

    private void initInternal() {
        if (initialized) {
            return;
        }
        initialized = true;
        selectedAccount = UserConfig.selectedAccount;
        networkOnline = ApplicationLoader.isNetworkOnline();
        yggdrasilGeneration = ApplicationLoader.getYggdrasilGeneration();
        yggdrasilRunning = safeIsYggdrasilRunning();
        yggdrasilPeerUp = yggdrasilRunning && safeHasYggdrasilPeer();

        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);

        // Process starts are always direct-first, regardless of saved state.
        applyDirectProxySettings();
        selectedConnectionState = ConnectionsManager.getInstance(selectedAccount).getConnectionState();
        log("initialized account=" + selectedAccount + " state=" + selectedConnectionState
                + " online=" + networkOnline + " yggRunning=" + yggdrasilRunning
                + " peerUp=" + yggdrasilPeerUp);
        handleConnectionState(selectedConnectionState);
        if (foreground) {
            schedulePeriodicDirectProbe();
        }
    }

    private void onNetworkChangedInternal(long generation, boolean online) {
        if (!initialized) {
            networkGeneration = generation;
            networkOnline = online;
            return;
        }
        if (networkGeneration == generation && networkOnline == online) {
            return;
        }
        networkGeneration = generation;
        networkOnline = online;
        invalidateYggdrasilHealth("network-change");
        cancelActiveProbe("network-change");
        cancelGraceAndHealthRetry();
        cancelNetworkDebounce();
        cancelProxyRouteCheck();
        directConnectingSince = -1L;
        log("network generation=" + generation + " online=" + online + " route=" + route);

        if (!online) {
            return;
        }
        ensureYggdrasilAvailable();
        networkDebounceScheduled = true;
        AndroidUtilities.runOnUIThread(networkDebounceRunnable, NETWORK_DEBOUNCE_MS);
    }

    private void onForegroundInternal() {
        if (!initialized) {
            foreground = true;
            return;
        }
        foreground = true;
        refreshSelectedAccount("foreground");
        if (route == Route.YGGDRASIL
                && (lastDirectProbeAt < 0
                || SystemClock.elapsedRealtime() - lastDirectProbeAt >= DIRECT_RECHECK_INTERVAL_MS)) {
            startDirectProbe("foreground-stale");
        }
        schedulePeriodicDirectProbe();
    }

    private void onBackgroundInternal() {
        foreground = false;
        cancelPeriodicDirectProbe();
    }

    private void onYggdrasilStateChangedInternal(long generation, boolean running, boolean peerUp) {
        if (!initialized) {
            yggdrasilGeneration = generation;
            yggdrasilRunning = running;
            yggdrasilPeerUp = running && peerUp;
            return;
        }
        boolean generationChanged = yggdrasilGeneration != generation;
        boolean readinessChanged = yggdrasilRunning != running || yggdrasilPeerUp != peerUp;
        if (!generationChanged && !readinessChanged) {
            return;
        }
        yggdrasilGeneration = generation;
        yggdrasilRunning = running;
        yggdrasilPeerUp = running && peerUp;
        if (generationChanged) {
            yggdrasilStopping = false;
        }
        if (generationChanged || !running || !peerUp) {
            invalidateYggdrasilHealth("ygg-state-change");
            cancelActiveProbe("ygg-state-change");
        }
        log("ygg generation=" + generation + " running=" + running + " peerUp=" + peerUp
                + " route=" + route);

        if (route == Route.YGGDRASIL && !yggdrasilRunning) {
            // Never leave tgnet pointed at a localhost listener that no longer exists.
            switchToDirect("ygg-stopped");
        } else if (route == Route.DIRECT && networkOnline && isSelectedAccountConnecting()) {
            if (yggdrasilRunning && yggdrasilPeerUp) {
                startYggdrasilProbe("ygg-became-ready");
            } else {
                ensureYggdrasilAvailable();
                scheduleHealthRetry();
            }
        } else if (route == Route.YGGDRASIL && (!yggdrasilRunning || !yggdrasilPeerUp)) {
            startDirectProbe("ygg-unavailable");
        } else if (route == Route.YGGDRASIL && generationChanged) {
            startYggdrasilProbe("ygg-generation-change");
        }
    }

    private void onYggdrasilStoppingInternal() {
        if (!initialized) {
            yggdrasilStopping = true;
            yggdrasilRunning = false;
            yggdrasilPeerUp = false;
            return;
        }
        invalidateYggdrasilHealth("ygg-stopping");
        cancelActiveProbe("ygg-stopping");
        yggdrasilStopping = true;
        yggdrasilRunning = false;
        yggdrasilPeerUp = false;
        if (route == Route.YGGDRASIL) {
            switchToDirect("ygg-stopping");
        }
    }

    private void onInternalProxyErrorInternal(int account) {
        if (!initialized || route != Route.YGGDRASIL || account != selectedAccount) {
            return;
        }
        invalidateYggdrasilHealth("internal-proxy-error");
        if (activeProbe != null && activeProbe.kind == ProbeKind.YGGDRASIL) {
            cancelActiveProbe("internal-proxy-error");
        }
        safeRetryYggdrasilPeers();
        startDirectProbe("internal-proxy-error");
    }

    private void refreshSelectedAccount(String reason) {
        if (!initialized) {
            return;
        }
        int account = UserConfig.selectedAccount;
        if (selectedAccount == account) {
            return;
        }
        cancelActiveProbe("account-change");
        cancelGraceAndHealthRetry();
        cancelNetworkDebounce();
        cancelProxyRouteCheck();
        directConnectingSince = -1L;
        selectedAccount = account;
        selectedConnectionState = ConnectionsManager.getInstance(account).getConnectionState();
        invalidateYggdrasilHealth("account-change");
        log("selected account=" + account + " reason=" + reason + " state=" + selectedConnectionState);

        if (route == Route.YGGDRASIL && networkOnline) {
            startDirectProbe("account-change");
        } else {
            handleConnectionState(selectedConnectionState);
        }
    }

    private void handleConnectionState(int state) {
        int previous = selectedConnectionState;
        selectedConnectionState = state;

        if (state == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            directConnectingSince = -1L;
            cancelGraceAndHealthRetry();
            cancelActiveProbe("waiting-for-network");
            return;
        }

        if (state == ConnectionsManager.ConnectionStateConnected
                || state == ConnectionsManager.ConnectionStateUpdating) {
            directConnectingSince = -1L;
            cancelGraceAndHealthRetry();
            if (route == Route.DIRECT && activeProbe != null
                    && activeProbe.kind == ProbeKind.YGGDRASIL) {
                cancelActiveProbe("direct-connected");
            }
            return;
        }

        if (state == ConnectionsManager.ConnectionStateConnecting) {
            if (route == Route.DIRECT && networkOnline) {
                beginDirectConnecting("connection-state");
            }
            return;
        }

        if (state == ConnectionsManager.ConnectionStateConnectingToProxy
                && route == Route.YGGDRASIL
                && SystemClock.elapsedRealtime() >= suppressProxyStateUntil
                && previous != ConnectionsManager.ConnectionStateConnectingToProxy) {
            startDirectProbe("proxy-connection-lost");
        }
    }

    private void beginDirectConnecting(String reason) {
        if (route != Route.DIRECT || !networkOnline || !isSelectedAccountConnecting()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (directConnectingSince < 0) {
            directConnectingSince = now;
            log("direct connecting started account=" + selectedAccount + " trigger=" + reason);
        }
        scheduleGrace(now);
        ensureYggdrasilAvailable();
        if (!hasFreshYggdrasilHealth()) {
            startYggdrasilProbe(reason);
            scheduleHealthRetry();
        }
    }

    private void scheduleGrace(long now) {
        if (graceScheduled) {
            return;
        }
        long dueIn = Math.max(0L, directConnectingSince + DIRECT_GRACE_MS - now);
        graceScheduled = true;
        AndroidUtilities.runOnUIThread(graceRunnable, dueIn);
    }

    private void maybeEnableYggdrasil(String reason) {
        if (route != Route.DIRECT || !networkOnline || !isSelectedAccountConnecting()
                || directConnectingSince < 0) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - directConnectingSince;
        if (elapsed < DIRECT_GRACE_MS) {
            scheduleGrace(SystemClock.elapsedRealtime());
            return;
        }
        if (hasFreshYggdrasilHealth()) {
            switchToYggdrasil(reason);
        } else {
            startYggdrasilProbe("grace-needs-health");
            scheduleHealthRetry();
        }
    }

    private void ensureYggdrasilAvailable() {
        if (yggdrasilStopping) {
            return;
        }
        yggdrasilRunning = safeIsYggdrasilRunning();
        yggdrasilPeerUp = yggdrasilRunning && safeHasYggdrasilPeer();
        if (!yggdrasilRunning) {
            safeEnsureYggdrasilStarted();
        } else if (!yggdrasilPeerUp) {
            safeRetryYggdrasilPeers();
        }
    }

    private void startYggdrasilProbe(String trigger) {
        boolean directFallbackCheck = route == Route.DIRECT && isSelectedAccountConnecting();
        boolean activeRouteCheck = route == Route.YGGDRASIL;
        if (!networkOnline || (!directFallbackCheck && !activeRouteCheck)) {
            return;
        }
        if (activeProbe != null) {
            return;
        }
        if (yggdrasilStopping) {
            return;
        }
        yggdrasilRunning = safeIsYggdrasilRunning();
        yggdrasilPeerUp = yggdrasilRunning && safeHasYggdrasilPeer();
        if (!yggdrasilRunning || !yggdrasilPeerUp) {
            return;
        }
        startProbe(ProbeKind.YGGDRASIL, trigger);
    }

    private void startDirectProbe(String trigger) {
        if (route != Route.YGGDRASIL || !networkOnline || activeProbe != null) {
            return;
        }
        startProbe(ProbeKind.DIRECT, trigger);
    }

    private void startProbe(ProbeKind kind, String trigger) {
        int account = selectedAccount;
        ActiveProbe probe = new ActiveProbe(
                ++nextProbeToken,
                kind,
                account,
                networkGeneration,
                yggdrasilGeneration,
                trigger);
        activeProbe = probe;
        probe.watchdog = () -> {
            if (activeProbe != probe) {
                return;
            }
            cancelActiveProbe("watchdog");
            recordProbeResult(kind, -1L);
            if (kind == ProbeKind.DIRECT && route == Route.YGGDRASIL) {
                startYggdrasilProbe("direct-probe-timeout");
            } else if (kind == ProbeKind.YGGDRASIL) {
                if (route == Route.YGGDRASIL) {
                    ApplicationLoader.requestYggdrasilRestart("end-to-end route check timed out", true);
                } else {
                    scheduleHealthRetry();
                }
            }
            log("probe timeout type=" + kind + " token=" + probe.token + " trigger=" + trigger);
        };
        AndroidUtilities.runOnUIThread(probe.watchdog, PROBE_TIMEOUT_MS);

        log("probe start type=" + kind + " token=" + probe.token + " account=" + account
                + " netGen=" + networkGeneration + " yggGen=" + yggdrasilGeneration
                + " trigger=" + trigger);
        try {
            ConnectionsManager manager = ConnectionsManager.getInstance(account);
            if (kind == ProbeKind.DIRECT) {
                probe.nativeId = manager.checkDirect(time ->
                        runOnUiThread(() -> finishProbe(probe, time)));
            } else {
                probe.nativeId = manager.checkProxy(
                        YGG_PROXY_HOST,
                        YGG_PROXY_PORT,
                        "",
                        "",
                        YGG_PROXY_SECRET,
                        time -> runOnUiThread(() -> finishProbe(probe, time)));
            }
            if (activeProbe == probe && probe.nativeId <= 0) {
                finishProbe(probe, -1L);
            }
        } catch (Throwable t) {
            FileLog.e(t);
            finishProbe(probe, -1L);
        }
    }

    private void finishProbe(ActiveProbe probe, long latencyMs) {
        if (activeProbe != probe) {
            return;
        }
        activeProbe = null;
        if (probe.watchdog != null) {
            AndroidUtilities.cancelRunOnUIThread(probe.watchdog);
        }
        boolean success = latencyMs >= 0;
        recordProbeResult(probe.kind, latencyMs);
        long currentYggdrasilGeneration = probe.kind == ProbeKind.YGGDRASIL
                ? ApplicationLoader.getYggdrasilGeneration()
                : yggdrasilGeneration;
        boolean current = TelegramRoutingPolicy.isProbeCurrent(
                probe.kind == ProbeKind.YGGDRASIL,
                probe.account,
                selectedAccount,
                probe.networkGeneration,
                networkGeneration,
                probe.yggdrasilGeneration,
                currentYggdrasilGeneration);
        log("probe done type=" + probe.kind + " token=" + probe.token + " result="
                + (success ? latencyMs + "ms" : "failed") + " current=" + current
                + " trigger=" + probe.trigger);
        if (!current) {
            return;
        }

        if (probe.kind == ProbeKind.DIRECT) {
            if (success && route == Route.YGGDRASIL) {
                switchToDirect("direct-probe:" + probe.trigger);
            } else if (!success && route == Route.YGGDRASIL) {
                startYggdrasilProbe("proxy-route-diagnosis");
            }
            return;
        }

        if (success) {
            healthyYggdrasilAt = SystemClock.elapsedRealtime();
            healthyYggdrasilNetworkGeneration = networkGeneration;
            healthyYggdrasilGeneration = yggdrasilGeneration;
            healthyYggdrasilAccount = selectedAccount;
            if (route == Route.DIRECT && networkOnline && isSelectedAccountConnecting()) {
                maybeEnableYggdrasil("ygg-health-ready");
            }
        } else if (!success) {
            invalidateYggdrasilHealth("probe-failed");
            if (route == Route.YGGDRASIL) {
                log("active Yggdrasil route failed end-to-end validation; forcing restart");
                ApplicationLoader.requestYggdrasilRestart("end-to-end route check failed", true);
            } else {
                scheduleHealthRetry();
            }
        }
    }

    private void recordProbeResult(ProbeKind kind, long latencyMs) {
        long now = SystemClock.elapsedRealtime();
        if (kind == ProbeKind.DIRECT) {
            lastDirectProbeResult = latencyMs >= 0 ? ProbeResult.SUCCESS : ProbeResult.FAILED;
            lastDirectProbeLatencyMs = latencyMs;
            lastDirectProbeAt = now;
        } else {
            lastYggdrasilProbeResult = latencyMs >= 0 ? ProbeResult.SUCCESS : ProbeResult.FAILED;
            lastYggdrasilProbeLatencyMs = latencyMs;
            lastYggdrasilProbeAt = now;
        }
    }

    private void cancelActiveProbe(String reason) {
        ActiveProbe probe = activeProbe;
        if (probe == null) {
            return;
        }
        activeProbe = null;
        if (probe.watchdog != null) {
            AndroidUtilities.cancelRunOnUIThread(probe.watchdog);
        }
        if (probe.nativeId > 0) {
            try {
                ConnectionsManager.getInstance(probe.account).cancelConnectionCheck(probe.nativeId);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        log("probe cancel type=" + probe.kind + " token=" + probe.token + " reason=" + reason);
    }

    private boolean hasFreshYggdrasilHealth() {
        return TelegramRoutingPolicy.isFreshYggdrasilHealth(
                healthyYggdrasilAt,
                SystemClock.elapsedRealtime(),
                YGG_HEALTH_TTL_MS,
                yggdrasilRunning,
                yggdrasilPeerUp,
                healthyYggdrasilAccount,
                selectedAccount,
                healthyYggdrasilNetworkGeneration,
                networkGeneration,
                healthyYggdrasilGeneration,
                yggdrasilGeneration);
    }

    private void invalidateYggdrasilHealth(String reason) {
        if (healthyYggdrasilAt >= 0) {
            log("invalidate ygg health reason=" + reason);
        }
        healthyYggdrasilAt = -1L;
        healthyYggdrasilNetworkGeneration = Long.MIN_VALUE;
        healthyYggdrasilGeneration = Long.MIN_VALUE;
        healthyYggdrasilAccount = -1;
    }

    private void switchToYggdrasil(String reason) {
        if (!TelegramRoutingPolicy.shouldEnableYggdrasil(
                route == Route.DIRECT,
                networkOnline,
                isSelectedAccountConnecting(),
                directConnectingSince,
                SystemClock.elapsedRealtime(),
                DIRECT_GRACE_MS,
                hasFreshYggdrasilHealth())) {
            return;
        }
        route = Route.YGGDRASIL;
        suppressProxyStateUntil = SystemClock.elapsedRealtime() + OWN_PROXY_STATE_SUPPRESSION_MS;
        scheduleProxyRouteCheck(OWN_PROXY_STATE_SUPPRESSION_MS);
        directConnectingSince = -1L;
        cancelGraceAndHealthRetry();
        log("route DIRECT -> YGGDRASIL reason=" + reason + " account=" + selectedAccount
                + " netGen=" + networkGeneration + " yggGen=" + yggdrasilGeneration);
        try {
            ConnectionsManager.setInternalProxySettings(
                    true, YGG_PROXY_HOST, YGG_PROXY_PORT, "", "", YGG_PROXY_SECRET);
        } catch (Throwable t) {
            FileLog.e(t);
            route = Route.DIRECT;
            cancelProxyRouteCheck();
            applyDirectProxySettings();
        }
    }

    private void switchToDirect(String reason) {
        if (route == Route.DIRECT) {
            return;
        }
        route = Route.DIRECT;
        directConnectingSince = -1L;
        suppressProxyStateUntil = 0L;
        cancelGraceAndHealthRetry();
        cancelActiveProbe("switch-direct");
        cancelProxyRouteCheck();
        log("route YGGDRASIL -> DIRECT reason=" + reason + " account=" + selectedAccount
                + " netGen=" + networkGeneration + " yggGen=" + yggdrasilGeneration);
        applyDirectProxySettings();
    }

    private void applyDirectProxySettings() {
        try {
            ConnectionsManager.setInternalProxySettings(false, "", 1080, "", "", "");
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private boolean isSelectedAccountConnecting() {
        return selectedConnectionState == ConnectionsManager.ConnectionStateConnecting;
    }

    private void scheduleHealthRetry() {
        if (healthRetryScheduled || route != Route.DIRECT || !networkOnline
                || !isSelectedAccountConnecting() || hasFreshYggdrasilHealth()) {
            return;
        }
        healthRetryScheduled = true;
        AndroidUtilities.runOnUIThread(healthRetryRunnable, YGG_HEALTH_RETRY_MS);
    }

    private void schedulePeriodicDirectProbe() {
        if (!foreground || periodicScheduled) {
            return;
        }
        periodicScheduled = true;
        AndroidUtilities.runOnUIThread(periodicDirectProbeRunnable, DIRECT_RECHECK_INTERVAL_MS);
    }

    private void cancelGraceAndHealthRetry() {
        if (graceScheduled) {
            AndroidUtilities.cancelRunOnUIThread(graceRunnable);
            graceScheduled = false;
        }
        if (healthRetryScheduled) {
            AndroidUtilities.cancelRunOnUIThread(healthRetryRunnable);
            healthRetryScheduled = false;
        }
    }

    private void cancelNetworkDebounce() {
        if (networkDebounceScheduled) {
            AndroidUtilities.cancelRunOnUIThread(networkDebounceRunnable);
            networkDebounceScheduled = false;
        }
    }

    private void cancelPeriodicDirectProbe() {
        if (periodicScheduled) {
            AndroidUtilities.cancelRunOnUIThread(periodicDirectProbeRunnable);
            periodicScheduled = false;
        }
    }

    private void scheduleProxyRouteCheck(long delayMs) {
        if (proxyRouteCheckScheduled) {
            return;
        }
        proxyRouteCheckScheduled = true;
        AndroidUtilities.runOnUIThread(proxyRouteCheckRunnable, Math.max(0L, delayMs));
    }

    private void cancelProxyRouteCheck() {
        if (proxyRouteCheckScheduled) {
            AndroidUtilities.cancelRunOnUIThread(proxyRouteCheckRunnable);
            proxyRouteCheckScheduled = false;
        }
    }

    private static boolean safeIsYggdrasilRunning() {
        try {
            return ApplicationLoader.isYggdrasilRunning();
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    private static boolean safeHasYggdrasilPeer() {
        try {
            return ApplicationLoader.hasYggdrasilUpPeer();
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    private static void safeRetryYggdrasilPeers() {
        try {
            ApplicationLoader.retryYggdrasilPeers();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void safeEnsureYggdrasilStarted() {
        try {
            ApplicationLoader.ensureYggdrasilStarted();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void runOnUiThread(Runnable runnable) {
        Handler handler = ApplicationLoader.applicationHandler;
        if (handler == null) {
            return;
        }
        Looper looper = handler.getLooper();
        if (Looper.myLooper() == looper) {
            runnable.run();
        } else {
            handler.post(runnable);
        }
    }

    private static void log(String message) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d(LOG_PREFIX + message);
        }
    }

    private static final class ActiveProbe {
        final long token;
        final ProbeKind kind;
        final int account;
        final long networkGeneration;
        final long yggdrasilGeneration;
        final String trigger;
        long nativeId;
        Runnable watchdog;

        ActiveProbe(long token, ProbeKind kind, int account, long networkGeneration,
                    long yggdrasilGeneration, String trigger) {
            this.token = token;
            this.kind = kind;
            this.account = account;
            this.networkGeneration = networkGeneration;
            this.yggdrasilGeneration = yggdrasilGeneration;
            this.trigger = trigger;
        }
    }
}
