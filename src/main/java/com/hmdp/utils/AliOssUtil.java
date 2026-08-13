package com.hmdp.utils;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.util.UUID;

@Data
@AllArgsConstructor
@Slf4j
public class AliOssUtil {

    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;

    /**
     * 文件上传
     *
     * @param bytes    文件字节
     * @param original 原始文件名（用于提取后缀）
     * @return 文件访问URL
     */
    public String upload(byte[] bytes, String original) {
        // 生成唯一文件名
        String ext = original.substring(original.lastIndexOf("."));
        String objectName = UUID.randomUUID().toString() + ext;

        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            ossClient.putObject(bucketName, objectName, new ByteArrayInputStream(bytes));
        } catch (OSSException oe) {
            log.error("OSS上传失败: {} {}", oe.getErrorCode(), oe.getErrorMessage());
            throw new RuntimeException("文件上传失败");
        } catch (ClientException ce) {
            log.error("OSS客户端异常: {}", ce.getMessage());
            throw new RuntimeException("文件上传失败");
        } finally {
            ossClient.shutdown();
        }

        String url = "https://" + bucketName + "." + endpoint + "/" + objectName;
        log.info("文件上传成功: {}", url);
        return url;
    }
}
