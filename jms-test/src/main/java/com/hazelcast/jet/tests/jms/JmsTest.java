/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.tests.jms;

import com.hazelcast.jet.Jet;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.config.ProcessingGuarantee;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.Sources;
import com.hazelcast.jet.tests.common.AbstractSoakTest;
import org.apache.activemq.ActiveMQConnectionFactory;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hazelcast.jet.core.JobStatus.FAILED;
import static com.hazelcast.jet.core.JobStatus.RUNNING;
import static com.hazelcast.jet.tests.common.Util.getJobStatusWithRetry;
import static com.hazelcast.jet.tests.common.Util.sleepMinutes;
import static com.hazelcast.jet.tests.common.Util.waitForJobStatus;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class JmsTest extends AbstractSoakTest {

    private static final double DELAY_AFTER_TEST_FINISHED_FACTOR = 1.05;

    private static final String STABLE_CLUSTER = "-stable";
    private static final String DYNAMIC_CLUSTER = "-dynamic";

    private static final int ASSERTION_RETRY_COUNT = 100;
    private static final String SOURCE_QUEUE = "source";
    private static final String MIDDLE_QUEUE = "middle";
    private static final String SINK_QUEUE = "sink";

    private JetInstance stableClusterClient;

    private String brokerURL;

    public static void main(String[] args) throws Exception {
        new JmsTest().run(args);
    }

    public void init() throws IOException {
        brokerURL = property("brokerURL", "tcp://localhost:61616");

        stableClusterClient = Jet.newJetClient(remoteClusterClientConfig());
    }

    public void test() throws Throwable {
        Throwable[] exceptions = new Throwable[2];
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        executorService.execute(() -> {
            try {
                testInternal(stableClusterClient, STABLE_CLUSTER);
            } catch (Throwable t) {
                logger.severe("Exception in Stable cluster test", t);
                exceptions[0] = t;
            }
        });
        executorService.execute(() -> {
            try {
                testInternal(jet, DYNAMIC_CLUSTER);
            } catch (Throwable t) {
                logger.severe("Exception in Dynamic cluster test", t);
                exceptions[1] = t;
            }
        });
        executorService.shutdown();
        executorService.awaitTermination((long) (durationInMillis * DELAY_AFTER_TEST_FINISHED_FACTOR), MILLISECONDS);

        if (exceptions[0] != null) {
            logger.severe("Exception in Stable cluster test", exceptions[1]);
        }
        if (exceptions[1] != null) {
            logger.severe("Exception in Dynamic cluster test", exceptions[0]);
        }
        if (exceptions[0] != null) {
            throw exceptions[0];
        }
        if (exceptions[1] != null) {
            throw exceptions[1];
        }
    }

    public void testInternal(JetInstance client, String clusterName) throws Exception {
        String localBrokerUrl = brokerURL;

        Pipeline p1 = Pipeline.create();
        p1.readFrom(Sources.jmsQueue(() -> new ActiveMQConnectionFactory(localBrokerUrl), SOURCE_QUEUE + clusterName))
          .withoutTimestamps()
          .writeTo(Sinks.jmsQueue(() -> new ActiveMQConnectionFactory(localBrokerUrl), MIDDLE_QUEUE + clusterName));

        Pipeline p2 = Pipeline.create();
        p2.readFrom(Sources.jmsQueue(() -> new ActiveMQConnectionFactory(localBrokerUrl), MIDDLE_QUEUE + clusterName))
          .withoutTimestamps()
          .writeTo(Sinks.jmsQueue(() -> new ActiveMQConnectionFactory(localBrokerUrl), SINK_QUEUE + clusterName));

        JobConfig jobConfig1 = new JobConfig()
                .setName("JMS Test source to middle queue")
                .setProcessingGuarantee(ProcessingGuarantee.EXACTLY_ONCE);
        Job job1 = client.newJob(p1, jobConfig1);
        waitForJobStatus(job1, RUNNING);
        System.out.println("job1 started");

        JobConfig jobConfig2 = new JobConfig()
                .setName("JMS Test middle to sink queue")
                .setProcessingGuarantee(ProcessingGuarantee.EXACTLY_ONCE);
        Job job2 = client.newJob(p2, jobConfig2);
        waitForJobStatus(job2, RUNNING);
        System.out.println("job2 started");

        JmsMessageProducer producer = new JmsMessageProducer(brokerURL, SOURCE_QUEUE + clusterName);
        producer.start();
        System.out.println("producer started");

        JmsMessageConsumer consumer = new JmsMessageConsumer(brokerURL, SINK_QUEUE + clusterName);
        consumer.start();
        System.out.println("consumer started");

        long begin = System.currentTimeMillis();
        while (System.currentTimeMillis() - begin < durationInMillis) {
            if (getJobStatusWithRetry(job1) == FAILED) {
                job1.join();
            }
            if (getJobStatusWithRetry(job2) == FAILED) {
                job2.join();
            }
            sleepMinutes(1);
        }

        long expectedTotalCount = producer.stop();
        System.out.println("Producer stopped, expectedTotalCount: " + expectedTotalCount);
        assertCountEventually(consumer, expectedTotalCount);
        consumer.stop();
        System.out.println("Consumer stopped");

        job2.cancel();
        System.out.println("Job2 completed");

        job1.cancel();
        System.out.println("Job1 completed");
    }

    protected void teardown(Throwable t) {
        if (stableClusterClient != null) {
            stableClusterClient.shutdown();
        }
    }

    private static void assertCountEventually(JmsMessageConsumer consumer, long expectedTotalCount) throws Exception {
        for (int i = 0; i < ASSERTION_RETRY_COUNT; i++) {
            long actualTotalCount = consumer.getCount();
            System.out.println("expected: " + expectedTotalCount + ", actual: " + actualTotalCount);
            if (expectedTotalCount == actualTotalCount) {
                return;
            }
            SECONDS.sleep(1);
        }
        assertEquals(expectedTotalCount, consumer.getCount());
    }

}
