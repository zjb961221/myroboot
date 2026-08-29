package com.myroboot.support.controller;

import com.myroboot.support.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SupportController {
    private final AuthService authService; private final FaqService faqService; private final TicketService ticketService; private final TicketNotificationService notificationService; private final TicketAssignmentService assignmentService;
    public SupportController(AuthService authService,FaqService faqService,TicketService ticketService,TicketNotificationService notificationService,TicketAssignmentService assignmentService){this.authService=authService;this.faqService=faqService;this.ticketService=ticketService;this.notificationService=notificationService;this.assignmentService=assignmentService;}
    @GetMapping("/faq") public List<Map<String,Object>> listFaq(@RequestHeader(value="Authorization",required=false)String a){requireUser(a);return faqService.listEnabled();}
    @GetMapping("/faq/search") public List<Map<String,Object>> searchFaq(@RequestHeader(value="Authorization",required=false)String a,@RequestParam(defaultValue="")String q){requireUser(a);return faqService.search(q,30);}
    @GetMapping("/faq/suggest") public List<Map<String,Object>> suggestFaq(@RequestHeader(value="Authorization",required=false)String a,@RequestParam(defaultValue="")String q){requireUser(a);return faqService.suggest(q,8);}
    @GetMapping("/ticket/similar") public List<Map<String,Object>> similar(@RequestHeader(value="Authorization",required=false)String a,@RequestParam(defaultValue="")String q){requireUser(a);String k=q==null?"":q.trim();return k.length()<2?List.of():faqService.search(k,5);}
    @GetMapping("/ticket/processors") public List<Map<String,Object>> customerProcessors(@RequestHeader(value="Authorization",required=false)String a){AuthService.Session s=requireUser(a);if(!"customer".equals(s.role())&&!"admin".equals(s.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前账号不能提交工单");return ticketService.listAvailableProcessors();}
    @PostMapping("/ticket") public Map<String,Object> createTicket(@RequestHeader(value="Authorization",required=false)String a,@RequestBody Map<String,Object>b){AuthService.Session s=requireUser(a);Long id=ticketService.create(s,b);Long p=longValue(b.get("processorUserId"));if(p!=null)assignmentService.notifyProcessorForNewTicket(id,p);return Map.of("success",true,"ticketId",id,"processorNotificationAttempted",p!=null);}
    @GetMapping("/tickets/mine") public List<Map<String,Object>> myTickets(@RequestHeader(value="Authorization",required=false)String a){return ticketService.listMine(requireUser(a));}
    @PostMapping("/tickets/{id}/cancel") public Map<String,Object> cancel(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id,@RequestBody(required=false)Map<String,Object>b){try{return Map.of("success",ticketService.cancel(requireUser(a),id,b==null?Map.of():b));}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage());}}
    @GetMapping("/admin/tickets") public List<Map<String,Object>> listTickets(@RequestHeader(value="Authorization",required=false)String a){requireAdmin(a);return ticketService.listAdmin();}
    @DeleteMapping("/admin/tickets/{id}") public Map<String,Object> deleteTicket(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id){return Map.of("success",ticketService.delete(requireAdmin(a),id));}
    @PutMapping("/admin/tickets/{id}/status") public Map<String,Object> updateStatus(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id,@RequestBody Map<String,Object>b){boolean success=ticketService.updateStatus(requireAdmin(a),id,b);boolean resolved="resolved".equals(String.valueOf(b.getOrDefault("status","")).trim());if(success&&resolved)notificationService.notifyResolvedAfterCommit(id);return Map.of("success",success,"customerNotificationScheduled",success&&resolved);}
    @GetMapping("/admin/faqs") public List<Map<String,Object>> listAdminFaqs(@RequestHeader(value="Authorization",required=false)String a){requireAdmin(a);return faqService.listAdmin();}
    @PostMapping("/admin/faqs") public Map<String,Object> createFaq(@RequestHeader(value="Authorization",required=false)String a,@RequestBody Map<String,Object>b){Long id=faqService.create(requireAdmin(a),b);return Map.of("success",true,"id",id);}
    @PutMapping("/admin/faqs/{id}") public Map<String,Object> updateFaq(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id,@RequestBody Map<String,Object>b){faqService.update(requireAdmin(a),id,b);return Map.of("success",true);}
    @DeleteMapping("/admin/faqs/{id}") public Map<String,Object> deleteFaq(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id){return Map.of("success",faqService.delete(requireAdmin(a),id));}
    private AuthService.Session requireUser(String a){try{return authService.require(a);}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,e.getMessage());}}
    private AuthService.Session requireAdmin(String a){try{return authService.requireAdmin(a);}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage());}}
    private Long longValue(Object v){if(v instanceof Number n)return n.longValue();try{return Long.parseLong(String.valueOf(v));}catch(Exception e){return null;}}
}
