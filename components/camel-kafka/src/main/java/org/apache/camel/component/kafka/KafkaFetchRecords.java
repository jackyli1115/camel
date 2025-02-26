/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.kafka;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.apache.camel.component.kafka.consumer.CommitManager;
import org.apache.camel.component.kafka.consumer.CommitManagers;
import org.apache.camel.component.kafka.consumer.errorhandler.KafkaConsumerListener;
import org.apache.camel.component.kafka.consumer.errorhandler.KafkaErrorStrategies;
import org.apache.camel.component.kafka.consumer.support.KafkaConsumerResumeStrategy;
import org.apache.camel.component.kafka.consumer.support.KafkaRecordProcessorFacade;
import org.apache.camel.component.kafka.consumer.support.PartitionAssignmentListener;
import org.apache.camel.component.kafka.consumer.support.ProcessingResult;
import org.apache.camel.component.kafka.consumer.support.ResumeStrategyFactory;
import org.apache.camel.support.BridgeExceptionHandlerToErrorHandler;
import org.apache.camel.support.task.ForegroundTask;
import org.apache.camel.support.task.Tasks;
import org.apache.camel.support.task.budget.Budgets;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ReflectionHelper;
import org.apache.camel.util.TimeUtils;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.internals.ConsumerNetworkClient;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KafkaFetchRecords implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaFetchRecords.class);

    private final KafkaConsumer kafkaConsumer;
    private org.apache.kafka.clients.consumer.Consumer consumer;
    private String clientId;
    private final String topicName;
    private final Pattern topicPattern;
    private final String threadId;
    private final Properties kafkaProps;
    private final Map<String, Long> lastProcessedOffset = new HashMap<>();
    private final PollExceptionStrategy pollExceptionStrategy;
    private final BridgeExceptionHandlerToErrorHandler bridge;
    private final ReentrantLock lock = new ReentrantLock();
    private CommitManager commitManager;
    private Exception lastError;
    private final KafkaConsumerListener consumerListener;

    private boolean terminated;
    private long currentBackoffInterval;
    private boolean reconnect; // must be false at init (this is the policy whether to reconnect)
    private boolean connected; // this is the state (connected or not)

    KafkaFetchRecords(KafkaConsumer kafkaConsumer,
                      BridgeExceptionHandlerToErrorHandler bridge, String topicName, Pattern topicPattern, String id,
                      Properties kafkaProps, KafkaConsumerListener consumerListener) {
        this.kafkaConsumer = kafkaConsumer;
        this.bridge = bridge;
        this.topicName = topicName;
        this.topicPattern = topicPattern;
        this.consumerListener = consumerListener;
        this.threadId = topicName + "-" + "Thread " + id;
        this.kafkaProps = kafkaProps;

        this.pollExceptionStrategy = KafkaErrorStrategies.strategies(this, kafkaConsumer.getEndpoint(), consumer);
    }

    @Override
    public void run() {
        if (!isKafkaConsumerRunnable()) {
            return;
        }

        do {
            terminated = false;

            if (!isConnected()) {

                // task that deals with creating kafka consumer
                currentBackoffInterval = kafkaConsumer.getEndpoint().getComponent().getCreateConsumerBackoffInterval();
                ForegroundTask task = Tasks.foregroundTask()
                        .withName("Create KafkaConsumer")
                        .withBudget(Budgets.iterationBudget()
                                .withMaxIterations(
                                        kafkaConsumer.getEndpoint().getComponent().getCreateConsumerBackoffMaxAttempts())
                                .withInitialDelay(Duration.ZERO)
                                .withInterval(Duration.ofMillis(currentBackoffInterval))
                                .build())
                        .build();
                boolean success = task.run(this::createConsumerTask);
                if (!success) {
                    int max = kafkaConsumer.getEndpoint().getComponent().getCreateConsumerBackoffMaxAttempts();
                    setupCreateConsumerException(task, max);
                    // give up and terminate this consumer
                    terminated = true;
                    break;
                }

                // task that deals with subscribing kafka consumer
                currentBackoffInterval = kafkaConsumer.getEndpoint().getComponent().getSubscribeConsumerBackoffInterval();
                task = Tasks.foregroundTask()
                        .withName("Subscribe KafkaConsumer")
                        .withBudget(Budgets.iterationBudget()
                                .withMaxIterations(
                                        kafkaConsumer.getEndpoint().getComponent().getSubscribeConsumerBackoffMaxAttempts())
                                .withInitialDelay(Duration.ZERO)
                                .withInterval(Duration.ofMillis(currentBackoffInterval))
                                .build())
                        .build();
                success = task.run(this::initializeConsumerTask);
                if (!success) {
                    int max = kafkaConsumer.getEndpoint().getComponent().getCreateConsumerBackoffMaxAttempts();
                    setupInitializeErrorException(task, max);
                    // give up and terminate this consumer
                    terminated = true;
                    break;
                }

                setConnected(true);
            }

            lastError = null;
            startPolling();
        } while ((pollExceptionStrategy.canContinue() || isReconnect()) && isKafkaConsumerRunnable());

        if (LOG.isInfoEnabled()) {
            LOG.info("Terminating KafkaConsumer thread {} receiving from {}", threadId, getPrintableTopic());
        }

        safeUnsubscribe();
        IOHelper.close(consumer);
    }

    private void setupInitializeErrorException(ForegroundTask task, int max) {
        String time = TimeUtils.printDuration(task.elapsed());
        String topic = getPrintableTopic();
        String msg = "Gave up subscribing org.apache.kafka.clients.consumer.KafkaConsumer " +
                     threadId + " to " + topic + " after " + max + " attempts (elapsed: " + time + ").";
        LOG.warn(msg);
        lastError = new KafkaConsumerFatalException(msg, lastError);
    }

    private void setupCreateConsumerException(ForegroundTask task, int max) {
        String time = TimeUtils.printDuration(task.elapsed());
        String topic = getPrintableTopic();
        String msg = "Gave up creating org.apache.kafka.clients.consumer.KafkaConsumer "
                     + threadId + " to " + topic + " after " + max + " attempts (elapsed: " + time + ").";
        lastError = new KafkaConsumerFatalException(msg, lastError);
    }

    private boolean initializeConsumerTask() {
        try {
            initializeConsumer();
        } catch (Exception e) {
            setConnected(false);
            // ensure this is logged so users can see the problem
            LOG.warn("Error subscribing org.apache.kafka.clients.consumer.KafkaConsumer due to: {}", e.getMessage(),
                    e);
            lastError = e;
            return false;
        }

        return true;
    }

    private boolean createConsumerTask() {
        try {
            createConsumer();
            commitManager
                    = CommitManagers.createCommitManager(consumer, kafkaConsumer, threadId, getPrintableTopic());

            if (consumerListener != null) {
                consumerListener.setConsumer(consumer);

                SeekPolicy seekPolicy = kafkaConsumer.getEndpoint().getComponent().getConfiguration().getSeekTo();
                if (seekPolicy == null) {
                    seekPolicy = SeekPolicy.BEGINNING;
                }

                consumerListener.setSeekPolicy(seekPolicy);
            }
        } catch (Exception e) {
            setConnected(false);
            // ensure this is logged so users can see the problem
            LOG.warn("Error creating org.apache.kafka.clients.consumer.KafkaConsumer due to: {}", e.getMessage(),
                    e);
            lastError = e;
            return false;
        }

        return true;
    }

    protected void createConsumer() {
        // create consumer
        ClassLoader threadClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // Kafka uses reflection for loading authentication settings, use its classloader
            Thread.currentThread()
                    .setContextClassLoader(org.apache.kafka.clients.consumer.KafkaConsumer.class.getClassLoader());

            // The Kafka consumer should be null at the first try. For every other reconnection event, it will not
            long delay = kafkaConsumer.getEndpoint().getConfiguration().getPollTimeoutMs();
            final String prefix = this.consumer == null ? "Connecting" : "Reconnecting";
            LOG.info("{} Kafka consumer thread ID {} with poll timeout of {} ms", prefix, threadId, delay);

            // this may throw an exception if something is wrong with kafka consumer
            this.consumer = kafkaConsumer.getEndpoint().getKafkaClientFactory().getConsumer(kafkaProps);

            // init client id which we may need to get from the kafka producer via reflection
            if (clientId == null) {
                clientId = getKafkaProps().getProperty(CommonClientConfigs.CLIENT_ID_CONFIG);
                if (clientId == null) {
                    try {
                        clientId = (String) ReflectionHelper
                                .getField(consumer.getClass().getDeclaredField("clientId"), consumer);
                    } catch (Exception e) {
                        // ignore
                        clientId = "";
                    }
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(threadClassLoader);
        }
    }

    private void initializeConsumer() {
        subscribe();

        // set reconnect to false as the connection and resume is done at this point
        setConnected(false);

        pollExceptionStrategy.reset();
    }

    private void subscribe() {
        KafkaConsumerResumeStrategy resumeStrategy = ResumeStrategyFactory.newResumeStrategy(kafkaConsumer);
        resumeStrategy.setConsumer(consumer);

        PartitionAssignmentListener listener = new PartitionAssignmentListener(
                threadId, kafkaConsumer.getEndpoint().getConfiguration(), lastProcessedOffset,
                this::isRunnable, commitManager, resumeStrategy);

        if (LOG.isInfoEnabled()) {
            LOG.info("Subscribing {} to {}", threadId, getPrintableTopic());
        }

        if (topicPattern != null) {
            consumer.subscribe(topicPattern, listener);
        } else {
            consumer.subscribe(Arrays.asList(topicName.split(",")), listener);
        }
    }

    protected void startPolling() {
        long partitionLastOffset = -1;

        try {
            /*
             * We lock the processing of the record to avoid raising a WakeUpException as a result to a call
             * to stop() or shutdown().
             */
            lock.lock();

            long pollTimeoutMs = kafkaConsumer.getEndpoint().getConfiguration().getPollTimeoutMs();

            if (LOG.isTraceEnabled()) {
                LOG.trace("Polling {} from {} with timeout: {}", threadId, getPrintableTopic(), pollTimeoutMs);
            }

            KafkaRecordProcessorFacade recordProcessorFacade = new KafkaRecordProcessorFacade(
                    kafkaConsumer, lastProcessedOffset, threadId, commitManager, consumerListener);

            Duration pollDuration = Duration.ofMillis(pollTimeoutMs);
            while (isKafkaConsumerRunnable() && isConnected() && pollExceptionStrategy.canContinue()) {
                ConsumerRecords<Object, Object> allRecords = consumer.poll(pollDuration);
                if (consumerListener != null) {
                    if (!consumerListener.afterConsume(consumer)) {
                        continue;
                    }
                }

                commitManager.processAsyncCommits();

                ProcessingResult result = recordProcessorFacade.processPolledRecords(allRecords, consumer);

                if (result.isBreakOnErrorHit()) {
                    LOG.debug("We hit an error ... setting flags to force reconnect");
                    // force re-connect
                    setReconnect(true);
                    setConnected(false);
                }

            }

            if (!isConnected()) {
                LOG.debug("Not reconnecting, check whether to auto-commit or not ...");
                commitManager.commit();
            }

            safeUnsubscribe();
        } catch (InterruptException e) {
            kafkaConsumer.getExceptionHandler().handleException("Interrupted while consuming " + threadId + " from kafka topic",
                    e);
            commitManager.commit();

            LOG.info("Unsubscribing {} from {}", threadId, getPrintableTopic());
            safeUnsubscribe();
            Thread.currentThread().interrupt();
        } catch (WakeupException e) {
            // This is normal: it raises this exception when calling the wakeUp (which happens when we stop)

            if (LOG.isTraceEnabled()) {
                LOG.trace("The kafka consumer was woken up while polling on thread {} for {}", threadId, getPrintableTopic());
            }

            safeUnsubscribe();
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.warn("Exception {} caught while polling {} from kafka {} at offset {}: {}",
                        e.getClass().getName(), threadId, getPrintableTopic(), lastProcessedOffset, e.getMessage(), e);
            } else {
                LOG.warn("Exception {} caught while polling {} from kafka {} at offset {}: {}",
                        e.getClass().getName(), threadId, getPrintableTopic(), lastProcessedOffset, e.getMessage());
            }

            pollExceptionStrategy.handle(partitionLastOffset, e);
        } finally {
            // only close if not retry
            if (!pollExceptionStrategy.canContinue()) {
                LOG.debug("Closing consumer {}", threadId);
                safeUnsubscribe();
                IOHelper.close(consumer);
            }

            lock.unlock();
        }
    }

    private void safeUnsubscribe() {
        if (consumer == null) {
            return;
        }

        final String printableTopic = getPrintableTopic();
        try {
            consumer.unsubscribe();
        } catch (IllegalStateException e) {
            LOG.warn("The consumer is likely already closed. Skipping unsubscribing thread {} from kafka {}", threadId,
                    printableTopic);
        } catch (Exception e) {
            kafkaConsumer.getExceptionHandler().handleException(
                    "Error unsubscribing thread " + threadId + " from kafka " + printableTopic, e);
        }
    }

    /*
     * This is only used for presenting log messages that take into consideration that it might be subscribed to a topic
     * or a topic pattern.
     */
    private String getPrintableTopic() {
        if (topicPattern != null) {
            return "topic pattern " + topicPattern;
        } else {
            return "topic " + topicName;
        }
    }

    private boolean isKafkaConsumerRunnable() {
        return kafkaConsumer.isRunAllowed() && !kafkaConsumer.isStoppingOrStopped()
                && !kafkaConsumer.isSuspendingOrSuspended();
    }

    private boolean isRunnable() {
        return kafkaConsumer.getEndpoint().getCamelContext().isStopping() && !kafkaConsumer.isRunAllowed();
    }

    private boolean isReconnect() {
        return reconnect;
    }

    public void setReconnect(boolean value) {
        reconnect = value;
    }

    /*
     * This wraps a safe stop procedure that should help ensure a clean termination procedure for consumer code.
     * This means that it should wait for the last process call to finish cleanly, including the commit of the
     * record being processed at the current moment.
     *
     * Note: keep in mind that the KafkaConsumer is not thread-safe, so no other call to the consumer instance
     * should be made here besides the wakeUp.
     */
    private void safeStop() {
        if (consumer == null) {
            return;
        }

        long timeout = kafkaConsumer.getEndpoint().getConfiguration().getShutdownTimeout();
        try {
            /*
             Try to wait for the processing to finish before giving up and waking up the Kafka consumer regardless
             of whether the processing have finished or not.
             */
            LOG.info("Waiting up to {} milliseconds for the processing to finish", timeout);
            if (!lock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
                LOG.warn("The processing of the current record did not finish within {} seconds", timeout);
            }

            // As advised in the KAFKA-1894 ticket, calling this wakeup method breaks the infinite loop
            consumer.wakeup();
        } catch (InterruptedException e) {
            consumer.wakeup();
            Thread.currentThread().interrupt();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    void stop() {
        safeStop();
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isPaused() {
        return !consumer.paused().isEmpty();
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isReady() {
        if (!connected) {
            return false;
        }

        boolean ready = true;
        try {
            if (consumer instanceof org.apache.kafka.clients.consumer.KafkaConsumer) {
                // need to use reflection to access the network client which has API to check if the client has ready
                // connections
                org.apache.kafka.clients.consumer.KafkaConsumer kc = (org.apache.kafka.clients.consumer.KafkaConsumer) consumer;
                ConsumerNetworkClient nc
                        = (ConsumerNetworkClient) ReflectionHelper.getField(kc.getClass().getDeclaredField("client"), kc);
                LOG.trace(
                        "Health-Check calling org.apache.kafka.clients.consumer.internals.ConsumerNetworkClient.hasReadyNode");
                ready = nc.hasReadyNodes(System.currentTimeMillis());
            }
        } catch (Exception e) {
            // ignore
            LOG.debug("Cannot check hasReadyNodes on KafkaConsumer client (ConsumerNetworkClient) due to: "
                      + e.getMessage() + ". This exception is ignored.",
                    e);
        }
        return ready;
    }

    Properties getKafkaProps() {
        return kafkaProps;
    }

    String getClientId() {
        return clientId;
    }

    Exception getLastError() {
        return lastError;
    }

    boolean isTerminated() {
        return terminated;
    }

    boolean isRecoverable() {
        return (pollExceptionStrategy.canContinue() || isReconnect()) && isKafkaConsumerRunnable();
    }

    long getCurrentRecoveryInterval() {
        return currentBackoffInterval;
    }

    public BridgeExceptionHandlerToErrorHandler getBridge() {
        return bridge;
    }

    /*
     * This is for manually pausing the consumer. This is mostly used for directly calling pause from Java code
     * or via JMX
     */
    public void pause() {
        consumer.pause(consumer.assignment());
    }

    /*
     * This is for manually resuming the consumer (not to be confused w/ the Resume API). This is
     * mostly used for directly calling resume from Java code or via JMX
     */
    public void resume() {
        consumer.resume(consumer.assignment());
    }
}
