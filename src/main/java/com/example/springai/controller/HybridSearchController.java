package com.example.springai.controller;

import com.example.springai.service.AdvancedRagService;
import com.example.springai.service.HybridRagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/hybrid")
public class HybridSearchController {

    @Autowired
    private HybridRagService hybridRagService;
    @Autowired
    private AdvancedRagService advancedRagService;


    @GetMapping("/ask")
    public String ask(@RequestParam String query) {

        String answer1 = hybridRagService.answer(query);

        return answer1;
    }


    @GetMapping("/askReranker")
    public String askReranker(@RequestParam String query) {

        String answer1 = advancedRagService.answer(query);

        return answer1;
    }

}
