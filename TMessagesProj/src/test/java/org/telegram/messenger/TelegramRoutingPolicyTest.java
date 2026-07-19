package org.telegram.messenger;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TelegramRoutingPolicyTest {

    @Test
    public void yggdrasilRequiresContinuousThreeSecondFailureAndFreshHealth() {
        long started = 10_000L;
        assertFalse(TelegramRoutingPolicy.shouldEnableYggdrasil(
                true, true, true, started, started + 2_999L, 3_000L, true));
        assertTrue(TelegramRoutingPolicy.shouldEnableYggdrasil(
                true, true, true, started, started + 3_000L, 3_000L, true));
        assertFalse(TelegramRoutingPolicy.shouldEnableYggdrasil(
                true, true, true, started, started + 3_000L, 3_000L, false));
    }

    @Test
    public void yggdrasilCannotBeEnabledOfflineOrAfterDirectRecovers() {
        assertFalse(TelegramRoutingPolicy.shouldEnableYggdrasil(
                true, false, true, 1_000L, 10_000L, 3_000L, true));
        assertFalse(TelegramRoutingPolicy.shouldEnableYggdrasil(
                true, true, false, 1_000L, 10_000L, 3_000L, true));
        assertFalse(TelegramRoutingPolicy.shouldEnableYggdrasil(
                false, true, true, 1_000L, 10_000L, 3_000L, true));
    }

    @Test
    public void healthIsBoundToAccountAndBothGenerations() {
        assertTrue(isFreshHealth(5, 5, 7, 7, 9, 9));
        assertFalse(isFreshHealth(4, 5, 7, 7, 9, 9));
        assertFalse(isFreshHealth(5, 5, 6, 7, 9, 9));
        assertFalse(isFreshHealth(5, 5, 7, 7, 8, 9));
    }

    @Test
    public void healthExpiresAfterTtlAndRequiresEndToEndReadiness() {
        assertTrue(TelegramRoutingPolicy.isFreshYggdrasilHealth(
                1_000L, 31_000L, 30_000L, true, true, 0, 0, 1, 1, 2, 2));
        assertFalse(TelegramRoutingPolicy.isFreshYggdrasilHealth(
                1_000L, 31_001L, 30_000L, true, true, 0, 0, 1, 1, 2, 2));
        assertFalse(TelegramRoutingPolicy.isFreshYggdrasilHealth(
                1_000L, 2_000L, 30_000L, false, true, 0, 0, 1, 1, 2, 2));
        assertFalse(TelegramRoutingPolicy.isFreshYggdrasilHealth(
                1_000L, 2_000L, 30_000L, true, false, 0, 0, 1, 1, 2, 2));
    }

    @Test
    public void staleProbeCallbacksCannotAffectRouting() {
        assertTrue(TelegramRoutingPolicy.isProbeCurrent(false, 0, 0, 3, 3, 4, 5));
        assertFalse(TelegramRoutingPolicy.isProbeCurrent(false, 0, 1, 3, 3, 4, 4));
        assertFalse(TelegramRoutingPolicy.isProbeCurrent(false, 0, 0, 2, 3, 4, 4));
        assertFalse(TelegramRoutingPolicy.isProbeCurrent(true, 0, 0, 3, 3, 4, 5));
        assertTrue(TelegramRoutingPolicy.isProbeCurrent(true, 0, 0, 3, 3, 5, 5));
    }

    private static boolean isFreshHealth(
            int healthyAccount,
            int selectedAccount,
            long healthyNetworkGeneration,
            long networkGeneration,
            long healthyYggdrasilGeneration,
            long yggdrasilGeneration) {
        return TelegramRoutingPolicy.isFreshYggdrasilHealth(
                1_000L,
                2_000L,
                30_000L,
                true,
                true,
                healthyAccount,
                selectedAccount,
                healthyNetworkGeneration,
                networkGeneration,
                healthyYggdrasilGeneration,
                yggdrasilGeneration);
    }
}
