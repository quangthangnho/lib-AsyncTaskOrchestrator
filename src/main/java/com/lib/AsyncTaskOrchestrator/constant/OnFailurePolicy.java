package com.lib.AsyncTaskOrchestrator.constant;

public enum OnFailurePolicy {
    /**
     * Throw an exception immediately when a task fails.
     */
    THROW,

    /**
     * Skip the failed task and continue with others (returns null for the failed result).
     */
    SKIP
}
