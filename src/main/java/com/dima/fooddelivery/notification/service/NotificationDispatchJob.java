package com.dima.fooddelivery.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Разгребает очередь уведомлений.
 *
 * <p>Планировщик простой, без блокировок — в одном экземпляре приложения этого достаточно.
 * При запуске нескольких инстансов два процесса начнут разбирать одну очередь и часть
 * уведомлений уйдёт дважды; лечится либо {@code SELECT ... FOR UPDATE SKIP LOCKED},
 * либо ShedLock. Отмечено здесь, а не сделано: до горизонтального масштабирования проект
 * ещё не дошёл, и решать эту задачу сейчас — преждевременное усложнение.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationDispatchJob {

    private final NotificationService notificationService;

    @Scheduled(fixedDelayString = "${app.notification.dispatch-interval:PT30S}")
    public void dispatch() {
        int sent = notificationService.dispatchPending();

        if (sent > 0) {
            log.info("Отправлено уведомлений: {}", sent);
        }
    }
}
