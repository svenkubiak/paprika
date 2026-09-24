package models;

public record Stats(
        long collections,
        long records,
        long tenants,

        /**
         * Responses of 500 and above the active tenant's request log holds for the last 24 hours,
         * hook executions included. Unlike the counts above this one is a number that is supposed
         * to be zero, so the dashboard can show it as a problem when it is not.
         */
        long serverErrors24h,
        long uptimeSeconds
) {}
