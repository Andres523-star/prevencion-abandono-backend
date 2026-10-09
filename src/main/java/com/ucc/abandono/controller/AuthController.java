package com.ucc.abandono.controller;

import com.ucc.abandono.model.entity.Usuario;
import com.ucc.abandono.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        try {
            Usuario u = authService.registrar(
                    body.get("nombre"),
                    body.get("email"),
                    body.get("password")
            );
            return ResponseEntity.ok(toResponse(u));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        Usuario u = authService.login(body.get("email"), body.get("password"));
        if (u == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Credenciales inválidas"));
        }
        return ResponseEntity.ok(toResponse(u));
    }

    private Map<String, Object> toResponse(Usuario u) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", u.getId());
        r.put("nombre", u.getNombre());
        r.put("email", u.getEmail());
        r.put("rol", u.getRol());
        return r;
    }
}