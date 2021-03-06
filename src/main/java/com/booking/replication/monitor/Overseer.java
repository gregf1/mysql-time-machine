package com.booking.replication.monitor;

import com.booking.replication.Constants;
import com.booking.replication.metrics.ReplicatorMetrics;
import com.booking.replication.pipeline.PipelineOrchestrator;
import com.booking.replication.pipeline.BinlogEventProducer;
import com.booking.replication.pipeline.BinlogPositionInfo;
import com.booking.replication.metrics.Metric;
import com.booking.replication.util.MutableLong;
import com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.rmi.ConnectException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import java.io.*;
import java.net.*;

/**
 * Created by bdevetak on 26/11/15.
 */
public class Overseer extends Thread {

    private PipelineOrchestrator pipelineOrchestrator;
    private BinlogEventProducer producer;
    private final ConcurrentHashMap<Integer,Object> lastKnownInfo;
    private final ReplicatorMetrics replicatorMetrics;

    private volatile boolean doMonitor = true;

    private int observedStatus = ObservedStatus.OK;

    private static final Logger LOGGER = LoggerFactory.getLogger(Overseer.class);

    public Overseer(BinlogEventProducer prod, PipelineOrchestrator orch, ReplicatorMetrics repMetrics, ConcurrentHashMap<Integer, Object> chm) {
        this.producer      = prod;
        this.pipelineOrchestrator = orch;
        this.lastKnownInfo = chm;
        this.replicatorMetrics = repMetrics;
    }

    @Override
    public void run() {
        while (doMonitor) {

            try {
                // make sure that producer is running every 1s
                Thread.sleep(1000);
                makeSureProducerIsRunning();
                String graphiteStatsNamespace = pipelineOrchestrator.configuration.getGraphiteStatsNamesapce();
                if (!graphiteStatsNamespace.equals("no-stats")) {
                    LOGGER.debug("processStats");
                    processStats();
                }
                // TODO: add status checks for pipelineOrchestrator and applier

            } catch (InterruptedException e) {
                LOGGER.error("Overseer thread interrupted", e);
                doMonitor = false;
            }
        }
    }

    public void stopMonitoring() {
        doMonitor = false;
    }

    public void startMonitoring() {
        doMonitor = true;
    }

    private void makeSureProducerIsRunning() {
        if (!producer.getOr().isRunning()) {
            LOGGER.warn("Producer stopped running. OR position: "
                    + ((BinlogPositionInfo) lastKnownInfo.get(Constants.LAST_KNOWN_BINLOG_POSITION)).getBinlogFilename()
                    + ":"
                    + ((BinlogPositionInfo) lastKnownInfo.get(Constants.LAST_KNOWN_BINLOG_POSITION)).getBinlogPosition()
                    + "Trying to restart it...");
            try {
                BinlogPositionInfo lastMapEventFakeMCounter = (BinlogPositionInfo) lastKnownInfo.get(Constants.LAST_KNOWN_MAP_EVENT_POSITION_FAKE_MICROSECONDS_COUNTER);
                Long   lastFakeMCounter = lastMapEventFakeMCounter.getFakeMicrosecondsCounter();

                pipelineOrchestrator.setFakeMicrosecondCounter(lastFakeMCounter);

                producer.startOpenReplicatorFromLastKnownMapEventPosition();
                LOGGER.info("Restarted open replicator to run from position "
                        + producer.getOr().getBinlogFileName()
                        + ":"
                        + producer.getOr().getBinlogPosition()
                );
            }
            catch (ConnectException e) {
                LOGGER.error("Overseer tried to restart OpenReplicator and failed. Can not continue running. Requesting shutdown...");
                observedStatus = ObservedStatus.ERROR_SHOULD_SHUTDOWN;
                System.exit(-1);
            }
            catch (Exception e) {
                LOGGER.warn("Exception while trying to restart OpenReplicator", e);
                e.printStackTrace();
            }
        }
        else {
            LOGGER.debug("MonitorCheck: producer is running.");
        }
    }

    // TODO: move this out of Overseer (it should only monitor state of other threads
    private void processStats() {

        int currentTimeSeconds = (int) (System.currentTimeMillis() / 1000L);

        List<String> metrics = new ArrayList<String>();

        String graphiteStatsNamespace = pipelineOrchestrator.configuration.getGraphiteStatsNamesapce();

        String dbAlias;

        if (pipelineOrchestrator.configuration.getReplicantShardID() > 0) {
            dbAlias = pipelineOrchestrator.configuration.getReplicantSchemaName()
                    + String.valueOf(pipelineOrchestrator.configuration.getReplicantShardID());
        }
        else {
            dbAlias = pipelineOrchestrator.configuration.getReplicantSchemaName();
        }

        // metrics per table (only for delta tables)
        if (pipelineOrchestrator.configuration.isWriteRecentChangesToDeltaTables()) {
            if (!graphiteStatsNamespace.equals("no-stats")) {
                List<String> deltaTables = pipelineOrchestrator.configuration.getTablesForWhichToTrackDailyChanges();
                HashMap<Integer, MutableLong> tableTotals;
                for (String table : deltaTables) {
                    if (replicatorMetrics.getTotalsPerTable().containsKey(table)) {
                        tableTotals = replicatorMetrics.getTotalsPerTable().get(table);
                        if (tableTotals != null) {
                            for (Integer metricID : tableTotals.keySet()) {
                                Long value = tableTotals.get(metricID).getValue();
                                String graphitePoint = graphiteStatsNamespace
                                        + "."
                                        + dbAlias
                                        + "."
                                        + table
                                        + "."
                                        + Metric.getCounterName(metricID)
                                        + " " + value.toString()
                                        + " " + currentTimeSeconds;

                                metrics.add(graphitePoint);
                            }
                        }
                    }
                }
            }
        }


        // time bucket metric
        for (Integer timebucket : replicatorMetrics.getMetrics().keySet()) {

            if (timebucket <  currentTimeSeconds) {

                LOGGER.debug("processing stats for bucket => " + timebucket + " since < then " + currentTimeSeconds);

                HashMap<Integer,MutableLong> timebucketStats;
                timebucketStats = replicatorMetrics.getMetrics().get(timebucket);
                if (timebucketStats != null) {
                    // all is good
                }
                else {
                    LOGGER.warn("Metrics missing for timebucket " + timebucket);
                    return;
                }

                for (Integer metricsID : timebucketStats.keySet()) {

                    Long value = timebucketStats.get(metricsID).getValue();

                    if (!graphiteStatsNamespace.equals("no-stats")) {
                        String graphitePoint;

                        graphitePoint = graphiteStatsNamespace
                                + "."
                                + dbAlias
                                + "."
                                + Metric.getCounterName(metricsID)
                                + " " + value.toString()
                                + " " + timebucket.toString();

                        LOGGER.debug("graphite point => " + graphitePoint);

                        metrics.add(graphitePoint);
                    }
                }
                String message = Joiner.on("\n").join(metrics) + "\n";
                LOGGER.debug("Graphite metrics from processed second => " + message);
                sendToGraphite(message);
                replicatorMetrics.getMetrics().remove(timebucket);
            }
        }
    }

    private void sendToGraphite(String message) {

        DatagramSocket sock = null;
        int port = 3002;

        try {

           sock = new DatagramSocket();

           InetAddress host = InetAddress.getByName("localhost");

           // send
           byte[] b = message.getBytes();
           DatagramPacket  dp = new DatagramPacket(b , b.length , host , port);
           sock.send(dp);
            
        }
        catch(IOException e) {
            LOGGER.warn("Graphite IOException ", e);
        }
    }
}
