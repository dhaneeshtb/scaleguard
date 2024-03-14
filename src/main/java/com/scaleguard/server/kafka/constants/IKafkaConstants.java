package com.scaleguard.server.kafka.constants;


import com.scaleguard.server.kafka.partitioner.CustomPartitioner;
import org.apache.kafka.clients.admin.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;

public class IKafkaConstants {
    private IKafkaConstants(){}
    private static final Logger LOGGER = LoggerFactory.getLogger(IKafkaConstants.class);
    private static final Object LOCK = new Object();
    public static final String KAFKA_BROKERS;
    private static AdminClient adminClient = null;


    public static final String KAFKA_DEFAULT_PARTITIONER = CustomPartitioner.class.getName();

    private static final String KAFKA_BROKERS_CNST = "KAFKA_BROKERS";
    private static String asyncStreamName = null;

    static {
        if (System.getenv(KAFKA_BROKERS_CNST) != null && !System.getenv(KAFKA_BROKERS_CNST).isEmpty()) {
            KAFKA_BROKERS = System.getenv(KAFKA_BROKERS_CNST);
        }else {
            KAFKA_BROKERS = "";
        }
        if (adminClient == null && !KAFKA_BROKERS.isEmpty()) {
            final Properties properties = new Properties();
            properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, IKafkaConstants.KAFKA_BROKERS);
            adminClient = AdminClient.create(properties);
        }
    }

    /**
     * Common Kafka Properties/Constants
     */
    public static final Integer MAX_NO_MESSAGE_FOUND_COUNT = 100;
    public static final String OFFSET_RESET_LATEST = "latest";
    public static final String OFFSET_RESET_EARLIER = "earliest";
    public static final Integer MAX_POLL_RECORDS = 1;
    public static final String APPLICATION_ID_CONFIG = "stream-counter";

    public static final int PARTITION_COUNT = 5;
    public static final short REPLICATION_FACTOR = 1;

    /**
     * Event Topic Constants
     */

    public static final String POST_FIX_EVENTS = "-event-streams";
    public static final String INTEGRATION_CLIENT_ID = "client1";


    public static String getEventTopicName(String lob) {
        return lob + POST_FIX_EVENTS;
    }








    public static void createTopic(String topicName) {
        final var newTopic = new NewTopic(topicName, PARTITION_COUNT, REPLICATION_FACTOR);
        try {
            Set<String> existingTopics = adminClient.listTopics().names().get();
            if (existingTopics.contains(topicName)) {
                Map<String, TopicDescription> res = adminClient.describeTopics(Collections.singletonList(topicName)).all().get();
                if (res.get(topicName).partitions().size() < PARTITION_COUNT) {
                    updatePartitions(topicName);
                } else {
                    LOGGER.debug("Skipping update partitions for {}, as reduction of partitions is not a smart move",topicName);
                }
            } else {
                LOGGER.info("Creating Topic {} with {} partitions", topicName ,PARTITION_COUNT );
                adminClient.createTopics(Collections.singletonList(newTopic)).all().get();
            }
        } catch (ExecutionException e) {
        	LOGGER.error(e.getMessage(),e);
        }catch (InterruptedException e) {
        	LOGGER.error(e.getMessage(),e);
        	Thread.currentThread().interrupt();
		}
    }

    private static void updatePartitions(String topicName) {
        LOGGER.info("Updating Partitions for {} to {} partitions",topicName,PARTITION_COUNT);
        Map<String, NewPartitions> newPartitionSet = new HashMap<>();
        newPartitionSet.put(topicName, NewPartitions.increaseTo(PARTITION_COUNT));
        adminClient.createPartitions(newPartitionSet);
    }



}
