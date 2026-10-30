package com.smartambulance.security;

import com.smartambulance.domain.Enums.Role;

import java.security.Principal;

/** Authenticated caller. getName() is the user id so STOMP user destinations (/user/queue/...) route by id. */
public record AuthUser(long id, Role role, Long hospitalId, String name, String email) implements Principal {
    @Override
    public String getName() {
        return String.valueOf(id);
    }

    public boolean is(Role... roles) {
        for (Role r : roles) if (r == role) return true;
        return false;
    }
}
