package com.example.governance.workflow;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowSchedulePollerTest {
    @Test void waitsForReadinessAndContinuesAfterOneScheduleFails() {
        var schedules = mock(WorkflowScheduleStore.class);
        var dispatcher = mock(WorkflowDispatcher.class);
        var poller = new WorkflowSchedulePoller(schedules, dispatcher);
        poller.poll(); verifyNoInteractions(schedules);
        when(schedules.due(any(), eq(100))).thenReturn(List.of(1L, 2L));
        when(schedules.fireDue(eq(1L), any())).thenThrow(new IllegalStateException());
        when(schedules.fireDue(eq(2L), any())).thenReturn(Optional.of(44L));
        poller.ready(); poller.poll();
        verify(dispatcher).dispatch(44L);
    }
}
