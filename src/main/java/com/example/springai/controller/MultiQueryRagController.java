package com.example.springai.controller;


import com.example.springai.service.MultiQueryRagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/multi")
public class MultiQueryRagController {

    @Autowired
    private MultiQueryRagService  multiQueryRagService;



    @GetMapping("/multi")
    public String multi(@RequestParam String query) {

        String answer1 = multiQueryRagService.multi(query);

        return answer1;
    }

}
