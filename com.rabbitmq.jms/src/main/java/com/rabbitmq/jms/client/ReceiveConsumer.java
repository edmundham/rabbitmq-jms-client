package com.rabbitmq.jms.client;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.jms.MessageConsumer;

import net.jcip.annotations.GuardedBy;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.jms.util.Abortable;
import com.rabbitmq.jms.util.TimeTracker;

/**
 * A {@link Consumer} to feed messages into a receive buffer. It has control methods <code>register()</code>,
 * <code>cancel()</code>, <code>abort()</code>, <code>start()</code> and <code>stop()</code>.
 * <p>
 * This is used to support the JMS semantics described in {@link MessageConsumer#receive()} and
 * {@link MessageConsumer#receive(long)}.
 * </p>
 */
class ReceiveConsumer implements Consumer, Abortable {
    private static final GetResponse EOF_RESPONSE = new GetResponse(null, null, null, 0);

    private static final long CANCELLATION_TIMEOUT = 1000; // milliseconds

    private final int batchingSize;
    private final Channel channel;
    private final RMQMessageConsumer rmqMessageConsumer;
    private final BlockingQueue<GetResponse> buffer;

    private final Completion completion = new Completion(); // RabbitMQ called handleCancelOK.
    private final String consumerTag;

    private final Object lock = new Object(); // synchronising lock
    @GuardedBy("lock") private boolean aborted = false;
    @GuardedBy("lock") private boolean cancelled = false;

    ReceiveConsumer(RMQMessageConsumer rmqMessageConsumer, BlockingQueue<GetResponse> buffer, int batchingSize) {
        this.batchingSize = Math.max(batchingSize, 1); // must be at least 1
        this.rmqMessageConsumer = rmqMessageConsumer;
        this.buffer = buffer;
        this.channel = rmqMessageConsumer.getSession().getChannel();
        this.consumerTag = RMQMessageConsumer.newConsumerTag(); // generate unique consumer tag for our private use
    }

    @Override
    public void handleConsumeOk(String consumerTag) {
        synchronized (this.lock) {
            log("handleConsumeOK");
        }
    }

    @Override
    public void handleCancelOk(String consumerTag) {
        synchronized (this.lock) {
            log("handleCancelOk");
            this.completion.setComplete();
        }
    }

    @Override
    public void handleCancel(String consumerTag) throws IOException {
        synchronized (this.lock) {
            log("handleCancel");
            this.abort();
        }
    }

    @Override
    public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body) throws IOException {
        log("handleDelivery");
        GetResponse response = new GetResponse(envelope, properties, body, 1);
        this.handleDelivery(consumerTag, response);
    }

    private final void handleDelivery(String consumerTag, GetResponse response) throws IOException {
        this.cancel(false); // cancel and don't wait for completion

        synchronized (this.lock) {
            if (!this.aborted && this.buffer.size() < this.batchingSize) { // room in buffer
                try {
                    log("handleDelivery", "put messsage");
                    this.buffer.put(response);
                    return;
                } catch (InterruptedException e) {
                    log("handleDelivery",e,"buffer.put");
                    Thread.currentThread().interrupt(); // reinstate interrupt status
                    this.abort();                       // we abort if interrupted
                }
            }
            /* Drop through if we do not put message in buffer. */
            /* We never ACK any message, that is the responsibility of the caller. */
            try {
                log("handleDelivery", "NACK messsage");
                this.channel.basicNack(response.getEnvelope().getDeliveryTag(),
                                       false, // single message
                                       true); // requeue this message
            } catch (IOException e) {
                log("handleDelivery",e,response.getEnvelope());
                this.abort();
                throw e; // RabbitMQ should close the channel
            }
        }
    }

    @Override
    public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
        log("handleShutdownSignal");
        this.abort();
    }

    @Override
    public void handleRecoverOk(String consumerTag) {
        log("handleRecoverOk");
        // noop
    }

    /**
     * Issue Consumer cancellation, and wait for the confirmation from the server.
     */
    public void cancel() {
        this.cancel(true); // wait for completion
    }

    private final void cancel(boolean wait) {
        log("cancel", wait);
        synchronized (this.lock) {
            if (!this.cancelled) {
                try {
                    this.channel.basicCancel(this.consumerTag);
                    this.cancelled = true;
                } catch (ShutdownSignalException x) {
                    log("cancel", x, "basicCancel");
                    this.abort();
                } catch (IOException x) {
                    if (!(x.getCause() instanceof ShutdownSignalException)) {
                        log("cancel", x, "basicCancel");
                    }
                    this.abort();
                }
            }
        }
        if (wait) { // don't wait holding the lock
            try {
                this.completion.waitUntilComplete(new TimeTracker(CANCELLATION_TIMEOUT, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                log("cancel", e, "waitUntilComplete");
            } catch (InterruptedException e) {
                this.abort();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void abort() {
        log("abort");
        synchronized (this.lock) {
            if (this.aborted)
                return;
            this.aborted = true;
            try {
                this.buffer.put(EOF_RESPONSE);
            } catch (InterruptedException _) {
                // we are aborting anyway
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void stop() {
    }

    @Override
    public void start() {
    }

    void register() {
        log("register");
        try {
            this.channel.basicConsume(this.rmqMessageConsumer.rmqQueueName(), // queue we are listening on
                                      false, // no autoAck - caller does all ACKs
                                      this.consumerTag, // generated on construction
                                      this.rmqMessageConsumer.getNoLocalNoException(), // noLocal option
                                      false, // not exclusive
                                      null, // no arguments
                                      this); // drive this consumer
        } catch (IOException e) {
            log("register", e, "basicConsume");
        }
    }

    public static boolean isEOFMessage(GetResponse response) {
        return response==EOF_RESPONSE;
    }

    private static final boolean LOGGING = false;

    private final void log(String s, Exception x, Object ... c) {
        if (LOGGING) {
            log("Exception ("+x+") in "+s, c);
        }
    }

    private final void log(String s, Object ... c) {
        if (LOGGING) {
            StringBuilder sb = new StringBuilder(s).append('(');
            boolean first = true;
            for (Object obj : c) {
                if (first) first = false;
                else sb.append(", ");
                sb.append(String.valueOf(obj));
            }
            log(sb.append(')').toString());
        }
    }

    private final void log(String s) {
        if (LOGGING)
            System.err.println("--->ReceiveConsumer("+String.valueOf(this.consumerTag)+"): "+s);
    }
}