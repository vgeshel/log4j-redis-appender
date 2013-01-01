/**
* This file is part of log4j2redis
*
* Copyright (c) 2012 by Pavlo Baron (pb at pbit dot org)
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
*

* @author Pavlo Baron <pb at pbit dot org>
* @author Landro Silva
* @author Ryan Tenney <ryan@10e.us>
* @copyright 2012 Pavlo Baron
**/

package com.ryantenney.log4j;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.spi.ErrorCode;
import org.apache.log4j.spi.LoggingEvent;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.util.SafeEncoder;

public class RedisAppender extends AppenderSkeleton {

    // Log4J properties
    private String host = "localhost";
    private int port = 6379;
    private String password;
    private String key;

    private int batchSize = 100;
    private long period = 500;
    private boolean alwaysBatch = true;
    private boolean purgeOnFailure = true;

    private int messageIndex = 0;
    private Queue<String> messages;
    private byte[][] batch;

    // Redis connection and messages buffer
    private Jedis jedis;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;

    @Override
    public void activateOptions() {
        super.activateOptions();

        if (key == null) throw new IllegalStateException("Must set 'key'");

        jedis = new Jedis(host, port);

        messages = new ConcurrentLinkedQueue<String>();
        batch = new byte[batchSize][];

        executor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("RedisAppender"));
        task = executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                send();
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void append(LoggingEvent event) {
        try {
        	messages.add(layout.format(event));
        } catch (Exception e) {
        	errorHandler.error(e.getMessage(), e, ErrorCode.GENERIC_FAILURE);
        }
    }

    @Override
    public void close() {
    	try {
	        task.cancel(false);
	        executor.shutdown();
	        send();
	        jedis.disconnect();
    	} catch (Exception e) {
            errorHandler.error(e.getMessage(), e, ErrorCode.CLOSE_FAILURE);
    	}
    }

    private boolean connect() {
    	try {
            if (!jedis.isConnected()) {
                jedis.connect();

                if (password != null) {
                    if ("OK".equals(jedis.auth(password))) {
                    	throw new JedisConnectionException("AUTH failure");
                    }
                }
            }
            return true;
        } catch (JedisConnectionException e) {
            errorHandler.error(e.getMessage(), e, ErrorCode.GENERIC_FAILURE);
            return false;
        }
    }

    private void send() {
        if (!connect()) {
        	if (purgeOnFailure) {
        	    messages.clear();
        	    messageIndex = 0;
        	}
            return;
        }

        try {
            if (messageIndex == batchSize) push();

            String message;
            while ((message = messages.poll()) != null) {
                batch[messageIndex++] = SafeEncoder.encode(message);

                if (messageIndex == batchSize) push();
            }

            if (!alwaysBatch && messageIndex > 0) push();
        } catch (JedisConnectionException e) {
            errorHandler.error(e.getMessage(), e, ErrorCode.WRITE_FAILURE);
        }
    }

    private void push() {
        jedis.rpush(SafeEncoder.encode(key),
              batchSize == messageIndex
                  ? batch
                  : Arrays.copyOf(batch, messageIndex));
        messageIndex = 0;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setPeriod(long millis) {
        this.period = millis;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public void setBatchSize(int batchsize) {
        this.batchSize = batchsize;
    }

    public void setPurgeOnFailure(boolean purgeOnFailure) {
        this.purgeOnFailure = purgeOnFailure;
    }

    public void setAlwaysBatch(boolean alwaysBatch) {
        this.alwaysBatch = alwaysBatch;
    }

    public boolean requiresLayout() {
        return true;
    }

}
