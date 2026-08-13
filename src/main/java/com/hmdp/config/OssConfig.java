package com.hmdp.config;

import com.hmdp.utils.AliOssProperties;
import com.hmdp.utils.AliOssUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;

@Configuration
public class OssConfig {

    @Resource
    private AliOssProperties aliOssProperties;

    @Bean
    public AliOssUtil aliOssUtil() {
        return new AliOssUtil(
                aliOssProperties.getEndpoint(),
                aliOssProperties.getAccessKeyId(),
                aliOssProperties.getAccessKeySecret(),
                aliOssProperties.getBucketName()
        );
    }
}
