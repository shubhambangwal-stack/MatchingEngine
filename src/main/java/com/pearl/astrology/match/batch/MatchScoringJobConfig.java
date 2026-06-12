package com.pearl.astrology.match.batch;

import com.pearl.astrology.match.entity.DailyMatchQueue;
import com.pearl.astrology.match.entity.Match;
import com.pearl.astrology.match.repository.DailyMatchQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.item.support.CompositeItemProcessor;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.dao.TransientDataAccessException;

@Configuration
@RequiredArgsConstructor
public class MatchScoringJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DailyMatchQueueRepository dailyMatchQueueRepository;
    
    private final CandidateFetchProcessor candidateFetchProcessor;
    private final CompatibilityScoringProcessor compatibilityScoringProcessor;
    private final MatchMongoWriter matchMongoWriter;
    private final PublishBatchReadyTasklet publishBatchReadyTasklet;

    @Value("${match.scoring.chunk-size:50}")
    private int chunkSize;

    @Value("${match.scoring.core-pool-size:4}")
    private int corePoolSize;

    @Value("${match.scoring.max-pool-size:8}")
    private int maxPoolSize;

    @Value("${match.scoring.queue-capacity:100}")
    private int queueCapacity;

    @Value("${match.scoring.throttle-limit:4}")
    private int throttleLimit;

    @Bean
    public Job matchScoringJob() {
        return new JobBuilder("matchScoringJob", jobRepository)
                .start(scoringStep())
                .next(notifyStep())
                .build();
    }

    @Bean
    public Step scoringStep() {
        return new StepBuilder("scoringStep", jobRepository)
                .<DailyMatchQueue, UserMatchResult>chunk(chunkSize, transactionManager)
                .reader(synchronizedQueueReader())
                .processor(compositeProcessor())
                .writer(matchMongoWriter)
                .faultTolerant()
                .skip(Exception.class) // Skip bad/malformed records
                .skipLimit(100)        // Maximum skipped items before failing
                .retry(TransientDataAccessException.class) // Retry transient DB drops
                .retryLimit(3)
                .taskExecutor(batchTaskExecutor())
                .throttleLimit(throttleLimit)
                .build();
    }

    @Bean
    public SynchronizedItemStreamReader<DailyMatchQueue> synchronizedQueueReader() {
        SynchronizedItemStreamReader<DailyMatchQueue> reader = new SynchronizedItemStreamReader<>();
        reader.setDelegate(queueReader());
        return reader;
    }

    @Bean
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("batch-thread-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Step notifyStep() {
        return new StepBuilder("notifyStep", jobRepository)
                .tasklet(publishBatchReadyTasklet, transactionManager)
                .build();
    }

    @Bean
    public RepositoryItemReader<DailyMatchQueue> queueReader() {
        return new RepositoryItemReaderBuilder<DailyMatchQueue>()
                .name("queueReader")
                .repository(dailyMatchQueueRepository)
                .methodName("findByQueueDateAndProcessedFalse")
                .arguments(Collections.singletonList(LocalDate.now()))
                .pageSize(chunkSize)
                .sorts(Map.of("id", Sort.Direction.ASC))
                .build();
    }

    @Bean
    public CompositeItemProcessor<DailyMatchQueue, UserMatchResult> compositeProcessor() {
        CompositeItemProcessor<DailyMatchQueue, UserMatchResult> processor = new CompositeItemProcessor<>();
        processor.setDelegates(List.of(
                candidateFetchProcessor,
                compatibilityScoringProcessor
        ));
        return processor;
    }
}
