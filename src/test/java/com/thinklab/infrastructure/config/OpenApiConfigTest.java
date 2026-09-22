package com.thinklab.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenApiConfigTest {

    @Test
    void anchorClassIsInstantiable() {
        assertNotNull(new OpenApiConfig());
    }
}
