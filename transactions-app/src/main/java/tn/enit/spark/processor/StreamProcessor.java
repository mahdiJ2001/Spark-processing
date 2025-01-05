package tn.enit.spark.processor;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.*;
import org.apache.spark.streaming.kafka010.*;
import tn.enit.spark.entity.Transaction;
import tn.enit.spark.util.PropertyFileReader;
import tn.enit.spark.util.TransactionDataDeserializer;

import java.util.*;

public class StreamProcessor {

    public static void main(String[] args) throws Exception {
        String file = "transactions-processor.properties";
        Properties prop = PropertyFileReader.readPropertyFile(file);

        SparkConf conf = new SparkConf()
                .setAppName(prop.getProperty("tn.enit.transactions.spark.app.name"))
                .setMaster(prop.getProperty("tn.enit.transactions.spark.master"))
                .set("spark.cassandra.connection.host", prop.getProperty("tn.enit.transactions.cassandra.host"))
                .set("spark.cassandra.connection.port", prop.getProperty("tn.enit.transactions.cassandra.port"))
                .set("spark.cassandra.auth.username", prop.getProperty("tn.enit.transactions.cassandra.username"))
                .set("spark.cassandra.auth.password", prop.getProperty("tn.enit.transactions.cassandra.password"));

        JavaStreamingContext streamingContext = new JavaStreamingContext(conf, Durations.seconds(10));
        JavaSparkContext sc = streamingContext.sparkContext();
        streamingContext.checkpoint(prop.getProperty("tn.enit.transactions.spark.checkpoint.dir"));

        Map<String, Object> kafkaParams = new HashMap<>();
        kafkaParams.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, prop.getProperty("tn.enit.transactions.brokerlist"));
        kafkaParams.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        kafkaParams.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, TransactionDataDeserializer.class);
        kafkaParams.put(ConsumerConfig.GROUP_ID_CONFIG, prop.getProperty("tn.enit.transactions.group.id"));
        kafkaParams.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, prop.getProperty("tn.enit.transactions.resetType"));
        kafkaParams.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        Collection<String> topics = Arrays.asList(prop.getProperty("tn.enit.transactions.topic"));

        JavaInputDStream<ConsumerRecord<String, Transaction>> stream = KafkaUtils.createDirectStream(
                streamingContext,
                LocationStrategies.PreferConsistent(),
                ConsumerStrategies.<String, Transaction>Subscribe(topics, kafkaParams)
        );

        // Map to get only the Transaction objects
        JavaDStream<Transaction> transactionStream = stream.map(ConsumerRecord::value);

        transactionStream.foreachRDD(rdd -> {
            if (!rdd.isEmpty()) {
                System.out.println("Received " + rdd.count() + " transactions");

                // Save transactions to Cassandra
                TransactionProcessor.saveTransactionsToCassandra(rdd); // Pass the rdd

                // Save transactions to HDFS
                SparkSession sparkSession = SparkSession.builder().config(conf).getOrCreate();
                String hdfsPath = prop.getProperty("tn.enit.transactions.hdfs") + "transactions/";
                TransactionProcessor.saveTransactionsToHDFS(rdd, hdfsPath, sparkSession); // Pass the rdd

            } else {
                System.out.println("No transactions received in this batch");
            }
        });

        // Print incoming transactions for debugging
        transactionStream.print();

        streamingContext.start();
        streamingContext.awaitTermination();
    }
}
