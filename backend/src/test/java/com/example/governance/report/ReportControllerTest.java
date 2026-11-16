package com.example.governance.report;

import com.example.governance.security.AccountPrincipal;
import com.example.governance.security.AuditLog;
import com.example.governance.query.DatasetPolicyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportControllerTest {
    @Test void updateChecksOwnershipAndCurrentDatasetAuthorization() throws Exception {
        var store = mock(ReportStore.class); var policies = mock(DatasetPolicyStore.class); var audit = mock(AuditLog.class); var mapper = new ObjectMapper();
        var controller = new ReportController(store, policies, null, null, null, audit, mapper);
        var auth = UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(10,"v","user","hash",true,List.of()), null, List.of());
        var request = new ReportController.CreateReport(4,"name","TABLE","PREVIEW",mapper.readTree("{\"fields\":[\"name\"],\"limit\":10}"));
        var notFound = assertThrows(ResponseStatusException.class, () -> controller.updateReport(8,request,auth));
        assertEquals(HttpStatus.NOT_FOUND,notFound.getStatusCode()); verify(policies, never()).resolve(anyLong(),anyLong());
        when(store.report(10,8)).thenReturn(Optional.of(new ReportStore.Report(8,4,"old","TABLE","PREVIEW","{}")));
        doThrow(new SecurityException()).when(policies).resolve(10,4);
        var forbidden = assertThrows(ResponseStatusException.class, () -> controller.updateReport(8,request,auth));
        assertEquals(HttpStatus.FORBIDDEN,forbidden.getStatusCode()); verify(store,never()).updateReport(anyLong(),anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void deletesAreOwnerScopedAndDashboardReferenceConflictIsVisible() {
        var store = mock(ReportStore.class); var audit = mock(AuditLog.class);
        var controller = new ReportController(store, null, null, null, null, audit, new ObjectMapper());
        var auth = UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(10,"v","user","hash",true,List.of()),null,List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class, () -> controller.deleteReport(8,auth)).getStatusCode());
        when(store.deleteReport(10,8)).thenThrow(new org.springframework.dao.DataIntegrityViolationException("reference"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class, () -> controller.deleteReport(8,auth)).getStatusCode());
        verify(store,times(2)).deleteReport(10,8); verifyNoInteractions(audit);
    }
    @Test void dashboardConcurrentReferenceRemovalReturnsConflict() {
        var store = mock(ReportStore.class); var audit = mock(AuditLog.class);
        var controller = new ReportController(store, null, null, null, null, audit, new ObjectMapper());
        var auth = UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(10,"v","user","hash",true,List.of()), null, List.of());
        var request = new ReportController.CreateDashboard("dashboard",List.of(8L));
        when(store.createDashboard(10,"dashboard",List.of(8L))).thenThrow(new org.springframework.dao.DataIntegrityViolationException("race"));
        when(store.updateDashboard(10,7,"dashboard",List.of(8L))).thenThrow(new org.springframework.dao.DataIntegrityViolationException("race"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> controller.dashboard(request,auth)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> controller.updateDashboard(7,request,auth)).getStatusCode());
        verifyNoInteractions(audit);
    }
}
