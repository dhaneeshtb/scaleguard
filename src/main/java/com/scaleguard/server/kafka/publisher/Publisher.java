package com.scaleguard.server.kafka.publisher;

public interface Publisher<T> {
    void publish(String topic, T sd);

    void publish(String topic, T sd,int partitionCount);

}
