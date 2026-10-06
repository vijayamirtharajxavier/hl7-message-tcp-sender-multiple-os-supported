package io.hl7sender.core.auth;

/** Something a signed-in user may be allowed to do. */
public enum Permission {
    /** See destinations, queues, history, logs and the dashboard. */
    VIEW,
    /** Send and queue messages, replay history, run schedules and load tests. */
    SEND,
    /** Requeue, edit and delete messages; pause and resume destinations. */
    MANAGE_QUEUE,
    /** Add, change and remove destinations, schedules, alerts and other settings. */
    CONFIGURE,
    /** Add, change and remove users. */
    ADMIN_USERS
}
