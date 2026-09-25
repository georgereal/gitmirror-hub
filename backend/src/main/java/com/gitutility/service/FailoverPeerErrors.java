package com.gitutility.service;

import java.util.Locale;

public final class FailoverPeerErrors {

    private FailoverPeerErrors() {}

    public static boolean looksUnreachable(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 6) {
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            String name = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (name.contains("unknownhost") || name.contains("connectexception")
                    || name.contains("sockettimeout") || name.contains("nosrouteto")
                    || message.contains("connection refused") || message.contains("timed out")
                    || message.contains("timeout") || message.contains("unknown host")
                    || message.contains("network is unreachable") || message.contains("503")
                    || message.contains("502") || message.contains("504")
                    || message.contains("remote hung up") || message.contains("connection reset")) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }
}
