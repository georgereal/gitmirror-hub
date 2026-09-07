package com.gitutility.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RootApiControllerTest {

    private final RootApiController controller = new RootApiController();

    @Test
    void testApiRootReturnsOkAndEndpoints() {
        ResponseEntity<Map<String, Object>> response = controller.getApiRootInfo();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("GitMirror Hub API", response.getBody().get("service"));
        assertEquals("UP", response.getBody().get("status"));
        assertTrue(response.getBody().containsKey("endpoints"));
    }
}
