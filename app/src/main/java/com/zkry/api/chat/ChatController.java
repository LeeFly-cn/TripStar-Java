package com.zkry.api.chat;

import com.zkry.trip.dto.TripChatRequest;
import com.zkry.trip.dto.TripChatResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @PostMapping("/ask")
    public TripChatResponse ask(@RequestBody TripChatRequest request) {
        String message = request.message() == null ? "" : request.message();
        String reply = "这是 Java 后端学习版 AIChat mock 回复。你问的是：「" + message
            + "」。后续这里会接入 Spring AI Alibaba，并把当前 trip_plan 作为上下文传给模型。";
        return new TripChatResponse(true, reply);
    }
}
