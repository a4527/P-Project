package com.smartparking.server.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VoiceAskRequest {
    @NotBlank
    private String question;
}
