package vn.ssi.openapi.config;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ReconnectPolicyTest {
    @Test void runtimeDefaultsFollowLegacyBudgetButCanBeOverriddenIndependently() {
        var legacy = SsiConfig.builder().maxRetries(7).retryDelay(Duration.ofSeconds(2)).build();
        assertEquals(7, legacy.reconnectPolicy().maxAttempts());
        assertEquals(Duration.ofSeconds(2), legacy.reconnectPolicy().initialDelay());
        assertEquals(ReconnectAuthenticationPolicy.REFRESH_ONLY, legacy.reconnectAuthenticationPolicy());
        assertEquals(Duration.ZERO, legacy.heartbeatReceiveTimeout());
        var runtime = new ReconnectPolicy(-1, Duration.ofMillis(1), Duration.ofSeconds(60), 0.2);
        assertEquals(5, SsiConfig.builder().reconnectPolicy(runtime).build().maxRetries());
        assertTrue(runtime.canAttempt(Integer.MAX_VALUE));
    }
    @Test void backoffCapsWithoutOverflowEvenForHugeAttempt() {
        var policy = new ReconnectPolicy(5, Duration.ofSeconds(2), Duration.ofSeconds(60), 0);
        assertEquals(Duration.ofSeconds(2), policy.delayForAttempt(1));
        assertEquals(Duration.ofSeconds(4), policy.delayForAttempt(2));
        assertEquals(Duration.ofSeconds(60), policy.delayForAttempt(Integer.MAX_VALUE));
        assertFalse(policy.canAttempt(6));
    }
    @Test void invalidPolicyAndSubMillisecondDeadlinesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ReconnectPolicy(0, Duration.ZERO, Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class, () -> new ReconnectPolicy(1, Duration.ofSeconds(2), Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class, () -> new ReconnectPolicy(1, Duration.ZERO, Duration.ofSeconds(1), Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> SsiConfig.builder().sendTimeout(Duration.ofNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> SsiConfig.builder().heartbeatReceiveTimeout(Duration.ofNanos(1)));
    }
}
