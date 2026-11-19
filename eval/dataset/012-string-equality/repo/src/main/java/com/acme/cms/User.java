package com.acme.cms;

public class User {

    private final Long id;
    private final String role;

    public User(Long id, String role) {
        this.id = id;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    /** Role name as stored in the database, e.g. "EDITOR". */
    public String getRole() {
        return role;
    }
}
