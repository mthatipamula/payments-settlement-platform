package io.paysettle.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The authenticated caller's name, recorded in the audit trail. */
public final class Actor {

    private Actor() {
    }

    public static String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
