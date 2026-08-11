package com.dima.fooddelivery.user.service;

import com.dima.fooddelivery.user.domain.UserRole;
import com.dima.fooddelivery.user.persistence.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Создаёт первого администратора.
 *
 * <p>Задача курицы и яйца: роль ADMIN выдаётся только из админки, а войти в админку может
 * только администратор. Кто-то должен появиться в обход этого правила.
 *
 * <p>Вариант «положить администратора в миграцию» отвергнут: пароль оказался бы в репозитории
 * и уехал бы во все окружения сразу. Здесь учётка создаётся из переменных окружения и только
 * если администраторов ещё нет вообще — повторный запуск ничего не перезапишет.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap {

    private final AppUserRepository appUserRepository;
    private final UserService userService;

    @Value("${app.security.bootstrap-admin.email:}")
    private String adminEmail;

    @Value("${app.security.bootstrap-admin.password:}")
    private String adminPassword;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void createAdminIfMissing() {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            log.info(
                    "Учётная запись администратора не задана. Чтобы создать её, задайте "
                            + "APP_ADMIN_EMAIL и APP_ADMIN_PASSWORD и перезапустите приложение"
            );
            return;
        }

        if (appUserRepository.existsByEmail(adminEmail)) {
            log.debug("Администратор уже существует, создание пропущено");
            return;
        }

        userService.createUser(
                adminEmail,
                adminPassword,
                "Администратор",
                null,
                UserRole.ADMIN,
                true
        );

        log.info("Создана учётная запись администратора: {}", adminEmail);
    }
}
