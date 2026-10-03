package hle.org.workqueue.engine;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.*;
class SchemaCheckTest {
    @BeforeEach void reset() { StartupDatabase.reset(); }
    SchemaCheck check() {
        return new SchemaCheck(new WorkItemRepository(new DriverManagerDataSource("jdbc:queue-test:db"), DbTimeouts.defaults(),
            new WorkItemRepository.Settings(ofSeconds(100),5,ofSeconds(5))));
    }
    @Test void validSchemaReturnsItsVerifiedNamespace() { assertThat(check().verify("demo")).isEqualTo("demo"); }
    @Test void migrationMustHaveExactlyOneSuccessfulIdentity() {
        for (long n : new long[]{0,2}) { StartupDatabase.migrations=n; assertThatThrownBy(() -> check().verify("demo")).isInstanceOf(IllegalStateException.class); }
    }
    @Test void metadataMustHaveOneMatchingValidNamespace() {
        for (List<String> values : List.of(List.<String>of(), List.of("other"), List.of("bad:name"),List.of("demo","demo"))) {
            StartupDatabase.namespaces=values; assertThatThrownBy(() -> check().verify("demo")).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void rejectsMissingRuntimeTableAndNonUtcClock() {
        StartupDatabase.missingTable=true;
        assertThatThrownBy(() -> check().verify("demo")).isInstanceOf(IllegalStateException.class);
        StartupDatabase.missingTable=false; StartupDatabase.timezone=new BigDecimal("80000");
        assertThatThrownBy(() -> check().verify("demo")).isInstanceOf(IllegalStateException.class).hasMessageContaining("UTC");
    }
}
