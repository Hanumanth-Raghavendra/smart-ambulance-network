package com.smartambulance.ws;

import com.smartambulance.domain.Ambulance;
import com.smartambulance.domain.Enums.Role;
import com.smartambulance.repo.Repos.AmbulanceRepo;
import com.smartambulance.repo.Repos.RequestRepo;
import com.smartambulance.security.AuthUser;
import com.smartambulance.security.TokenAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;

/** Authenticates the STOMP CONNECT frame with the JWT and authorises every SUBSCRIBE by role. */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

    private final TokenAuthenticator authenticator;
    private final RequestRepo requests;
    private final AmbulanceRepo ambulances;
    private final RequestAccess access;

    public StompAuthInterceptor(TokenAuthenticator authenticator, RequestRepo requests, AmbulanceRepo ambulances,
                                RequestAccess access) {
        this.authenticator = authenticator;
        this.requests = requests;
        this.ambulances = ambulances;
        this.access = access;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null || acc.getCommand() == null) return message;

        if (acc.getCommand() == StompCommand.CONNECT) {
            AuthUser u = authenticator.authenticate(acc.getFirstNativeHeader("Authorization"))
                    .orElseThrow(() -> new MessagingException("Unauthorized"));
            acc.setUser(new UsernamePasswordAuthenticationToken(u, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + u.role().name()))));
        } else if (acc.getCommand() == StompCommand.SUBSCRIBE) {
            AuthUser u = userOf(acc.getUser());
            String dest = acc.getDestination();
            if (u == null || dest == null || !canSubscribe(u, dest)) {
                log.warn("Rejected subscription to {} by {}", dest, u == null ? "anonymous" : u.email());
                return null;   // drop the frame; the connection stays open
            }
        } else if (acc.getCommand() == StompCommand.SEND) {
            return null;       // clients never publish; all traffic is server -> client
        }
        return message;
    }

    private static AuthUser userOf(Principal p) {
        if (p instanceof UsernamePasswordAuthenticationToken t && t.getPrincipal() instanceof AuthUser u) return u;
        return null;
    }

    boolean canSubscribe(AuthUser u, String dest) {
        if (dest.startsWith("/user/queue/")) return true;
        if (dest.equals("/topic/signals") || dest.equals("/topic/fleet")) {
            return u.is(Role.POLICE, Role.ADMIN, Role.HOSPITAL, Role.AMBULANCE);
        }
        if (dest.equals("/topic/police")) return u.is(Role.POLICE, Role.ADMIN);
        Long id = lastId(dest);
        if (id == null) return false;
        if (dest.startsWith("/topic/hospital/")) {
            return u.is(Role.ADMIN) || (u.is(Role.HOSPITAL) && id.equals(u.hospitalId()));
        }
        if (dest.startsWith("/topic/ambulance/")) {
            return u.is(Role.ADMIN) || (u.is(Role.AMBULANCE)
                    && ambulances.findById(id).map(Ambulance::getDriverId).map(d -> d == u.id()).orElse(false));
        }
        if (dest.startsWith("/topic/request/")) {
            return requests.findById(id).map(r -> access.canView(u, r)).orElse(false);
        }
        return false;
    }

    private static Long lastId(String dest) {
        try {
            return Long.parseLong(dest.substring(dest.lastIndexOf('/') + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
