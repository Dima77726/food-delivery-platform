package com.dima.fooddelivery.common.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DbPingService {

    private final JdbcTemplate jdbcTemplate;

    public String ping(){
        log.debug("Pinging");

        Integer result = jdbcTemplate.queryForObject("select 1",  Integer.class);

        if(result == null || result != 1){
            log.error("Database ping failed: unexpected result={}", result);
            throw new IllegalStateException("Database ping failed: unexpected result");
        }

        log.info("Database ping successful");
        return "ok";
    }
}
