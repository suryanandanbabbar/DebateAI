package com.debateai.controller;

import com.debateai.dto.DebateRequest;
import com.debateai.dto.DebateResult;
import com.debateai.service.DebateService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/debate")
public class DebateController {

    private final DebateService debateService;

    public DebateController(DebateService debateService) {
        this.debateService = debateService;
    }

    @PostMapping
    public ResponseEntity<DebateResult> debate(@Valid @RequestBody DebateRequest request) {
        return ResponseEntity.ok(debateService.runDebate(request));
    }
}
