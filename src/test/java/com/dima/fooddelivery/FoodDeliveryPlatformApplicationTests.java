package com.dima.fooddelivery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.liquibase.enabled=false",
        "preliquibase.enabled=false"
})
class FoodDeliveryPlatformApplicationTests {

    @Test
    void contextLoads() {
    }

}
