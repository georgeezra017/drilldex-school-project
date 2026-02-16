// com.drilldex.drillbackend.beat.dto.BeatCommentDto
package com.drilldex.drillbackend.beat.dto;

import com.drilldex.drillbackend.beat.BeatComment;
import com.drilldex.drillbackend.util.TimeAgo;

public record BeatCommentDto(
        Long id,
        String text,
        String ago,
        UserMini user
) {
    public static BeatCommentDto from(BeatComment c) {
        var u = c.getUser();
        return new BeatCommentDto(
                c.getId(),
                c.getText(),
                TimeAgo.of(c.getCreatedAt()),
                new UserMini(
                        u.getId(),
                        u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getEmail(),
                        u.getProfilePicturePath() // make sure you have this field on User; otherwise compute a default
                )
        );
    }

    public record UserMini(Long id, String name, String avatarUrl) {}
}