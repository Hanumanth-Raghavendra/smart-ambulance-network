package com.smartambulance.web;

import com.smartambulance.domain.AppUser;
import com.smartambulance.domain.Enums.Role;
import com.smartambulance.dto.Commands.Login;
import com.smartambulance.dto.Commands.Register;
import com.smartambulance.dto.Mappers;
import com.smartambulance.dto.Views.UserView;
import com.smartambulance.repo.Repos.UserRepo;
import com.smartambulance.security.AuthUser;
import com.smartambulance.security.JwtService;
import com.smartambulance.service.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record AuthResponse(String token, UserView user) {}

    private final UserRepo users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserRepo users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /** Public sign-up is limited to patients and road drivers; staff accounts are created by an admin. */
    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody Register body) {
        Role role = body.role() == null ? Role.PATIENT : body.role();
        if (role != Role.PATIENT && role != Role.DRIVER) {
            throw ApiException.forbidden("Only patients and road drivers can sign up. Staff accounts are created by an administrator.");
        }
        if (users.existsByEmailIgnoreCase(body.email())) throw ApiException.conflict("That email is already registered");
        AppUser u = new AppUser();
        u.setName(body.name().trim());
        u.setEmail(body.email().trim().toLowerCase());
        u.setPasswordHash(encoder.encode(body.password()));
        u.setPhone(body.phone());
        u.setRole(role);
        u = users.save(u);
        return new AuthResponse(jwt.issue(u), Mappers.user(u));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody Login body) {
        AppUser u = users.findByEmailIgnoreCase(body.email().trim())
                .filter(x -> encoder.matches(body.password(), x.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Wrong email or password"));
        if (!u.isActive()) throw new ApiException(HttpStatus.FORBIDDEN, "This account has been disabled");
        return new AuthResponse(jwt.issue(u), Mappers.user(u));
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthUser me) {
        return users.findById(me.id()).map(Mappers::user).orElseThrow(() -> ApiException.notFound("User not found"));
    }
}
