package hle.org;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Basic application test.
 */
class AppTest {

    @Test
    void shouldSupportVirtualThreads() {
        // Verify that virtual threads are available (JDK 21+)
        Thread virtualThread = Thread.ofVirtual().unstarted(() -> {});
        assertTrue(virtualThread.isVirtual(), "Virtual threads should be supported");
    }

    @Test
    void shouldSupportStructuredConcurrency() {
        // Verify that StructuredTaskScope is available (preview feature)
        // Just checking the class is loadable
        try {
            Class.forName("java.util.concurrent.StructuredTaskScope");
            assertTrue(true, "StructuredTaskScope should be available");
        } catch (ClassNotFoundException e) {
            // This shouldn't happen with --enable-preview on JDK 25
            throw new AssertionError("StructuredTaskScope not available", e);
        }
    }
}
