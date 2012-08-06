package com.rabbitmq.jms.admin;

import java.io.IOException;
import java.io.Serializable;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.TopicConnection;
import javax.jms.TopicConnectionFactory;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;

import com.rabbitmq.jms.client.RMQConnection;
import com.rabbitmq.jms.util.Util;

/**
 * RabbitMQ Implementation of JMS {@link ConnectionFactory}
 */
@SuppressWarnings("serial")
public class RMQConnectionFactory implements ConnectionFactory, Referenceable, Serializable, QueueConnectionFactory, TopicConnectionFactory {

    private static final String DEFAULT_USERNAME = "guest";
    private static final String DEFAULT_PASSWORD = "guest";

    @Override
    public Connection createConnection() throws JMSException {
        return createConnection(DEFAULT_USERNAME, DEFAULT_PASSWORD);
    }

    @Override
    public Connection createConnection(String userName, String password) throws JMSException {
        com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setUsername(DEFAULT_PASSWORD);
        factory.setPassword(password);
        factory.setVirtualHost("/");
        factory.setHost("localhost");
        factory.setPort(5672);
        com.rabbitmq.client.Connection rabbitConnection = null;
        try {
            rabbitConnection = factory.newConnection();
        }catch (IOException x) {
            Util.util().handleException(x);
        }
        return new RMQConnection(rabbitConnection);
    }

    @Override
    public Reference getReference() throws NamingException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public TopicConnection createTopicConnection() throws JMSException {
        return (TopicConnection)createConnection();
    }

    @Override
    public TopicConnection createTopicConnection(String userName, String password) throws JMSException {
        return (TopicConnection)createConnection(userName,password);    }

    @Override
    public QueueConnection createQueueConnection() throws JMSException {
        return (QueueConnection)createConnection();
    }

    @Override
    public QueueConnection createQueueConnection(String userName, String password) throws JMSException {
        return (QueueConnection)createConnection(userName,password);
    }
    
    

}