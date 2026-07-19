package org.telegram.messenger;

/** Pure routing predicates kept separate from Android/native orchestration. */
final class TelegramRoutingPolicy {

    private TelegramRoutingPolicy() {
    }

    static boolean shouldEnableYggdrasil(
            boolean directRoute,
            boolean online,
            boolean directConnecting,
            long connectingSinceMs,
            long nowMs,
            long graceMs,
            boolean freshYggdrasilHealth) {
        return directRoute
                && online
                && directConnecting
                && connectingSinceMs >= 0
                && nowMs >= connectingSinceMs
                && nowMs - connectingSinceMs >= graceMs
                && freshYggdrasilHealth;
    }

    static boolean isFreshYggdrasilHealth(
            long healthyAtMs,
            long nowMs,
            long ttlMs,
            boolean running,
            boolean peerUp,
            int healthyAccount,
            int selectedAccount,
            long healthyNetworkGeneration,
            long networkGeneration,
            long healthyYggdrasilGeneration,
            long yggdrasilGeneration) {
        return healthyAtMs >= 0
                && nowMs >= healthyAtMs
                && nowMs - healthyAtMs <= ttlMs
                && running
                && peerUp
                && healthyAccount == selectedAccount
                && healthyNetworkGeneration == networkGeneration
                && healthyYggdrasilGeneration == yggdrasilGeneration;
    }

    static boolean isProbeCurrent(
            boolean yggdrasilProbe,
            int probeAccount,
            int selectedAccount,
            long probeNetworkGeneration,
            long networkGeneration,
            long probeYggdrasilGeneration,
            long yggdrasilGeneration) {
        return probeAccount == selectedAccount
                && probeNetworkGeneration == networkGeneration
                && (!yggdrasilProbe || probeYggdrasilGeneration == yggdrasilGeneration);
    }
}
