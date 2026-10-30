package com.smartambulance.security;

import com.smartambulance.domain.AppUser;
import com.smartambulance.repo.Repos.UserRepo;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Turns a bearer token into an AuthUser, re-checking in the database that the account is still active. */
@Component
public class TokenAuthenticator {
    private final JwtService jwt;
    private final UserRepo users;

    public TokenAuthenticator(JwtService jwt, UserRepo users) {
        this.jwt = jwt;
        this.users = users;
    }

    public Optional<AuthUser> authenticate(String headerValue) {
        if (headerValue == null) return Optional.empty();
        String token = headerValue.startsWith("Bearer ") ? headerValue.substring(7) : headerValue;
        return jwt.parseUserId(token).flatMap(users::findById).filter(AppUser::isActive)
                .map(u -> new AuthUser(u.getId(), u.getRole(), u.getHospitalId(), u.getName(), u.getEmail()));
    }
}
