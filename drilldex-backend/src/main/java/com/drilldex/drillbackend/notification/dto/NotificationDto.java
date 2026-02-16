package com.drilldex.drillbackend.notification.dto;

import com.drilldex.drillbackend.notification.RelatedType;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class NotificationDto {
    private Long id;
    private String title;
    private String message;
    private boolean read;
    private RelatedType relatedType;
    private Long referenceId; // still for beat/pack/kit/user
    private String slug;      // optional for beat/pack/kit
    private Long profileId;   // optional for USER
    private Long chatId;    // optional for CHAT
}