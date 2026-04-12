package com.dima.fooddelivery.restaurant.service;

import com.dima.fooddelivery.restaurant.api.RestaurantResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RestaurantService {
    public List<RestaurantResponse> getRestaurantAll(){
        return List.of(
                new RestaurantResponse(1L, "Pizza House", "Moscow", true),
                new RestaurantResponse(2L, "Dodo Pizza", "Saint Petersburg", false),
                new RestaurantResponse(3L, "Land Pizza", "Pyatigorsk", true)
        );
    }
}
