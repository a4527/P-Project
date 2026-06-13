package com.smartparking.server.controller;

import com.smartparking.server.dto.VoiceAskRequest;
import com.smartparking.server.dto.VoiceAskResponse;
import com.smartparking.server.service.VoiceAnswerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceAnswerService voiceAnswerService;

    @PostMapping("/ask")
    public ResponseEntity<VoiceAskResponse> ask(@Valid @RequestBody VoiceAskRequest request) {
        return ResponseEntity.ok(new VoiceAskResponse(voiceAnswerService.ask(request.getQuestion())));
    }
}
