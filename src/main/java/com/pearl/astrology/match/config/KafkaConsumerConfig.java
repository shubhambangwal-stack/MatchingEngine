package com.pearl.astrology.match.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import org.springframework.kafka.annotation.EnableKafka;

@EnableKafka
@Configuration
@Slf4j
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            CommonErrorHandler commonErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(commonErrorHandler);
        return factory;
    }

    @Bean
    public CommonErrorHandler commonErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        // Dead Letter Queue recoverer sends failed messages to topicName + ".DLT"
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> {
                    log.error("Recovering message from topic={} partition={} offset={} to DLT due to error: {}",
                            record.topic(), record.partition(), record.offset(), ex.getMessage());
                    return new TopicPartition(record.topic() + ".DLT", 0); // Send to partition 0 of DLT
                });

        // Set up Exponential Backoff retry: initial 2s, max 10s, multiplier 2.0, max 3 attempts
        ExponentialBackOff backOff = new ExponentialBackOff(2000L, 2.0);
        backOff.setMaxInterval(10000L);
        
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.setBackOffFunction((record, ex) -> {
            log.warn("Retrying message from topic={} partition={} offset={} due to error: {}",
                    record.topic(), record.partition(), record.offset(), ex.getMessage());
            return backOff;
        });

        return errorHandler;
    }
}
