// src/main/java/com/drilldex/drillbackend/chat/ChatMessageRepository.java
package com.drilldex.drillbackend.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // conversation (A <-> B), ascending by time
    List<ChatMessage> findBySenderIdAndReceiverIdOrReceiverIdAndSenderIdOrderByTimestampAsc(
            Long senderId, Long receiverId, Long receiverId2, Long senderId2
    );


    // Last message with a specific partner
    ChatMessage findTopBySenderIdAndReceiverIdOrReceiverIdAndSenderIdOrderByTimestampDesc(
            Long s1, Long r1, Long s2, Long r2
    );


    @Query(value = """
        WITH msgs AS (
            SELECT
                CASE WHEN sender_id = :uid THEN receiver_id ELSE sender_id END AS partner_id,
                timestamp,
                content
            FROM chat_messages
            WHERE sender_id = :uid OR receiver_id = :uid
        ),
        ranked AS (
            SELECT
                partner_id,
                timestamp AS last_time,
                content   AS last_content,
                ROW_NUMBER() OVER (PARTITION BY partner_id ORDER BY timestamp DESC) AS rn
            FROM msgs
        )
        SELECT
            partner_id     AS partnerId,
            last_time      AS lastTime,
            last_content   AS lastContent
        FROM ranked
        WHERE rn = 1
        ORDER BY last_time DESC
        """, nativeQuery = true)
    List<ThreadRow> findThreads(@Param("uid") Long uid);
}