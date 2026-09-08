package models;

public record Stats(
        boolean connected,
        boolean healthy,
        long collections,
        long records,
        long tenants,
        long uptimeSeconds
) {}
