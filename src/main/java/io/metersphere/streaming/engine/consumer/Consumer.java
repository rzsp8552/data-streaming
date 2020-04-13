package io.metersphere.streaming.engine.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.metersphere.streaming.model.Metric;
import io.metersphere.streaming.service.TestResultService;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class Consumer {
    public static final String CONSUME_ID = "metric-data";
    public static final Integer QUEUE_SIZE = 1000;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private TestResultService testResultService;
    private final CopyOnWriteArrayList<Metric> metrics = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Metric> metricQueue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private boolean isRunning = true;


    @KafkaListener(id = CONSUME_ID, topics = "${kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<?, String> record) throws Exception {
        Metric metric = objectMapper.readValue(record.value(), Metric.class);
        testResultService.saveDetail(metric);
        metricQueue.put(metric);
    }

    @PreDestroy
    public void preDestroy() {
        isRunning = false;
    }

    @PostConstruct
    public void handleQueue() {
        new Thread(() -> {
            while (isRunning) {
                try {
                    Metric metric = metricQueue.take();
                    metrics.add(metric);
                    // 长度达到 queue_size save 一次
                    int size = metrics.size();
                    if (size >= QUEUE_SIZE) {
                        save();
                    }
                } catch (Exception e) {
                }
            }
        }).start();
    }

    @PostConstruct
    public void handleSave() {
        new Thread(() -> {
            while (isRunning) {
                try {
                    // 确保 metrics 全部被保存
                    int size = metrics.size();
                    if (metricQueue.isEmpty() && size > 0 && size < QUEUE_SIZE) {
                        save();
                    }
                    Thread.sleep(10 * 1000);
                } catch (Exception e) {
                }
            }
        }).start();
    }


    public synchronized void save() {
        Map<String, List<Metric>> reportMetrics = metrics.stream().collect(Collectors.groupingBy(Metric::getReportId));
        reportMetrics.forEach((r, ms) -> {
            Map<String, List<Metric>> rMetrics = ms.stream().collect(Collectors.groupingBy(this::fetchGroupKey));
            rMetrics.forEach((s, m) -> {
                Metric metric = m.stream().findFirst().get();
                testResultService.save(metric);
            });
        });
        // 清空 list
        metrics.clear();
    }

    private String fetchGroupKey(Metric metric) {
        // todo 处理分组字段
        // 每个报告分组字段, 每秒, url, response-code
        Date timestamp = metric.getTimestamp();
        return StringUtils.joinWith("|", timestamp.getTime() / 1000, metric.getUrl(), metric.getResponseCode());
    }

}
