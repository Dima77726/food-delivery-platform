package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.persistence.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Модуль Notification.
 *
 * <p>Уведомление сначала кладётся в таблицу со статусом PENDING в той же транзакции, что и
 * бизнес-операция, и только потом отправляется отдельным шагом. Это паттерн outbox, и он решает
 * конкретную проблему: если отправлять письмо прямо в транзакции, то при её откате письмо
 * уже улетело, а заказа нет. Обратный порядок — отправка после коммита без записи в базу —
 * теряет уведомления при падении процесса.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int SEND_BATCH_SIZE = 100;

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;

    @Transactional
    public Long enqueue(
            Long recipientId,
            NotificationChannel channel,
            String type,
            String subject,
            String body,
            Long orderId
    ) {
        Long notificationId = notificationRepository.insert(recipientId, channel, type, subject, body, orderId);

        log.debug("Уведомление поставлено в очередь: notificationId={}, type={}", notificationId, type);

        return notificationId;
    }

    /**
     * Отправляет накопившиеся уведомления.
     *
     * <p>Каждое в своей транзакции: одно упавшее не должно откатывать остальные.
     * Вызывается планировщиком {@link NotificationDispatchJob}.
     */
    public int dispatchPending() {
        List<Notification> pending = notificationRepository.findPending(SEND_BATCH_SIZE);

        int sent = 0;
        for (Notification notification : pending) {
            if (dispatchOne(notification)) {
                sent++;
            }
        }

        return sent;
    }

    @Transactional
    public boolean dispatchOne(Notification notification) {
        try {
            notificationSender.send(notification);
            notificationRepository.markSent(notification.id());

            return true;
        } catch (RuntimeException exception) {
            log.warn(
                    "Не удалось отправить уведомление: notificationId={}, reason={}",
                    notification.id(),
                    exception.getMessage()
            );

            notificationRepository.markFailed(notification.id(), exception.getMessage());

            return false;
        }
    }

    @Transactional(readOnly = true)
    public List<Notification> getNotificationsForUser(Long userId) {
        return notificationRepository.findByRecipientId(userId);
    }

    /**
     * Канал доставки. Реализация подменяется без правки сервиса — так же, как платёжный шлюз.
     */
    public interface NotificationSender {

        void send(Notification notification);
    }
}
