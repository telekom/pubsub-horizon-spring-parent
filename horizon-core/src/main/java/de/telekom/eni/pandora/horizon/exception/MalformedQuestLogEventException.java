package de.telekom.eni.pandora.horizon.exception;

public class MalformedQuestLogEventException extends Exception {
    public MalformedQuestLogEventException(String msg) {
        super(msg);
    }

    public MalformedQuestLogEventException(String msg, Throwable t) {
        super(msg, t);
    }
}
