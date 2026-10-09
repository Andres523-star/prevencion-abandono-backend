package com.ucc.abandono.service;

import com.ucc.abandono.model.entity.Usuario;
import com.ucc.abandono.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    @Autowired
    private UsuarioRepository usuarioRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public Usuario registrar(String nombre, String email, String password) {
        if (usuarioRepository.findByEmail(email).isPresent()) {
            throw new RuntimeException("El email ya está registrado");
        }
        Usuario u = Usuario.builder()
                .nombre(nombre)
                .email(email)
                .password(encoder.encode(password))
                .rol("CONSEJERO")
                .build();
        return usuarioRepository.save(u);
    }

    public Usuario login(String email, String password) {
        Optional<Usuario> opt = usuarioRepository.findByEmail(email);
        if (opt.isEmpty()) return null;
        Usuario u = opt.get();
        if (!encoder.matches(password, u.getPassword())) return null;
        return u;
    }
}