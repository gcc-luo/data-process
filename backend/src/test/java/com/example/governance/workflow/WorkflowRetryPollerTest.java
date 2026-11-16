package com.example.governance.workflow;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowRetryPollerTest {
    @Test void dispatchesOnlyRetriesActivatedAfterReadiness() {
        var runs = mock(WorkflowRunStore.class); var dispatcher = mock(WorkflowDispatcher.class);
        var poller = new WorkflowRetryPoller(runs, dispatcher);
        poller.poll(); verifyNoInteractions(runs);
        when(runs.dueRetries(any(), eq(100))).thenReturn(List.of(1L, 2L));
        when(runs.activateDueRetries(eq(1L), any())).thenReturn(true);
        when(runs.activateDueRetries(eq(2L), any())).thenReturn(false);
        poller.ready(); poller.poll();
        verify(dispatcher).dispatch(1); verify(dispatcher, never()).dispatch(2);
    }
}
