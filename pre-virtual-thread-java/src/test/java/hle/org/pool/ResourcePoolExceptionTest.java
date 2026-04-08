package hle.org.pool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePoolExceptionTest {

    @Test
    void messageConstructorSetsMessage() {
        ResourcePoolException ex = new ResourcePoolException("pool error");
        assertEquals("pool error", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void messageCauseConstructorSetsBoth() {
        RuntimeException cause = new RuntimeException("root cause");
        ResourcePoolException ex = new ResourcePoolException("pool error", cause);
        assertEquals("pool error", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }
}
