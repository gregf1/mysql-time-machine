package com.booking.replication.applier;

/**
 * Created by bdevetak on 07/12/15.
 */
public class UUIDBufferStatus {
    public static final int READY_FOR_BUFFERING = 0;
    public static final int READY_FOR_PICK_UP   = 1;
    public static final int WRITE_IN_PROGRESS   = 2;
    public static final int WRITE_FAILED        = 3;
    public static final int WRITE_SUCCEEDED     = 4;
}
