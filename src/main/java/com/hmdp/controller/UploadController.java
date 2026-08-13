package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IUploadService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;

@RestController
@RequestMapping("upload")
public class UploadController {

    @Resource
    private IUploadService uploadService;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        return uploadService.uploadImage(image);
    }
}
