package com.example.springai.dto;

import lombok.Data;
import java.util.Map;

@Data
public class SearchResult {
    private String id;
    private String content;
    private Map<String, Object> metadata;
    private double distance;  // 越小越相似
}