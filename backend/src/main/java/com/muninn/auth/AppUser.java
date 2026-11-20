package com.muninn.auth;

import java.util.UUID;

public record AppUser(UUID id, String email, String displayName, Role role) {

    public enum Role {
        ADMIN,
        USER
    }
}
