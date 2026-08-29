package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import com.myroboot.support.service.FaqService;
import com.myroboot.support.service.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SupportController {
    private final AuthService authService;
    private final FaqService faqService;
    private final TicketService ticketService;

    public SupportController(AuthService authService, FaqService faqService, TicketService ticketService) {
        this.authService = authService;
        this.faqService = faqService;
        this.ticketService = ticketService;
    }

    @GetMapping("/faq")
    public List<Map<String, Object>> listFaq(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireUser(authorization);
        return faqService.listEnabled();
    }

    @GetMapping("/faq/search")
    public List<Map<String, Object>> searchFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String q) {
        requireUser(authorization);
        return faqService.search(q, 30);
    }

    @GetMapping("/faq/suggest")
    public List<Map<String, Object>> suggestFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String q) {
        requireUser(authorization);
        return faqService.suggest(q, 8);
    }

    @GetMapping("/ticket/similar")
    public List<Map<String, Object>> similarBeforeTicket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String q) {
        requireUser(authorization);
        String keyword = q == null ? "" : q.trim();
        if (keyword.length() < 2) return List.of();
        return faqService.search(keyword, 5);
    }

    @PostMapping("/ticket")
    public Map<String, Object> createTicket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        AuthService.Session session = requireUser(authorization);
        Long id = ticketService.create(session, body);
        return Map.of("success", true, "ticketId", id);
    }

    @GetMapping("/tickets/mine")
    public List<Map<String, Object>> myTickets(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ticketService.listMine(requireUser(authorization));
    }

    @PostMapping("/tickets/{id}/cancel")
    public Map<String, Object> cancelTicket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            return Map.of("success", ticketService.cancel(requireUser(authorization), id, body == null ? Map.of() : body));
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    @GetMapping("/admin/tickets")
    public List<Map<String, Object>> listTickets(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return ticketService.listAdmin();
    }

    @DeleteMapping("/admin/tickets/{id}")
    public Map<String, Object> deleteTicket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        return Map.of("success", ticketService.delete(requireAdmin(authorization), id));
    }

    @PutMapping("/admin/tickets/{id}/status")
    public Map<String, Object> updateTicketStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        boolean success = ticketService.updateStatus(requireAdmin(authorization), id, body);
        return Map.of("success", success);
    }

    @GetMapping("/admin/faqs")
    public List<Map<String, Object>> listAdminFaqs(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return faqService.listAdmin();
    }

    @PostMapping("/admin/faqs")
    public Map<String, Object> createFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        Long id = faqService.create(requireAdmin(authorization), body);
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/admin/faqs/{id}")
    public Map<String, Object> updateFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        faqService.update(requireAdmin(authorization), id, body);
        return Map.of("success", true);
    }

    @DeleteMapping("/admin/faqs/{id}")
    public Map<String, Object> deleteFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        return Map.of("success", faqService.delete(requireAdmin(authorization), id));
    }

    private AuthService.Session requireUser(String authorization) {
        try {
            return authService.require(authorization);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private AuthService.Session requireAdmin(String authorization) {
        try {
            return authService.requireAdmin(authorization);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }
}
