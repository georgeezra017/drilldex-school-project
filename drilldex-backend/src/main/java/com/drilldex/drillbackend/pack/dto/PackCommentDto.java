package com.drilldex.drillbackend.pack.dto;

import com.drilldex.drillbackend.pack.PackComment;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.util.TimeAgo;

public record PackCommentDto(
        Long id,
        String text,
        String ago,          // formatted age string
        UserDto user,
        Integer likeCount
) {
    public static PackCommentDto from(PackComment c) {
        return new PackCommentDto(
                c.getId(),
                c.getText(),
                TimeAgo.of(c.getCreatedAt()),   // ⬅️ use your util
                UserDto.from(c.getUser()),
                c.getLikeCount()
        );
    }

    public record UserDto(String name, String avatarUrl) {
        public static UserDto from(User u) {
            if (u == null) return new UserDto("User", null);

            String name = firstNonBlank(u.getDisplayName(), u.getEmail(), "User");
            String avatar = toPublicUrl(u.getProfilePicturePath());

            return new UserDto(name, avatar);
        }

        private static String firstNonBlank(String... xs) {
            if (xs == null) return null;
            for (String s : xs) {
                if (s != null && !s.isBlank()) return s;
            }
            return null;
        }

        private static String toPublicUrl(String path) {
            if (path == null || path.isBlank()) return null;
            String p = path.trim();
            if (p.startsWith("http://") || p.startsWith("https://")) return p;
            // normalize leading slashes and prefix your uploads mount
            p = p.replaceFirst("^/+", "");
            return "/uploads/" + p;
        }
    }
}