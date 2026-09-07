package com.gitutility.service;

/**
 * Thrown when another Hub instance holds the pair lease so AMQP can requeue the message.
 */
public class PairLeaseBusyException extends RuntimeException {

    public PairLeaseBusyException(String message) {
        super(message);
    }
}
