package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BlogLikeDTO {
    private Long userId;
    private String nickName;
    private String icon;
    private LocalDateTime likeTime;
}
