package com.dima.fooddelivery.user.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.user.domain.AppUser;
import com.dima.fooddelivery.user.domain.UserRole;
import com.dima.fooddelivery.user.persistence.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Модуль User: создание пользователей, роли, привязка управляющих к ресторанам.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Создаёт пользователя с одной ролью.
     *
     * @param allowPrivilegedRole разрешено ли выдать ADMIN; true только для вызова из админки
     */
    @Transactional
    public AppUser createUser(
            String email,
            String rawPassword,
            String fullName,
            String phone,
            UserRole role,
            boolean allowPrivilegedRole
    ) {
        if (role == UserRole.ADMIN && !allowPrivilegedRole) {
            log.warn("Попытка самостоятельно зарегистрироваться администратором: email={}", email);

            throw new BusinessRuleViolationException("Роль ADMIN не может быть получена при регистрации");
        }

        Long userId;
        try {
            userId = appUserRepository.insert(
                    email.trim(),
                    passwordEncoder.encode(rawPassword),
                    fullName.trim(),
                    phone
            );
        } catch (DuplicateKeyException exception) {
            // Уникальность e-mail проверяет база: предварительный SELECT не спасал бы
            // от двух одновременных регистраций с одним адресом.
            log.warn("Регистрация с уже занятым e-mail: email={}", email);

            throw new BusinessRuleViolationException("Пользователь с e-mail " + email + " уже зарегистрирован");
        }

        appUserRepository.addRole(userId, role);

        log.info("Зарегистрирован пользователь: userId={}, role={}", userId, role);

        return requireUser(userId);
    }

    @Transactional(readOnly = true)
    public AppUser requireUser(Long userId) {
        return appUserRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Пользователь с id=" + userId + " не найден"));
    }

    @Transactional(readOnly = true)
    public List<AppUser> getAllUsers() {
        return appUserRepository.findAll();
    }

    @Transactional
    public AppUser grantRole(Long userId, UserRole role) {
        requireUser(userId);
        appUserRepository.addRole(userId, role);

        log.info("Пользователю выдана роль: userId={}, role={}", userId, role);

        return requireUser(userId);
    }

    @Transactional
    public AppUser setEnabled(Long userId, boolean enabled) {
        requireUser(userId);
        appUserRepository.setEnabled(userId, enabled);

        log.info("Изменён доступ пользователя: userId={}, enabled={}", userId, enabled);

        return requireUser(userId);
    }

    /**
     * Привязывает пользователя к ресторану как управляющего.
     *
     * <p>Роль RESTAURANT_OWNER выдаётся здесь же: управлять рестораном, не имея роли,
     * бессмысленно, а забыть выдать её отдельным вызовом — легко.
     */
    @Transactional
    public void assignRestaurantManager(Long userId, Long restaurantId) {
        requireUser(userId);

        appUserRepository.addRole(userId, UserRole.RESTAURANT_OWNER);
        appUserRepository.addRestaurantManager(userId, restaurantId);

        log.info("Пользователь назначен управляющим рестораном: userId={}, restaurantId={}", userId, restaurantId);
    }

    @Transactional(readOnly = true)
    public List<Long> getManagedRestaurantIds(Long userId) {
        return appUserRepository.findManagedRestaurantIds(userId);
    }
}
