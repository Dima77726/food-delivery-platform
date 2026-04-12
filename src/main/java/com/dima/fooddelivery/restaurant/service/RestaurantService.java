package com.dima.fooddelivery.restaurant.service;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.restaurant.api.RestaurantResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class RestaurantService {
    public List<RestaurantResponse> getRestaurantAll(){
        return List.of(
                new RestaurantResponse(1L, "Pizza House", "Moscow", true),
                new RestaurantResponse(2L, "Dodo Pizza", "Saint Petersburg", false),
                new RestaurantResponse(3L, "Land Pizza", "Pyatigorsk", true)
        );
    }

    public RestaurantResponse getRestaurantById(Long id){
        log.debug("Searching restaurant by id={}", id);

        return getRestaurantAll().stream()
                .filter(restaurant -> restaurant.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant with id=" + id + " not found"));
    }
}
