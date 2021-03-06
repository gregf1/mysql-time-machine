package com.booking.replication.applier;

import com.booking.replication.audit.CheckPointTests;
import com.booking.replication.augmenter.AugmentedRowsEvent;
import com.booking.replication.augmenter.AugmentedSchemaChangeEvent;
import com.booking.replication.pipeline.PipelineOrchestrator;
import com.google.code.or.binlog.impl.event.FormatDescriptionEvent;
import com.google.code.or.binlog.impl.event.QueryEvent;
import com.google.code.or.binlog.impl.event.RotateEvent;
import com.google.code.or.binlog.impl.event.XidEvent;

import java.io.IOException;

/**
 * Created by bosko on 11/14/15.
 */
public interface Applier {

    void bufferData(AugmentedRowsEvent augmentedSingleRowEvent, PipelineOrchestrator caller) throws IOException;

    void applyCommitQueryEvent(QueryEvent event);

    void applyXIDEvent(XidEvent event);

    void applyRotateEvent(RotateEvent event);

    void applyAugmentedSchemaChangeEvent(AugmentedSchemaChangeEvent augmentedSchemaChangeEvent, PipelineOrchestrator caller);

    void forceFlush();

    void resubmitIfThereAreFailedTasks();

    void applyFormatDescriptionEvent(FormatDescriptionEvent event);

    void waitUntilAllRowsAreCommitted(CheckPointTests checkPointTests);

    void dumpStats();
}
