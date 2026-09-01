package com.myroboot.support.controller;
import com.myroboot.support.service.*;import org.springframework.http.HttpStatus;import org.springframework.web.bind.annotation.*;import org.springframework.web.server.ResponseStatusException;import java.util.*;
@RestController @RequestMapping("/api/admin/maintenance-notices") public class MaintenanceNoticeController{
 private final AuthService auth;private final MaintenanceNoticeService service;public MaintenanceNoticeController(AuthService a,MaintenanceNoticeService s){auth=a;service=s;}
 @GetMapping public List<Map<String,Object>> list(@RequestHeader(value="Authorization",required=false)String a){admin(a);return service.list();}
 @GetMapping("/preview") public Map<String,Object> preview(@RequestHeader(value="Authorization",required=false)String a){admin(a);return service.preview();}
 @PostMapping public Map<String,Object> create(@RequestHeader(value="Authorization",required=false)String a,@RequestBody Map<String,Object>b){long id=service.create(admin(a),b);return Map.of("id",id);}
 @PostMapping("/{id}/send") public Map<String,Object> send(@RequestHeader(value="Authorization",required=false)String a,@PathVariable long id,@RequestBody(required=false)Map<String,Object>b){service.send(admin(a),id,b!=null&&Boolean.TRUE.equals(b.get("retryFailed")));return Map.of("ok",true,"status","sending");}
 @GetMapping("/{id}/recipients") public List<Map<String,Object>> recipients(@RequestHeader(value="Authorization",required=false)String a,@PathVariable long id){admin(a);return service.recipients(id);}
 private AuthService.Session admin(String a){try{return auth.requireAdmin(a);}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage());}}
}
