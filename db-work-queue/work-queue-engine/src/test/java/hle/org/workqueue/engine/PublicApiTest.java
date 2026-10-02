package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiTest {
    @Test
    void applicationsCanUseTheDownstreamAndConfigurationContracts() {
        for (Class<?> type : List.of(ExternalService.class, IdempotencyKey.class, CallResult.class,
                WorkQueueProperties.class, WorkQueueProperties.Db.class, ReplayFilter.class)) {
            assertThat(accessible(type)).as(type.getSimpleName()).isTrue();
        }
    }

    @Test
    void applicationsCannotDependOnClaimPersistenceOrTimingImplementation() {
        for (Class<?> type : List.of(WorkItemRepository.class, WorkItemRepository.Settings.class,
                ClaimedItem.class, ClaimKey.class, RenewalResult.class, PersistResult.class,
                BacklogSample.class, Outcome.class, DbTimeouts.class, TimingBudget.class, RenewalSchedule.class)) {
            assertThat(accessible(type)).as(type.getSimpleName()).isFalse();
        }
    }

    private static boolean accessible(Class<?> type) {
        return Modifier.isPublic(type.getModifiers())
                && (type.getEnclosingClass() == null || accessible(type.getEnclosingClass()));
    }
}
