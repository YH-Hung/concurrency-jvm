package hle.org.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemoteClientExceptionTest {

    @Test
    void messageConstructorIsNotRetryable() {
        RemoteClientException ex = new RemoteClientException("error");
        assertEquals("error", ex.getMessage());
        assertFalse(ex.isRetryable());
        assertNull(ex.getCause());
    }

    @Test
    void messageRetryableConstructorSetsFlag() {
        RemoteClientException ex = new RemoteClientException("error", true);
        assertTrue(ex.isRetryable());
    }

    @Test
    void messageCauseConstructorIsNotRetryable() {
        RuntimeException cause = new RuntimeException("root");
        RemoteClientException ex = new RemoteClientException("error", cause);
        assertEquals(cause, ex.getCause());
        assertFalse(ex.isRetryable());
    }

    @Test
    void messageCauseRetryableConstructorSetsAll() {
        RuntimeException cause = new RuntimeException("root");
        RemoteClientException ex = new RemoteClientException("error", cause, true);
        assertEquals(cause, ex.getCause());
        assertTrue(ex.isRetryable());
    }
}
