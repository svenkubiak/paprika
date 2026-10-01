package models;

public record Stats(
        long collections,
        long records,
        long tenants,
        long serverErrors24h,
        long uptimeSeconds
) {}
