package hle.org.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SimulatedCorbaClient.
 */
class SimulatedCorbaClientTest {

    @BeforeEach
    void setUp() {
        SimulatedCorbaClient.resetGlobalCounter();
    }

    @Test
    void shouldExecuteRequest() {
        SimulatedCorbaClient client = SimulatedCorbaClient.builder()
                .latency(1, 10)
                .failureRate(0)
                .verbose(false)
                .build();

        String response = client.execute("test-request");

        assertNotNull(response);
        assertTrue(response.contains("test-request"));
        assertEquals(1, client.getRequestCount());
    }

    @Test
    void shouldTrackGlobalRequestCount() {
        SimulatedCorbaClient client1 = SimulatedCorbaClient.builder()
                .latency(1, 5)
                .failureRate(0)
                .verbose(false)
                .build();
        SimulatedCorbaClient client2 = SimulatedCorbaClient.builder()
                .latency(1, 5)
                .failureRate(0)
                .verbose(false)
                .build();

        client1.execute("req1");
        client2.execute("req2");
        client1.execute("req3");

        assertEquals(2, client1.getRequestCount());
        assertEquals(1, client2.getRequestCount());
        assertEquals(3, SimulatedCorbaClient.getGlobalRequestCount());
    }

    @Test
    void shouldHaveUniqueIds() {
        SimulatedCorbaClient client1 = new SimulatedCorbaClient("endpoint");
        SimulatedCorbaClient client2 = new SimulatedCorbaClient("endpoint");

        assertNotEquals(client1.getId(), client2.getId());
        assertTrue(client1.getId().startsWith("corba-client-"));
    }

    @Test
    void shouldBeValidWhenConnected() {
        SimulatedCorbaClient client = new SimulatedCorbaClient("endpoint");

        assertTrue(client.isValid());
        assertTrue(client.isConnected());

        client.close();

        assertFalse(client.isValid());
        assertFalse(client.isConnected());
    }

    @Test
    void shouldRejectRequestsAfterClose() {
        SimulatedCorbaClient client = SimulatedCorbaClient.builder()
                .latency(1, 5)
                .failureRate(0)
                .verbose(false)
                .build();

        client.close();

        assertThrows(RemoteClientException.class, () -> client.execute("request"));
    }

    @Test
    void shouldSimulateLatency() {
        SimulatedCorbaClient client = SimulatedCorbaClient.builder()
                .latency(50, 100)
                .failureRate(0)
                .verbose(false)
                .build();

        long start = System.currentTimeMillis();
        client.execute("timed-request");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 50, "Expected at least 50ms but got " + elapsed);
    }

    @Test
    void shouldSimulateFailures() {
        SimulatedCorbaClient client = SimulatedCorbaClient.builder()
                .latency(1, 5)
                .failureRate(1.0)  // 100% failure rate
                .verbose(false)
                .build();

        assertThrows(RemoteClientException.class, () -> client.execute("will-fail"));
    }

    @Test
    void shouldReportEndpoint() {
        String endpoint = "corba://test:1234/service";
        SimulatedCorbaClient client = SimulatedCorbaClient.builder()
                .endpoint(endpoint)
                .build();

        assertEquals(endpoint, client.getEndpoint());
    }

    @Test
    void shouldSupportReset() {
        SimulatedCorbaClient client = SimulatedCorbaClient.builder()
                .verbose(false)
                .build();

        // Reset should not throw
        assertDoesNotThrow(client::reset);
    }

    @Test
    void shouldCreateWithDefaultSettings() {
        SimulatedCorbaClient client = new SimulatedCorbaClient("endpoint");

        assertNotNull(client.getId());
        assertEquals("endpoint", client.getEndpoint());
        assertTrue(client.isConnected());
    }
}
