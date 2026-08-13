package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.service.IUploadService;
import com.hmdp.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;

@Slf4j
@Service
public class UploadServiceImpl implements IUploadService {

    @Resource
    private AliOssUtil aliOssUtil;

    @Override
    public Result uploadImage(MultipartFile image) {
        if (image.isEmpty()) {
            return Result.fail("文件不能为空");
        }
        try {
            String url = aliOssUtil.upload(image.getBytes(), image.getOriginalFilename());
            log.debug("文件上传成功，{}", url);
            return Result.ok(url);
        } catch (IOException e) {
            log.error("文件读取失败", e);
            return Result.fail("文件上传失败");
        }
    }
}
