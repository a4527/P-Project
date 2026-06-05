package com.smartparking.server.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.smartparking.server.dto.LoginRequest;
import com.smartparking.server.dto.LoginResponse;
import com.smartparking.server.entity.User;
import com.smartparking.server.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public String register(LoginRequest req) {
        // 이미 존재하는 사용자라면 → 예외 대신 문자열 반환
        if (userRepository.findByUsername(req.getUsername()).isPresent()) {
            return "USER_EXISTS";
        }

        User u = new User();
        u.setUsername(req.getUsername());
        u.setPassword(passwordEncoder.encode(req.getPassword()));
        userRepository.save(u);

        return "REGISTER_SUCCESS";
    }

    public LoginResponse login(LoginRequest req) {
        User u = userRepository.findByUsername(req.getUsername())
            .orElse(null);

        if (u == null) {
            throw new IllegalArgumentException("USER_NOT_FOUND");
        }

        if (!passwordEncoder.matches(req.getPassword(), u.getPassword())) {
            throw new IllegalArgumentException("WRONG_PASSWORD");
        }

        return new LoginResponse(jwtUtil.generateToken(u.getUsername()), u.getUsername());
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username).orElse(null);
    }
}
