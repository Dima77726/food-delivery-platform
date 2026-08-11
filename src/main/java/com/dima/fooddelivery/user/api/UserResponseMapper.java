package com.dima.fooddelivery.user.api;

import com.dima.fooddelivery.user.domain.AppUser;

import java.util.List;

public final class UserResponseMapper {

    public static UserResponse toResponse(AppUser user) {
        return new UserResponse(
                user.id(),
                user.email(),
                user.fullName(),
                user.phone(),
                user.enabled(),
                user.roles(),
                user.createdAt()
        );
    }

    public static List<UserResponse> toResponses(List<AppUser> users) {
        return users.stream().map(UserResponseMapper::toResponse).toList();
    }

    private UserResponseMapper() {
    }
}
