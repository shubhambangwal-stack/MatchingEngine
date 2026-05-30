package com.pearl.astrology.match.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic userCreatedTopic() {
        return TopicBuilder.name("user-created")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic dailyBatchReadyTopic() {
        return TopicBuilder.name("match.daily_batch_ready")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic userCreatedDltTopic() {
        return TopicBuilder.name("user-created.DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic profileCompletedTopic() {
        return TopicBuilder.name("profile-completed")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic profileCompletedDltTopic() {
        return TopicBuilder.name("profile-completed.DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
