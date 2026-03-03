package com.debateai.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.debateai.dto.DebateRequest;
import com.debateai.dto.DebateResponseView;
import com.debateai.dto.DebateResult;
import com.debateai.service.DebateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/debate")
public class DebateController {

    private final DebateService debateService;
    private final DebateResponseMapper debateResponseMapper;

    public DebateController(DebateService debateService, DebateResponseMapper debateResponseMapper) {
        this.debateService = debateService;
        this.debateResponseMapper = debateResponseMapper;
    }

    @PostMapping
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ResponseEntity<DebateResponseView> debate(@Valid @RequestBody DebateRequest request) {
        try {
            DebateResult result = debateService.runDebate(request);
            return ResponseEntity.ok(debateResponseMapper.from(result));
        } catch (IllegalStateException ex) {
            if ("All agents failed. Debate aborted.".equals(ex.getMessage())) {
                DebateResponseView aborted = debateResponseMapper.fromAborted(
                        request.topic(),
                        "Debate failed due to unavailable agent outputs"
                );
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(aborted);
            }
            throw ex;
        }
    }
}
