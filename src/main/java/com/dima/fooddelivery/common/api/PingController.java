package com.dima.fooddelivery.common.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

    @GetMapping("/api/v1/ping")
    public PingResponse ping() {
        return new PingResponse("ok");
    }
}
