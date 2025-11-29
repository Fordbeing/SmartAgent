package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 文档管理服务类
 * 负责文档的删除、预览、下载等管理操作
 */
@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private UserRepository userRepository;

    /**
     * 删除文档及其相关数据
     */
    @Transactional
    public void deleteDocument(String fileMd5, String userId) {
        logger.info("开始删除文档: {}", fileMd5);

        try {
            FileUpload fileUpload = fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId)
                    .orElseThrow(() -> new RuntimeException("文件不存在"));

            // 1. 删除Elasticsearch中的数据
            try {
                elasticsearchService.deleteByFileMd5(fileMd5);
                logger.info("成功从Elasticsearch删除文档: {}", fileMd5);
            } catch (Exception e) {
                logger.error("从Elasticsearch删除文档时出错: {}", fileMd5, e);
            }

            // 2. 删除MinIO中的文件
            try {
                String objectName = "merged/" + fileUpload.getFileName();
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build()
                );
                logger.info("成功从MinIO删除文件: {}", objectName);
            } catch (Exception e) {
                logger.error("从MinIO删除文件时出错: {}", fileMd5, e);
            }

            // 3. 删除DocumentVector记录
            try {
                documentVectorRepository.deleteByFileMd5(fileMd5);
                logger.info("成功删除文档向量记录: {}", fileMd5);
            } catch (Exception e) {
                logger.error("删除文档向量记录时出错: {}", fileMd5, e);
            }

            // 4. 删除FileUpload记录
            fileUploadRepository.deleteByFileMd5(fileMd5);
            logger.info("成功删除文件上传记录: {}", fileMd5);

            logger.info("文档删除完成: {}", fileMd5);
        } catch (Exception e) {
            logger.error("删除文档过程中发生错误: {}", fileMd5, e);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取用户可访问的所有文件列表
     */
    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        logger.info("获取用户可访问文件列表: userId={}", userId);

        try {
            User user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));

            List<String> userEffectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());

            List<FileUpload> files;
            if (userEffectiveTags.isEmpty()) {
                files = fileUploadRepository.findByUserIdOrIsPublicTrue(userId);
            } else {
                files = fileUploadRepository.findAccessibleFilesWithTags(userId, userEffectiveTags);
            }

            logger.info("成功获取用户可访问文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户可访问文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取可访问文件列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取用户上传的所有文件列表
     */
    public List<FileUpload> getUserUploadedFiles(String userId) {
        logger.info("获取用户上传的文件列表: userId={}", userId);
        try {
            return fileUploadRepository.findByUserId(userId);
        } catch (Exception e) {
            logger.error("获取用户上传的文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取用户上传的文件列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成文件下载链接
     */
    public String generateDownloadUrl(String fileMd5) {
        logger.info("生成文件下载链接: fileMd5={}", fileMd5);

        try {
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            String objectName = "merged/" + fileUpload.getFileName();

            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket("uploads")
                            .object(objectName)
                            .expiry(3600)
                            .build()
            );
        } catch (Exception e) {
            logger.error("生成文件下载链接失败: fileMd5={}", fileMd5, e);
            return null;
        }
    }

    /**
     * 获取文件预览内容 (核心修改方法)
     */
    public String getFilePreviewContent(String fileMd5, String fileName) {
        logger.info("获取文件预览内容: fileMd5={}, fileName={}", fileMd5, fileName);

        try {
            String objectName = "merged/" + fileName;
            String fileExtension = getFileExtension(fileName).toLowerCase();

            // 获取文件流 (Try-with-resources 自动关闭流)
            try (InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket("uploads")
                            .object(objectName)
                            .build())) {

                // 1. 如果是 docx 文件，使用 POI 解析
                if ("docx".equals(fileExtension)) {
                    return readDocxContent(inputStream);
                }
                // 2. 如果是纯文本文件，使用字符流读取
                else if (isTextFile(fileExtension)) {
                    return readPlainContent(inputStream);
                }
                // 3. 其他二进制文件 (doc, pdf, xlsx, images等)，返回元数据
                else {
                    return getNoPreviewMessage(fileMd5, fileName, fileExtension);
                }
            }

        } catch (Exception e) {
            logger.error("获取文件预览内容失败: fileMd5={}, fileName={}", fileMd5, fileName, e);
            return "预览失败: " + e.getMessage();
        }
    }

    /**
     * 解析 Word (.docx) 文件内容
     */
    private String readDocxContent(InputStream inputStream) {
        try (XWPFDocument document = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {

            String text = extractor.getText();
            // 限制预览长度，避免过大
            if (text.length() > 3000) {
                return text.substring(0, 3000) + "\n\n... (文档过长，仅展示前3000字)";
            }
            return text;
        } catch (IOException e) {
            logger.error("解析Docx文件失败", e);
            return "Word文档解析失败，可能文件已损坏或加密。";
        }
    }

    /**
     * 读取普通纯文本内容
     */
    private String readPlainContent(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder content = new StringBuilder();
        String line;
        int bytesRead = 0;
        int maxBytes = 10240; // 10KB

        while ((line = reader.readLine()) != null && bytesRead < maxBytes) {
            content.append(line).append("\n");
            bytesRead += line.getBytes(StandardCharsets.UTF_8).length + 1;
        }

        String result = content.toString();
        if (bytesRead >= maxBytes) {
            result += "\n... (内容已截断，仅显示前10KB)";
        }
        return result;
    }

    /**
     * 生成不支持预览的提示信息
     */
    private String getNoPreviewMessage(String fileMd5, String fileName, String ext) {
        try {
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5).orElse(null);
            String sizeStr = (fileUpload != null) ? formatFileSize(fileUpload.getTotalSize()) : "未知";
            String timeStr = (fileUpload != null && fileUpload.getCreatedAt() != null) ? fileUpload.getCreatedAt().toString() : "未知";

            return String.format(
                    "文件名: %s\n" +
                            "文件大小: %s\n" +
                            "文件类型: %s\n" +
                            "上传时间: %s\n\n" +
                            "此文件格式 (%s) 不支持在线文本预览，请下载后查看。",
                    fileName, sizeStr, ext.toUpperCase(), timeStr, ext
            );
        } catch (Exception e) {
            return "此文件不支持预览，且无法获取文件详情。";
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }

    /**
     * 判断是否为纯文本文件
     * 注意：已移除 doc, docx, pdf，因为它们是二进制文件
     */
    private boolean isTextFile(String extension) {
        // 移除了 doc, docx, pdf，因为它们不是纯文本
        String[] textExtensions = {
                "txt", "md", "html", "htm", "xml", "json",
                "csv", "log", "java", "js", "ts", "py", "cpp", "c", "h", "css",
                "scss", "less", "sql", "yml", "yaml", "properties", "conf", "config", "ini", "sh", "bat"
        };

        return Arrays.stream(textExtensions)
                .anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(Long size) {
        if (size == null) return "0 B";

        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
}