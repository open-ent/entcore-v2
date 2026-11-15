package org.entcore.common.email;

import java.util.concurrent.atomic.AtomicInteger;

public class MassCreateResults {

    private final AtomicInteger success = new AtomicInteger(0);
    private final AtomicInteger failure = new AtomicInteger(0);

    public AtomicInteger getSuccess() {
        return success;
    }

    public AtomicInteger getFailure() {
        return failure;
    }

}