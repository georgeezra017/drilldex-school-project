// src/main/java/com/drilldex/drillbackend/user/dto/ArtistWithBeatsDto.java
package com.drilldex.drillbackend.user.dto;

import com.drilldex.drillbackend.beat.BeatDto;
import java.util.List;

public record ArtistWithBeatsDto(
        String displayName,
        String profilePicture,
        List<BeatDto> beats
) {}