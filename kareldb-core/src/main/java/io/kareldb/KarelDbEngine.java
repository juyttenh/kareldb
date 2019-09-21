/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kareldb;

import io.kareldb.kafka.KafkaSchema;
import io.kareldb.schema.Schema;
import io.kareldb.transaction.KarelDbCommitTable;
import io.kareldb.transaction.KarelDbTimestampStorage;
import io.kareldb.transaction.client.KarelDbTransactionManager;
import io.kcache.Cache;
import io.kcache.KafkaCache;
import io.kcache.KafkaCacheConfig;
import io.kcache.utils.Caches;
import io.kcache.utils.InMemoryCache;
import org.apache.kafka.common.Configurable;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Utils;
import org.apache.omid.committable.CommitTable;
import org.apache.omid.timestamp.storage.TimestampStorage;
import org.apache.omid.transaction.RollbackException;
import org.apache.omid.transaction.Transaction;
import org.apache.omid.transaction.TransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class KarelDbEngine implements Configurable, Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(KarelDbEngine.class);

    private KarelDbConfig config;
    private KarelDbTransactionManager transactionManager;
    private Schema schema;
    private final AtomicBoolean initialized = new AtomicBoolean();

    private static KarelDbEngine INSTANCE;

    public synchronized static KarelDbEngine getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new KarelDbEngine();
        }
        return INSTANCE;
    }

    public synchronized static void closeInstance() {
        if (INSTANCE != null) {
            try {
                INSTANCE.close();
            } catch (IOException e) {
                LOG.warn("Could not close engine", e);
            }
            INSTANCE = null;
        }
    }

    private KarelDbEngine() {
    }

    public void configure(Map<String, ?> configs) {
        this.config = new KarelDbConfig(configs);
    }

    public void init() {
        Map<String, Object> configs = config.originals();
        String bootstrapServers = (String) configs.get(KafkaCacheConfig.KAFKACACHE_BOOTSTRAP_SERVERS_CONFIG);
        Cache<Long, Long> commits;
        if (bootstrapServers != null) {
            // TODO make configurable
            String id = "_commits";
            configs.put(KafkaCacheConfig.KAFKACACHE_TOPIC_CONFIG, id);
            configs.put(KafkaCacheConfig.KAFKACACHE_GROUP_ID_CONFIG, id);
            configs.put(KafkaCacheConfig.KAFKACACHE_CLIENT_ID_CONFIG, id);
            commits = new KafkaCache<>(
                new KafkaCacheConfig(configs), Serdes.Long(), Serdes.Long(), null,
                new InMemoryCache<>());
        } else {
            commits = new InMemoryCache<>();
        }
        commits = Caches.concurrentCache(commits);
        commits.init();
        Cache<Long, Long> timestamps;
        if (bootstrapServers != null) {
            // TODO make configurable
            String id = "_timestamps";
            configs.put(KafkaCacheConfig.KAFKACACHE_TOPIC_CONFIG, id);
            configs.put(KafkaCacheConfig.KAFKACACHE_GROUP_ID_CONFIG, id);
            configs.put(KafkaCacheConfig.KAFKACACHE_CLIENT_ID_CONFIG, id);
            timestamps = new KafkaCache<>(
                new KafkaCacheConfig(configs), Serdes.Long(), Serdes.Long(), null,
                new InMemoryCache<>());
        } else {
            timestamps = new InMemoryCache<>();
        }
        timestamps = Caches.concurrentCache(timestamps);
        timestamps.init();
        CommitTable commitTable = new KarelDbCommitTable(commits);
        TimestampStorage timestampStorage = new KarelDbTimestampStorage(timestamps);
        transactionManager = KarelDbTransactionManager.newInstance(commitTable, timestampStorage);
        String schemaClass = (String) configs.getOrDefault("kind", KafkaSchema.class.getName());
        schema = getConfiguredInstance(schemaClass, configs);
        schema.init();
        boolean isInitialized = initialized.compareAndSet(false, true);
        if (!isInitialized) {
            throw new IllegalStateException("Illegal state while initializing engine. Engine "
                + "was already initialized");
        }
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public Schema getSchema() {
        return schema;
    }

    public KarelDbTransactionManager getTxManager() {
        return transactionManager;
    }

    public Transaction beginTx() throws TransactionException {
        return transactionManager.begin();
    }

    public void commitTx(Transaction tx) throws RollbackException, TransactionException {
        transactionManager.commit(tx);
    }

    public void rollbackTx(Transaction tx) throws TransactionException {
        transactionManager.rollback(tx);
    }

    @Override
    public void close() throws IOException {
        transactionManager.close();
        schema.close();
    }

    @SuppressWarnings("unchecked")
    public static <T> T getConfiguredInstance(String className, Map<String, ?> configs) {
        try {
            Class<T> cls = (Class<T>) Class.forName(className);
            if (cls == null) {
                return null;
            }
            Object o = Utils.newInstance(cls);
            if (o instanceof Configurable) {
                ((Configurable) o).configure(configs);
            }
            return cls.cast(o);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}