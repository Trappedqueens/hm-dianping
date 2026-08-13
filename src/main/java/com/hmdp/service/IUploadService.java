package com.hmdp.service;

import com.hmdp.dto.Result;
import org.springframework.web.multipart.MultipartFile;

public interface IUploadService {

    Result uploadImage(MultipartFile image);
}
