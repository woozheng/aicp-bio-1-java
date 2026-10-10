package com.aicp.core;

@FunctionalInterface
public interface Plugin {
    Envelop execute(Envelop envelop, Agent agent);
}
