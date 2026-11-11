package com.example.cashdesk.paxfix.terminal;

/** Signals that the total deadline of an INPAS operation has expired. */
final class InpasTimeoutException extends Exception {

    private static final long serialVersionUID = 1L;

    InpasTimeoutException(String stage) {
        super("Превышено время ожидания INPAS: " + stage);
    }
}

