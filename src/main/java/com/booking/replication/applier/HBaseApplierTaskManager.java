package com.booking.replication.applier;

import com.booking.replication.metrics.ReplicatorMetrics;
import com.booking.replication.util.MutableLong;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class HBaseApplierTaskManager {

    /**
     * Concurrent batch-transaction-based mutations buffer:
     *
     * Buffer is structured by tasks. Each task can have multiple transactions, each transaction can have multiple
     * tables and each table can have multiple mutations. Each task is identified by task UUID. Each transaction is
     * identified with transaction UUID. Task sub-buffers are picked up by flusher threads and on success there
     * are two options:
     *
     *      1. the task UUID key is deleted from the the buffer if all transactions are marked for commit.
     *
     *      2. If there is a transactions not marked for commit (large transactions, so buffer is full before
     *         end of transaction is reached), the new task UUID is created and the transaction UUID of the
     *         unfinished transaction is reserved in the new task-sub-buffer.
     *
     * On task failure, task status is updated to 'WRITE_FAILED' and that task will be retried. The hash structure
     * of single task sub-buffer looks like this:
     *
     *  {
     *      "874c3466-3bf0-422f-a3e3-148289226b6c" => { // <- transaction UUID
     *
     *        table_1 => [@table_1_mutations]
     *        ,...,
     *        table_N => [@table_N_mutations]
     *
     *      },
     *
     *      "187433e5-7b05-47ff-a3bd-633897cd2b4f" => {
     *
     *        table_1 => [@table_1_mutations]
     *        ,...,
     *        table_N => [@table_N_mutations]
     *
     *      },
     *  }
     *
     * Or in short, Perl-like syntax:
     *
     *  $taskBuffer = { $taskUUID => { $transactionUUID => { $tableName => [@Mutations] }}}
     *
     * This works asynchronously for maximum performance. Since transactions are timestamped and they are from RBR
     * we can buffer them in any order. In HBase all of them will be present with corresponding timestamp. And RBR
     * guaranties that each operation is idempotent (so there is no queries that transform data like update value
     * to value * x, which would break the idempotent feature of operations). Simply put, the order of applying of
     * different transactions does not influence the end result since data will be timestamped with timestamps
     * from the binlog and if there are multiple operations on the same row all versions are kept in HBase.
     */

    private final
            ConcurrentHashMap<String, Map<String, Map<String,List<Put>>>>
            taskMutationBuffer = new ConcurrentHashMap<String, Map<String, Map<String,List<Put>>>>();

    private final
    ConcurrentHashMap<String, Map<String, Map<String,List<String>>>>
            taskRowIDS = new ConcurrentHashMap<String, Map<String, Map<String,List<String>>>>();

    /**
     * Futures grouped by task UUID
     */
    private final
            ConcurrentHashMap<String, Future<TaskResult>>
            taskFutures = new ConcurrentHashMap<String,Future<TaskResult>>();

    /**
     * Status tracking helper structures
     */
    private final ConcurrentHashMap<String, Integer> taskStatus        = new ConcurrentHashMap<String,Integer>();
    private final ConcurrentHashMap<String, Integer> transactionStatus = new ConcurrentHashMap<String,Integer>();

    private final ConcurrentHashMap<String, List<String>> taskMessages = new ConcurrentHashMap<>();

    /**
     * Shared connection used by all tasks in applier
     */
    private Connection hbaseConnection;

    /**
     * Task thread pool
     */
    private static ExecutorService taskPool;

    // TODO: add to startup options
    private final int POOL_SIZE;

    // dry run option; TODO: add to startup options
    private final boolean DRY_RUN = false;

    private static volatile String currentTaskUUID;
    private static volatile String currentTransactionUUID;

    // rowsBufferedInCurrentTask is the size of currentTaskUUID buffer. Once this buffer
    // is full, it is submitted and new one is opened with new taskUUID
    public AtomicInteger rowsBufferedInCurrentTask = new AtomicInteger(0);

    private final ReplicatorMetrics replicatorMetrics;

    private final Configuration hbaseConf;

    private static final Logger LOGGER = LoggerFactory.getLogger(HBaseApplierTaskManager.class);

    // ================================================
    // Constructor
    // ================================================
    public HBaseApplierTaskManager(int poolSize, ReplicatorMetrics repMetrics, org.apache.hadoop.conf.Configuration hbaseConfiguration) {

        POOL_SIZE         = poolSize;
        taskPool          = Executors.newFixedThreadPool(POOL_SIZE);

        hbaseConf         = hbaseConfiguration;
        replicatorMetrics = repMetrics;

        try {
            hbaseConnection = ConnectionFactory.createConnection(hbaseConf);
        } catch (IOException e) {
            LOGGER.error("Failed to create hbase connection", e);
        }

        initBuffers();

    }

    public void initBuffers() {

        currentTaskUUID = UUID.randomUUID().toString();
        currentTransactionUUID = UUID.randomUUID().toString();

        taskMutationBuffer.put(currentTaskUUID, new HashMap<String, Map<String, List<Put>>>());
        taskRowIDS.put(currentTaskUUID, new HashMap<String, Map<String, List<String>>>());

        taskMutationBuffer.get(currentTaskUUID).put(currentTransactionUUID, new HashMap<String,List<Put>>());
        taskRowIDS.get(currentTaskUUID).put(currentTransactionUUID, new HashMap<String,List<String>>());

        taskStatus.put(currentTaskUUID, TaskStatusCatalog.READY_FOR_BUFFERING);
        transactionStatus.put(currentTransactionUUID, TransactionStatus.OPEN);
    }

    // ================================================
    // Buffering util
    // ================================================
    public void pushMutationToTaskBuffer(String tableName, String hbRowKey, Put put) {

        if (taskMutationBuffer.get(currentTaskUUID) == null) {
            LOGGER.error("ERROR: Missing task UUID from taskMutationBuffer keySet. Should never happen. Shutting down...");
            System.exit(1);
        }
        if (taskRowIDS.get(currentTaskUUID) == null) {
            LOGGER.error("ERROR: Missing task UUID from taskRowIDS keySet. Should never happen. Shutting down...");
            System.exit(1);
        }
        if (taskMutationBuffer.get(currentTaskUUID).get(currentTransactionUUID) == null) {
            LOGGER.error("ERROR: Missing transaction UUID from taskMutationBuffer keySet. Should never happen. Shutting down...");
            System.exit(1);
        }
        if (taskRowIDS.get(currentTaskUUID).get(currentTransactionUUID) == null) {
            LOGGER.error("ERROR: Missing transaction UUID from taskRowIDS keySet. Should never happen. Shutting down...");
            for (String tid : taskRowIDS.get(currentTaskUUID).keySet()) {
                LOGGER.info("current task => " + currentTaskUUID + ", current tid => " + currentTransactionUUID + ", available tid => " + tid);
            }
            System.exit(1);
        }

        // in case of delta tables, delta table key will belong to the same task and transaction
        // as corresponding mirrored table
        if (taskMutationBuffer.get(currentTaskUUID).get(currentTransactionUUID).get(tableName) == null) {
            taskMutationBuffer.get(currentTaskUUID).get(currentTransactionUUID).put(tableName, new ArrayList<Put>());
            taskRowIDS.get(currentTaskUUID).get(currentTransactionUUID).put(tableName, new ArrayList<String>());
            // LOGGER.info("New table in uuid buffer " + uuid + ", initialized key for table " + tableName );
        }

        taskMutationBuffer.get(currentTaskUUID).get(currentTransactionUUID).get(tableName).add(put);
        taskRowIDS.get(currentTaskUUID).get(currentTransactionUUID).get(tableName).add(hbRowKey);

        rowsBufferedInCurrentTask.incrementAndGet();
    }

    // ================================================
    // Flushing util
    // ================================================
    public void markCurrentTransactionForCommit() {

        // mark
        transactionStatus.put(currentTransactionUUID, TransactionStatus.READY_FOR_COMMIT);

        // open a new transaction slot and set it as the current transaction
        currentTransactionUUID = UUID.randomUUID().toString();
        taskMutationBuffer.get(currentTaskUUID).put(currentTransactionUUID, new HashMap<String,List<Put>>());
        taskRowIDS.get(currentTaskUUID).put(currentTransactionUUID, new HashMap<String,List<String>>());
        transactionStatus.put(currentTransactionUUID, TransactionStatus.OPEN);
    }

    public void markCurrentTaskAsReadyAndCreateNewUUIDBuffer() {

        // don't create new buffers if no slots available
        blockIfNoSlotsAvailableForBuffering();

        // mark current uuid buffer as READY_FOR_PICK_UP unless there are no
        // rows buffered (then just keep the buffer ready for next binlog file)
        if (rowsBufferedInCurrentTask.get() > 0) {
            taskStatus.put(currentTaskUUID, TaskStatusCatalog.READY_FOR_PICK_UP);
        }
        else {
            return;
        }

        // create new uuid buffer
        String newTaskUUID = UUID.randomUUID().toString();

        taskMessages.put(newTaskUUID, new ArrayList<String>());

        taskMutationBuffer.put(newTaskUUID, new HashMap<String, Map<String, List<Put>>>());
        taskRowIDS.put(newTaskUUID, new HashMap<String, Map<String, List<String>>>());

        // Check if there is an open/unfinished transaction in current UUID task buffer and
        // if so, create/reserve the corresponding transaction UUID in the new UUID task buffer
        // so that the transaction rows that are on the way can be buffered under the same UUID.
        // This is a foundation for the TODO: when XID event is received and the end of transaction tie
        // the transaction id from XID with the transaction UUID used for buffering. The goal is
        // to be able to identify mutations in HBase which were part of the same transaction.
        int openTransactions = 0;
        for (String transactionUUID : taskMutationBuffer.get(currentTaskUUID).keySet()) {
            if (transactionStatus.get(transactionUUID) == TransactionStatus.OPEN) {
                openTransactions++;
                if (openTransactions > 1) {
                    LOGGER.error("More than one partial transaction in the buffer. Should never happen! Exiting...");
                    System.exit(-1);
                }
                taskMutationBuffer.get(newTaskUUID).put(transactionUUID, new HashMap<String,List<Put>>() );
                taskRowIDS.get(newTaskUUID).put(transactionUUID, new HashMap<String,List<String>>() );
                currentTransactionUUID = transactionUUID; // <- important
            }
        }

        taskStatus.put(newTaskUUID, TaskStatusCatalog.READY_FOR_BUFFERING);

        currentTaskUUID = newTaskUUID;

        rowsBufferedInCurrentTask.set(0);

        // update task queue size
        long taskQueueSize = 0;
        for (String taskUUID : taskStatus.keySet()) {
            if (taskStatus.get(taskUUID) == TaskStatusCatalog.READY_FOR_PICK_UP) {
                taskQueueSize++;
            }
        }
        replicatorMetrics.setTaskQueueSize(taskQueueSize);
    }

    public void markAllTasksAsReadyToGo() {

        // mark current uuid buffer as READY_FOR_PICK_UP
        int nubmerOfTasksLeft = taskStatus.keySet().size();

        LOGGER.info("Tasks left: " + nubmerOfTasksLeft);

        if (nubmerOfTasksLeft == 0) {
            replicatorMetrics.setTaskQueueSize(0L);
            return;
        }
        else {
            for (String taskUUID : taskStatus.keySet()) {

                if (taskStatus.get(taskUUID) == TaskStatusCatalog.WRITE_IN_PROGRESS) {
                    LOGGER.info("task " + taskUUID + " => " + "WRITE_IN_PROGRESS");
                }
                else if (taskStatus.get(taskUUID) == TaskStatusCatalog.WRITE_FAILED) {
                    LOGGER.info("task " + taskUUID + " => " + "WRITE_FAILED");
                    //requeueTask(taskUUID); // todo: update task queue size
                }
                else if (taskStatus.get(taskUUID) == TaskStatusCatalog.TASK_SUBMITTED) {
                    LOGGER.info("task " + taskUUID + " => " + "TASK_SUBMITTED");
                }
                else if (taskStatus.get(taskUUID) == TaskStatusCatalog.READY_FOR_PICK_UP) {
                    LOGGER.info("task " + taskUUID + " => " + "READY_FOR_PICK_UP");
                }
                else if (taskStatus.get(taskUUID) == TaskStatusCatalog.READY_FOR_BUFFERING) {
                    LOGGER.info("task " + taskUUID + " => " + "READY_FOR_BUFFERING");
                    if (taskHasRowsBuffered(taskUUID)) {
                        taskStatus.put(taskUUID, TaskStatusCatalog.READY_FOR_PICK_UP);
                        LOGGER.info("Marked task " + taskUUID + " as READY_FOR_PICK_UP");
                    }
                    else {
                        // cant flush empty task
                        taskStatus.remove(taskUUID);
                        taskMutationBuffer.remove(taskUUID);
                        taskRowIDS.remove(taskUUID);
                        taskMessages.remove(taskUUID);
                        if (taskFutures.containsKey(taskUUID)) {
                            taskFutures.remove(taskUUID);
                        }
                    }
                }
                else if (taskStatus.get(taskUUID) == TaskStatusCatalog.WRITE_SUCCEEDED) {
                    LOGGER.info("task " + taskUUID + " => " + "WRITE_SUCCEEDED");
                }
                else {
                    LOGGER.info("task " + taskUUID + " => " + "UNKNOWN STATUS => " + taskStatus.get(taskUUID));
                }
            }
        }
    }

    private void blockIfNoSlotsAvailableForBuffering() {

        boolean block = true;
        int blockingTime = 0;

        while (block) {

            updateTaskStatuses();

            int currentNumberOfTasks = taskMutationBuffer.keySet().size();

            if (currentNumberOfTasks > POOL_SIZE) {
                try {
                    Thread.sleep(5);
                    blockingTime += 5;
                }
                catch (InterruptedException e) {
                    LOGGER.error("Cant sleep.", e);
                }
                if ((blockingTime % 500) == 0) {
                    LOGGER.warn("To many tasks already open ( " + currentNumberOfTasks + " ), blocking time is " + blockingTime + "ms");
                }
            }
            else {
                block = false;
            }
        }
    }

    public void updateTaskStatuses() {

        // clean up and re-queue failed tasks
        Set<String> taskFuturesUUIDs = taskFutures.keySet();

        // Loop submitted tasks
        for (String submittedTaskUUID : taskFuturesUUIDs) {

            Future<TaskResult> taskFuture;

            try {

                taskFuture = taskFutures.get(submittedTaskUUID);

                // Process done tasks
                if (taskFuture.isDone()) {

                    LOGGER.info("Task " + submittedTaskUUID + " is done");

                    TaskResult taskResult = taskFuture.get(); // raise exceptions if any
                    boolean taskSucceeded = taskResult.isTaskSucceeded();
                    long numberOfRowsInTask = taskResult.getNumberOfRowsInTask();

                    int statusOfDoneTask = taskStatus.get(submittedTaskUUID);

                    if (statusOfDoneTask == TaskStatusCatalog.WRITE_SUCCEEDED) {
                        if (!taskSucceeded) {
                            throw new Exception("Inconsistent success reports for task " + submittedTaskUUID);
                        }

                        replicatorMetrics.incApplierTasksSucceededCounter();
                        replicatorMetrics.addToRowOpsSuccessfullyCommited(numberOfRowsInTask);

                        if (taskResult.getTableStats() != null) {
                            for (String table : taskResult.getTableStats().keySet()) {
                                long rowOpsCommitedForTable = taskResult.getTableStats().get(table).getValue();
                                LOGGER.info("Row ops committed for table " + table + " => " + rowOpsCommitedForTable);
                                replicatorMetrics.incTotalRowOpsSuccessfullyCommited(rowOpsCommitedForTable, table);
                            }
                        }
                        else {
                            LOGGER.error("No table stats in task result!");
                        }

                        taskStatus.remove(submittedTaskUUID);
                        taskMessages.remove(submittedTaskUUID);

                        // the following two are structured by task-transaction, so
                        // if there is an open transaction UUID in this task, it has
                        // already been copied to the new/next task
                        taskMutationBuffer.remove(submittedTaskUUID);
                        taskRowIDS.remove(submittedTaskUUID);

                        // since the task is done, remove the key from the futures hash
                        taskFutures.remove(submittedTaskUUID);


                    } else if (statusOfDoneTask == TaskStatusCatalog.WRITE_FAILED) {
                        if (taskSucceeded) {
                            throw new Exception("Inconsistent success reports for task " + submittedTaskUUID);
                        }
                        LOGGER.warn("Task " + submittedTaskUUID + " failed. Task will be retried.");
                        requeueTask(submittedTaskUUID);
                        replicatorMetrics.incApplierTasksFailedCounter();
                    }
                    else {
                        LOGGER.error("Illegal task status ["
                                + statusOfDoneTask
                                + "]. Probably a silent death of a thread. "
                                + "Will consider the task as failed and re-queue.");
                        requeueTask(submittedTaskUUID);
                        replicatorMetrics.incApplierTasksFailedCounter();
                    }
                }
            }
            catch (ExecutionException ex) {
                LOGGER.error("Future failed for task " + submittedTaskUUID + ", with exception " + ex.getCause().toString());
                requeueTask(submittedTaskUUID);
                replicatorMetrics.incApplierTasksFailedCounter();
            } catch (InterruptedException ei) {
                LOGGER.info("Task " + submittedTaskUUID + " was canceled by interrupt. The task that has been canceled will be retired later by another future.", ei);
                requeueTask(submittedTaskUUID);
                replicatorMetrics.incApplierTasksFailedCounter();
            } catch (Exception e) {
                LOGGER.error("Inconsistent success reports for task " + submittedTaskUUID + ". Will retry the task.");
                requeueTask(submittedTaskUUID);
                replicatorMetrics.incApplierTasksFailedCounter();
            }
        }
    }

    /**
     * requeueTask
     *
     * @param failedTaskUUID
     */
    private void requeueTask(String failedTaskUUID) {

        // remove the key from the futures hash; new future will be created
        taskFutures.remove(failedTaskUUID);

        // keep the mutation buffer, just change the status so this task is picked up again
        taskStatus.put(failedTaskUUID, TaskStatusCatalog.READY_FOR_PICK_UP);
    }

    private boolean taskHasRowsBuffered(String taskUUID) {

        boolean taskHasTransactions = false;
        boolean taskHasRows = false;

        Map<String, Map<String, List<Put>>> task = taskMutationBuffer.get(taskUUID);

        Set<String> transactionIDs = task.keySet();
        if (transactionIDs != null) {
            taskHasTransactions = true;
            for (String transactionUUID : transactionIDs) {
                Set<String> transactionTables = task.get(transactionUUID).keySet();
                if (transactionTables != null) {
                    for (String tableName : transactionTables) {
                        List<Put> bufferedOPS = task.get(transactionUUID).get(tableName);
                        if (bufferedOPS != null && bufferedOPS.size() > 0) {
                            taskHasRows = true;
                        }
                        else {
                            LOGGER.info("Table " + tableName + " has no rows!!!");
                        }
                    }
                }
                else {
                    LOGGER.warn("Transaction " + transactionUUID + " has no tables!!!");
                }
            }
        }
        else {
            LOGGER.warn("No transactions on task " + taskUUID);
        }

        return taskHasRows;
    }

    /**
     * Submit tasks that are READY_FOR_PICK_UP
     */
    public void submitTasksThatAreReadyForPickUp() {

        if (hbaseConnection == null) {
            LOGGER.info("HBase connection is gone. Will try to recreate new connection...");
            int retry = 10;
            while (retry > 0) {
                try {
                    hbaseConnection = ConnectionFactory.createConnection(hbaseConf);
                    retry = 0;
                } catch (IOException e) {
                    LOGGER.warn("Failed to create hbase connection from HBaseApplier, attempt " + retry + "/10");
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    LOGGER.error("Thread wont sleep. Not a good day for you.",e);
                }
                retry--;
            }
        }

        if (hbaseConnection == null) {
            LOGGER.error("Could not create HBase connection, all retry attempts failed. Exiting...");
            System.exit(-1);
        }

        // one future per task
        for (final String taskUUID : taskStatus.keySet()) {

            boolean taskHasTransactions = false;
            boolean taskHasRows = false;

            Map<String, Map<String, List<Put>>> task = taskMutationBuffer.get(taskUUID);

            if (task != null) {
                Set<String> transactionIDs = task.keySet();
                if (transactionIDs != null) {
                    taskHasTransactions = true;
                    for (String transactionUUID : transactionIDs) {
                        Set<String> transactionTables = task.get(transactionUUID).keySet();
                        if (transactionTables != null) {
                            for (String tableName : transactionTables) {
                                List<Put> bufferedOPS = task.get(transactionUUID).get(tableName);
                                if (bufferedOPS != null && bufferedOPS.size() > 0) {
                                    taskHasRows = true;
                                }
                                else {
                                    LOGGER.info("Table " + tableName + " has no rows!!!");
                                }
                            }
                        }
                        else {
                            LOGGER.warn("Transaction " + transactionUUID + " has no tables!!!");
                        }
                    }
                }
                else {
                    LOGGER.warn("No transactions on task " + taskUUID);
                }
            }
            else {
                LOGGER.warn("task is null");
            }

            if ((taskStatus.get(taskUUID) == TaskStatusCatalog.READY_FOR_PICK_UP)) {
                if (taskHasTransactions && taskHasRows) {

                    LOGGER.info("Submitting task " + taskUUID);

                    taskStatus.put(taskUUID, TaskStatusCatalog.TASK_SUBMITTED);

                    replicatorMetrics.incApplierTasksSubmittedCounter();

                    taskFutures.put(taskUUID, taskPool.submit(new Callable<TaskResult>() {

                        @Override
                        public TaskResult call() throws Exception {

                            try {
                                long numberOfRowsInTask = 0;

                                HashMap<String, MutableLong> tableStats = new HashMap<String, MutableLong>();

                                for (String transactionUUID : taskRowIDS.get(taskUUID).keySet()) {
                                    for (String tableName : taskRowIDS.get(taskUUID).get(transactionUUID).keySet()) {
                                        List<String> bufferedIDs = taskRowIDS.get(taskUUID).get(transactionUUID).get(tableName);
                                        long numberOfBufferedIDsForTable = bufferedIDs.size();
                                        numberOfRowsInTask += numberOfBufferedIDsForTable;
                                        if (tableStats.get(tableName) == null) {
                                            tableStats.put(tableName, new MutableLong(numberOfBufferedIDsForTable));
                                        } else {
                                            tableStats.get(tableName).addValue(numberOfBufferedIDsForTable);
                                        }
                                    }
                                }

                                LOGGER.info("Number of rows in task " + taskUUID + " => " + numberOfRowsInTask);

                                if (DRY_RUN) {
                                    taskStatus.put(taskUUID, TaskStatusCatalog.WRITE_SUCCEEDED);
                                    TaskResult taskResult = new TaskResult(taskUUID, true, numberOfRowsInTask, tableStats);
                                    return taskResult;
                                }

                                if (taskMessages.get(taskUUID) == null) {
                                    taskMessages.put(taskUUID, new ArrayList<String>());
                                }

                                ChaosMonkey chaosMonkey = new ChaosMonkey();

                                if (chaosMonkey.feelsLikeThrowingExceptionAfterTaskSubmitted()) {
                                    throw new Exception("Chaos monkey exception for submitted task!");
                                }
                                if (chaosMonkey.feelsLikeFailingSubmitedTaskWithoutException()) {
                                    taskStatus.put(taskUUID, TaskStatusCatalog.WRITE_FAILED);
                                    TaskResult taskResult = new TaskResult(taskUUID, false, numberOfRowsInTask, tableStats);
                                    return taskResult;
                                }

                                taskStatus.put(taskUUID, TaskStatusCatalog.WRITE_IN_PROGRESS);
                                replicatorMetrics.incApplierTasksInProgressCounter();

                                if (chaosMonkey.feelsLikeThrowingExceptionForTaskInProgress()) {
                                    throw new Exception("Chaos monkey exception for task in progress!");
                                }
                                if (chaosMonkey.feelsLikeFailingTaskInProgessWithoutException()) {
                                    taskStatus.put(taskUUID, TaskStatusCatalog.WRITE_FAILED);
                                    TaskResult taskResult = new TaskResult(taskUUID, false, numberOfRowsInTask, tableStats);
                                    return taskResult;
                                }

                                for (final String transactionUUID : taskMutationBuffer.get(taskUUID).keySet()) {

                                    int numberOfTablesInCurrentTransaction = taskMutationBuffer.get(taskUUID).get(transactionUUID).keySet().size();

                                    int numberOfFlushedTablesInCurrentTransaction = 0;

                                    for (final String bufferedTableName : taskMutationBuffer.get(taskUUID).get(transactionUUID).keySet()) {

                                        TableName TABLE = TableName.valueOf(bufferedTableName);
                                        Table hbaseTable = hbaseConnection.getTable(TABLE);

                                        if (chaosMonkey.feelsLikeThrowingExceptionBeforeFlushingData()) {
                                            throw new Exception("Chaos monkey is here to prevent call to flush!!!");
                                        } else if (chaosMonkey.feelsLikeFailingDataFlushWithoutException()) {
                                            taskStatus.put(taskUUID, TaskStatusCatalog.WRITE_FAILED);
                                            TaskResult taskResult = new TaskResult(taskUUID, false, numberOfRowsInTask, tableStats);
                                            return taskResult;
                                        } else {
                                            hbaseTable.put(taskMutationBuffer.get(taskUUID).get(transactionUUID).get(bufferedTableName));
                                            numberOfFlushedTablesInCurrentTransaction++;
                                        }
                                    } // next table

                                    // exception listener callback report
                                    if (taskMessages.get(taskUUID).size() > 0) {
                                        taskStatus.put(taskUUID, TaskStatusCatalog.WRITE_FAILED);
                                        TaskResult taskResult = new TaskResult(taskUUID, false, numberOfRowsInTask, tableStats);
                                        return taskResult;
                                    }

                                    // data integrity check
                                    if (numberOfTablesInCurrentTransaction != numberOfFlushedTablesInCurrentTransaction) {
                                        taskStatus.put(taskUUID, TaskStatusCatalog.WRITE_FAILED);
                                        TaskResult taskResult = new TaskResult(taskUUID, false, numberOfRowsInTask, tableStats);
                                        return taskResult;
                                    }
                                } // next transaction

                                taskStatus.put(taskUUID, TaskStatusCatalog.WRITE_SUCCEEDED);
                                TaskResult taskResult = new TaskResult(taskUUID, true, numberOfRowsInTask, tableStats);
                                return taskResult;
                            } catch (NullPointerException e) {
                                LOGGER.error("NullPointerException in future", e);
                                e.printStackTrace();
                                System.exit(1);
                            }
                            return null;
                        }
                    }));
                } else {
                    LOGGER.error("Task is marked as READY_FOR_PICK_UP, but has no rows. Exiting...");
                    System.exit(1);
                }
            }
        }
    }
}
