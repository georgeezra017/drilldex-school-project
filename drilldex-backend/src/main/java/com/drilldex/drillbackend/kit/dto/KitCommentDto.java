// src/main/java/com/drilldex/drillbackend/kit/dto/KitCommentDto.java
package com.drilldex.drillbackend.kit.dto;

import com.drilldex.drillbackend.kit.KitComment;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.util.TimeAgo;

public record KitCommentDto(
        Long id,
        String text,
        String ago,          // e.g. "2h"
        UserDto user,
        Integer likeCount
) {
    public static KitCommentDto from(KitComment c) {
        return new KitCommentDto(
                c.getId(),
                c.getText(),
                TimeAgo.of(c.getCreatedAt()),
                UserDto.from(c.getUser()),
                c.getLikeCount()
        );
    }

    public record UserDto(String name, String avatarUrl) {
        public static UserDto from(User u) {
            if (u == null) return new UserDto("User", null);
            String name = firstNonBlank(u.getDisplayName(), u.getEmail(), "User");
            // match your User entity: profilePicturePath is the avatar field
            String avatar = u.getProfilePicturePath();
            return new UserDto(name, avatar);
        }
        private static String firstNonBlank(String... xs) {
            for (String s : xs) if (s != null && !s.isBlank()) return s;
            return null;
        }
    }
}