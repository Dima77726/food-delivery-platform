package com.dima.fooddelivery.common.api;

import com.dima.fooddelivery.common.db.DbPingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DbPingController {

    private final DbPingService dbPingService;

    @GetMapping("/api/v1/db/ping")
    public PingResponse pingDatabase(){
        log.info("Received request to ping database");

        String status = dbPingService.ping();

        log.info("Returning database ping response: status={}", status);
        return new PingResponse(status);
    }
}
