package hle.org.executor;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TaskResultTest {

    @Test
    void successResultHasCorrectFields() {
        Instant start = Instant.now();
        Instant end = start.plusMillis(100);
        TaskResult<String> result = TaskResult.success("t1", "hello", start, end);

        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
        assertEquals("t1", result.getTaskId());
        assertEquals("hello", result.getValue().orElse(null));
        assertFalse(result.getException().isPresent());
        assertEquals(start, result.getStartTime());
        assertEquals(end, result.getEndTime());
        assertEquals(100, result.getDuration().toMillis());
    }

    @Test
    void failureResultHasCorrectFields() {
        Instant start = Instant.now();
        Instant end = start.plusMillis(50);
        RuntimeException ex = new RuntimeException("oops");
        TaskResult<String> result = TaskResult.failure("t2", ex, start, end);

        assertFalse(result.isSuccess());
        assertTrue(result.isFailure());
        assertEquals("t2", result.getTaskId());
        assertFalse(result.getValue().isPresent());
        assertEquals(ex, result.getException().orElse(null));
    }

    @Test
    void getOrThrowReturnsValueOnSuccess() throws Exception {
        Instant now = Instant.now();
        TaskResult<String> result = TaskResult.success("t1", "value", now, now);
        assertEquals("value", result.getOrThrow());
    }

    @Test
    void getOrThrowThrowsCheckedExceptionOnFailure() {
        Instant now = Instant.now();
        Exception ex = new Exception("checked");
        TaskResult<String> result = TaskResult.failure("t1", ex, now, now);
        assertThrows(Exception.class, result::getOrThrow);
    }

    @Test
    void getOrThrowWrapsNonExceptionThrowableInRuntimeException() {
        Instant now = Instant.now();
        // AssertionError extends Error, not Exception
        Error err = new AssertionError("raw error");
        TaskResult<String> result = TaskResult.failure("t1", err, now, now);
        RuntimeException thrown = assertThrows(RuntimeException.class, result::getOrThrow);
        assertEquals(err, thrown.getCause());
    }

    @Test
    void getOrDefaultReturnsValueOnSuccess() {
        Instant now = Instant.now();
        TaskResult<String> result = TaskResult.success("t1", "actual", now, now);
        assertEquals("actual", result.getOrDefault("default"));
    }

    @Test
    void getOrDefaultReturnsDefaultOnFailure() {
        Instant now = Instant.now();
        TaskResult<String> result = TaskResult.failure("t1", new RuntimeException("err"), now, now);
        assertEquals("default", result.getOrDefault("default"));
    }

    @Test
    void toStringOnSuccess() {
        Instant start = Instant.now();
        Instant end = start.plusMillis(10);
        TaskResult<String> result = TaskResult.success("t1", "val", start, end);
        String s = result.toString();
        assertTrue(s.contains("success=true"), "Expected 'success=true' in: " + s);
        assertTrue(s.contains("t1"), "Expected task ID in: " + s);
        assertTrue(s.contains("val"), "Expected value in: " + s);
    }

    @Test
    void toStringOnFailureWithMessage() {
        Instant start = Instant.now();
        Instant end = start.plusMillis(10);
        TaskResult<String> result = TaskResult.failure("t1", new RuntimeException("bad thing"), start, end);
        String s = result.toString();
        assertTrue(s.contains("success=false"), "Expected 'success=false' in: " + s);
        assertTrue(s.contains("bad thing"), "Expected error message in: " + s);
    }

    @Test
    void toStringOnFailureWithNullExceptionMessage() {
        Instant start = Instant.now();
        Instant end = start.plusMillis(10);
        // RuntimeException with null message — toString should fall back to class name
        TaskResult<String> result = TaskResult.failure("t1", new RuntimeException((String) null), start, end);
        String s = result.toString();
        assertTrue(s.contains("success=false"), "Expected 'success=false' in: " + s);
        assertTrue(s.contains("RuntimeException"), "Expected class name fallback in: " + s);
    }
}
