package com.drilldex.drillbackend.user;

import com.drilldex.drillbackend.auth.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/follow")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;
    private final com.drilldex.drillbackend.user.CurrentUserService currentUserService;
    private final UserRepository userRepository;

    @PostMapping("/{userId}")
    public ResponseEntity<Integer> follow(@PathVariable Long userId) {
        User me = currentUserService.getCurrentUserOrThrow();
        followService.follow(me, userId);

        int newCount = userRepository.findById(userId)
                .map(u -> u.getFollowers() != null ? u.getFollowers().size() : 0)
                .orElse(0);

        return ResponseEntity.ok(newCount);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Integer> unfollow(@PathVariable Long userId) {
        User me = currentUserService.getCurrentUserOrThrow();
        followService.unfollow(me, userId);

        int newCount = userRepository.findById(userId)
                .map(u -> u.getFollowers() != null ? u.getFollowers().size() : 0)
                .orElse(0);

        return ResponseEntity.ok(newCount);
    }

    @GetMapping("/{userId}/is-following")
    public ResponseEntity<Boolean> isFollowing(@PathVariable Long userId) {
        User me = currentUserService.getCurrentUserOrThrow();
        return ResponseEntity.ok(followService.isFollowing(me, userId));
    }
}