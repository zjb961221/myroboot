package com.myroboot.support.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class TicketServiceTest {

    @Test
    void customerCannotCancelAnotherUsersTicket() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TicketService service = new TicketService(jdbc);
        AuthService.Session customer = session(10L, "customer");

        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("user_id", 99L);
        row.put("status", "pending");
        row.put("is_deleted", 0);
        when(jdbc.queryForList("SELECT id,user_id,status,is_deleted FROM support_ticket WHERE id=? FOR UPDATE", 1L))
                .thenReturn(List.of(row));

        assertThrows(SecurityException.class, () -> service.cancel(customer, 1L, Map.of()));
    }

    @Test
    void resolvedTicketCannotBeCancelled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TicketService service = new TicketService(jdbc);
        AuthService.Session customer = session(10L, "customer");

        Map<String, Object> row = new HashMap<>();
        row.put("id", 2L);
        row.put("user_id", 10L);
        row.put("status", "resolved");
        row.put("is_deleted", 0);
        when(jdbc.queryForList("SELECT id,user_id,status,is_deleted FROM support_ticket WHERE id=? FOR UPDATE", 2L))
                .thenReturn(List.of(row));

        assertThrows(IllegalArgumentException.class, () -> service.cancel(customer, 2L, Map.of()));
    }

    @Test
    void adminDeleteIsSoftDeleteAndKeepsAuditTrail() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TicketService service = new TicketService(jdbc);
        AuthService.Session admin = session(7L, "admin");

        Map<String, Object> row = new HashMap<>();
        row.put("id", 3L);
        row.put("is_deleted", 0);
        when(jdbc.queryForList("SELECT id,is_deleted FROM support_ticket WHERE id=? FOR UPDATE", 3L))
                .thenReturn(List.of(row));
        when(jdbc.update("UPDATE support_ticket SET is_deleted=1,deleted_time=NOW(),deleted_by=? WHERE id=? AND is_deleted=0", 7L, 3L))
                .thenReturn(1);

        assertTrue(service.delete(admin, 3L));
        verify(jdbc).update("UPDATE support_ticket SET is_deleted=1,deleted_time=NOW(),deleted_by=? WHERE id=? AND is_deleted=0", 7L, 3L);
        verify(jdbc).update(
                "INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,0)",
                3L, 7L, "tester", "deleted", "管理员已将该工单移出正常列表");
    }

    private AuthService.Session session(Long id, String role) {
        return new AuthService.Session(id, "tester", role, "tester", "", "", "");
    }
}
