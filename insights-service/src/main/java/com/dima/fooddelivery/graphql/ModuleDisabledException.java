package com.dima.fooddelivery.graphql;

/** Поле запрошено, а модуль, который его отдаёт, выключен своим app.stores.*.enabled. */
public class ModuleDisabledException extends RuntimeException {

    public ModuleDisabledException(String message) {
        super(message);
    }
}
