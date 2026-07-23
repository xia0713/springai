package com.example.springai.pojo;

import lombok.Data;

@Data
public class User {
    private String name;      // 姓名
    private Integer age;      // 年龄
    private String city;      // 城市
    private String email;     // 邮箱
    private String occupation; // 职业
}
